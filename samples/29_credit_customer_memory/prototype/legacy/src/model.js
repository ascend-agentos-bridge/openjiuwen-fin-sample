/* PROTOTYPE · 记忆状态模型（纯逻辑，不碰 DOM）
 *
 * 产品视角的“记忆”状态机：
 *   - 客户发一句话 → 智能体抽取并记住若干信息（客户画像 / 事实 / 事件 / 变量）
 *   - 开启新对话 → 生成会话摘要，长期记忆保留
 *   - 客户追问 → 从长期记忆里召回相关条目，用于本轮回答
 *   - 客户改主意 / 要求删除 → 更新或删除记忆
 *   - 重新打开应用 → 记忆仍然在本机数据库里
 *
 * 技术细节（SQLite 表名、SDK 类名、向量索引重建）只在 README / research 中说明，界面不展示。
 */
(function () {
  'use strict';

  var DATA = window.ProtoData;
  var TURNS = DATA.turns;
  var USER = 'C1001';
  var SCOPE = '__default__';
  var NOW = '2026-11-11';

  var GRAPH_RECALL_SCORES = {
    'e-zhouyanqing': 0.96,
    'e-business-loan': 0.93,
    'e-equal-principal': 0.91,
    'e-plan-3y': 0.90,
    'e-plan-5y': 0.87,
    'e-overdue-2023': 0.85,
    'e-precision': 0.82,
    'e-no-interest-first': 0.80,
    'e-change-event': 0.78,
    'r-prefers': 0.94,
    'r-plan': 0.88,
    'r-experienced': 0.84,
    'r-applies': 0.81,
    'r-runs': 0.76
  };

  var RECALL_SCORES = {
    'm-prof-repay': 0.93,
    'm-sem-plan5y': 0.90,
    'm-prof-identity': 0.87,
    'm-epi-overdue': 0.84,
    'm-sum-session1': 0.82,
    'm-epi-materials': 0.79,
    'm-sem-no-interest-first': 0.76
  };

  function clone(value) {
    return JSON.parse(JSON.stringify(value));
  }

  function categoryOf(id) {
    return DATA.categories.filter(function (category) { return category.id === id; })[0];
  }

  function nextTs(state) {
    var base = 10 * 60 + state.clock;
    state.clock += 1;
    return ('0' + Math.floor(base / 60)).slice(-2) + ':' + ('0' + (base % 60)).slice(-2);
  }

  function fullTs(state) {
    return NOW + ' ' + nextTs(state);
  }

  function initialState() {
    return {
      session: 1,
      sessions: [{ session: 1 }],
      turnIndex: 0,
      clock: 0,
      messageSeq: 1,
      messages: [{
        id: 'msg-000',
        session: 1,
        role: 'assistant',
        ts: '09:10',
        text: '您好，我是信贷智能体，可以帮您分析经营贷方案。您想先了解什么？'
      }],
      memories: [],
      vars: {},
      recall: null,
      graph: { entities: [], relations: [], episodes: [] },
      graphRecall: null,
      multiRecall: null,
      pipelineRuns: [],
      modelConfig: clone(DATA.defaultModelConfig),
      vectorStatus: 'ready',
      hasRestarted: false,
      lastAction: { type: 'RESET', added: 0, updated: 0, deleted: 0, recalled: 0 },
      newMemIds: [],
      db: {
        userMessages: [{
          message_id: 'msg-000',
          user_id: USER,
          scope_id: SCOPE,
          content: '您好，我是信贷智能体，可以帮您分析经营贷方案。您想先了解什么？',
          session_id: DATA.sessionIds[1],
          role: 'assistant',
          timestamp: NOW + ' 09:10'
        }],
        kvRows: [],
        sessionKv: []
      }
    };
  }

  function addMessage(state, role, text, ts, extra) {
    var message = {
      id: 'msg-' + ('000' + state.messageSeq++).slice(-3),
      session: state.session,
      role: role,
      text: text,
      ts: ts || nextTs(state)
    };
    if (extra) { Object.keys(extra).forEach(function (key) { message[key] = extra[key]; }); }
    state.messages.push(message);
    if (role !== 'system') {
      state.db.userMessages.push({
        message_id: message.id,
        user_id: USER,
        scope_id: SCOPE,
        content: text,
        session_id: DATA.sessionIds[state.session],
        role: role === 'customer' ? 'user' : 'assistant',
        timestamp: NOW + ' ' + message.ts
      });
    }
    return message;
  }

  function saveCheckpoint(state) {
    var sessionId = DATA.sessionIds[state.session];
    var key = sessionId + ':agent:credit-advisor:agent_state_blobs';
    var existing = state.db.sessionKv.filter(function (row) { return row.key === key; })[0];
    if (existing) {
      existing.value = '客户对话进行中';
    } else {
      state.db.sessionKv.push({ key: key, value: '客户对话进行中' });
    }
  }

  function memoryKvKey(memory) {
    return 'UMD/' + USER + '/' + SCOPE + '/' + memory.memId;
  }

  function variableKvKey(name) {
    return 'user_var/' + USER + '/' + SCOPE + '/' + name;
  }

  function syncKvRows(state) {
    state.db.kvRows = state.memories
      .filter(function (memory) { return memory.status === 'active'; })
      .map(function (memory) {
        return { key: memoryKvKey(memory), kind: 'memory', memType: memory.memType };
      });
    Object.keys(state.vars).forEach(function (name) {
      state.db.kvRows.push({ key: variableKvKey(name), kind: 'variable' });
    });
  }

  function insertMemory(state, write, ts) {
    var memory = {
      memId: write.memId,
      layer: write.layer,
      content: write.content,
      memType: write.memType,
      createdAt: ts || nextTs(state),
      status: 'active',
      version: 1
    };
    state.memories.push(memory);
    state.newMemIds.push(memory.memId);
    return memory;
  }

  function updateMemory(state, update, ts) {
    var memory = state.memories.filter(function (item) { return item.memId === update.memId; })[0];
    if (!memory) { return; }
    memory.updatedFrom = memory.content;
    memory.content = update.content;
    memory.version += 1;
    memory.updatedAt = ts || nextTs(state);
    state.newMemIds.push(memory.memId);
  }

  function deleteMemory(state, memId) {
    var memory = state.memories.filter(function (item) { return item.memId === memId; })[0];
    if (!memory) { return; }
    memory.status = 'deleted';
    state.newMemIds.push(memId);
  }

  function upsertVariable(state, change, ts) {
    var previous = state.vars[change.name];
    state.vars[change.name] = {
      value: change.value,
      updatedAt: ts || nextTs(state),
      version: previous ? previous.version + 1 : 1,
      previous: previous ? previous.value : null
    };
  }

  function activeSessionMessages(state) {
    return state.messages.filter(function (message) {
      return message.session === state.session && message.role !== 'system';
    });
  }

  function buildRecallHits(state) {
    if (state.vectorStatus === 'empty') {
      /* 重新打开后应用内索引为空：摘要类记忆无法被语义召回，其余记忆按本地文本兜底命中 */
      return state.memories
        .filter(function (memory) { return memory.status === 'active' && memory.layer !== 'summary'; })
        .map(function (memory) {
          return {
            memId: memory.memId,
            layer: memory.layer,
            score: (RECALL_SCORES[memory.memId] || 0.66) * 0.62
          };
        })
        .sort(function (a, b) { return b.score - a.score; })
        .slice(0, 5);
    }
    return state.memories
      .filter(function (memory) { return memory.status === 'active'; })
      .map(function (memory) {
        return {
          memId: memory.memId,
          layer: memory.layer,
          score: RECALL_SCORES[memory.memId] || 0.66
        };
      })
      .sort(function (a, b) { return b.score - a.score; })
      .slice(0, 5);
  }

  function emitRecall(state, query) {
    state.recall = {
      query: query,
      at: fullTs(state),
      hits: buildRecallHits(state)
    };
  }

  function graphEntity(state, id) {
    return state.graph.entities.filter(function (entity) { return entity.id === id; })[0];
  }

  function upsertGraphEntity(state, entity) {
    var existing = graphEntity(state, entity.id);
    if (existing) {
      existing.label = entity.label;
      existing.type = entity.type;
      return 0;
    }
    state.graph.entities.push({ id: entity.id, label: entity.label, type: entity.type });
    return 1;
  }

  function upsertGraphRelation(state, relation) {
    var existing = state.graph.relations.filter(function (item) { return item.id === relation.id; })[0];
    if (existing) {
      Object.assign(existing, relation);
      return 0;
    }
    state.graph.relations.push(Object.assign({}, relation));
    return 1;
  }

  function upsertEpisode(state, episode) {
    var existing = state.graph.episodes.filter(function (item) { return item.id === episode.id; })[0];
    if (existing) {
      Object.assign(existing, episode);
      return 0;
    }
    state.graph.episodes.push(Object.assign({}, episode));
    return 1;
  }

  function applyGraphOps(state, ops) {
    var stats = { entitiesAdded: 0, relationsAdded: 0, episodesAdded: 0, removed: 0 };
    if (!ops) { return stats; }
    (ops.entities || []).forEach(function (entity) { stats.entitiesAdded += upsertGraphEntity(state, entity); });
    (ops.relations || []).forEach(function (relation) { stats.relationsAdded += upsertGraphRelation(state, relation); });
    ((ops.updates || {}).relations || []).forEach(function (patch) {
      var target = state.graph.relations.filter(function (item) { return item.id === patch.id; })[0];
      if (target) { Object.assign(target, patch); }
    });
    (ops.removeRelations || []).forEach(function (id) {
      state.graph.relations = state.graph.relations.filter(function (item) { return item.id !== id; });
      stats.removed += 1;
    });
    (ops.removeEntities || []).forEach(function (id) {
      state.graph.entities = state.graph.entities.filter(function (item) { return item.id !== id; });
      state.graph.relations = state.graph.relations.filter(function (item) {
        return item.source !== id && item.target !== id;
      });
      stats.removed += 1;
    });
    (ops.removeEpisodes || []).forEach(function (id) {
      state.graph.episodes = state.graph.episodes.filter(function (item) { return item.id !== id; });
      stats.removed += 1;
    });
    (ops.episodes || []).forEach(function (episode) { stats.episodesAdded += upsertEpisode(state, episode); });
    return stats;
  }

  function emitGraphRecall(state, query) {
    var hits = [];
    state.graph.entities.forEach(function (entity) {
      if (GRAPH_RECALL_SCORES[entity.id] != null) {
        hits.push({
          kind: 'entity',
          id: entity.id,
          label: entity.label,
          type: entity.type,
          score: GRAPH_RECALL_SCORES[entity.id]
        });
      }
    });
    state.graph.relations.forEach(function (relation) {
      if (GRAPH_RECALL_SCORES[relation.id] == null) { return; }
      var source = graphEntity(state, relation.source);
      var target = graphEntity(state, relation.target);
      hits.push({
        kind: 'relation',
        id: relation.id,
        label: (source ? source.label : relation.source) + ' —' + relation.label + '→ ' +
          (target ? target.label : relation.target),
        score: GRAPH_RECALL_SCORES[relation.id]
      });
    });
    hits.sort(function (a, b) { return b.score - a.score; });
    state.graphRecall = { query: query, at: fullTs(state), hits: hits.slice(0, 7) };
  }

  function buildMultiRecall(state) {
    if (!state.recall) { state.multiRecall = null; return; }
    var vector = state.recall.hits.slice(0, 3).map(function (hit) {
      var memory = state.memories.filter(function (item) {
        return item.memId === hit.memId && item.status === 'active';
      })[0];
      return { source: '向量', label: memory ? memory.content : hit.memId };
    });
    var kvOrder = ['plannedYears', 'materialStatus', 'product', 'amount', 'term', 'customerId'];
    var kv = kvOrder.filter(function (name) { return state.vars[name]; }).slice(0, 3).map(function (name) {
      return {
        source: 'KV',
        label: (DATA.variableLabels[name] || name) + '：' + state.vars[name].value
      };
    });
    var graph = state.graphRecall ? state.graphRecall.hits.slice(0, 3).map(function (hit) {
      return { source: '图', label: hit.label, graphKind: hit.kind };
    }) : [];
    var fused = [];
    var count = Math.max(vector.length, graph.length, kv.length);
    for (var index = 0; index < count; index++) {
      if (vector[index]) { fused.push(vector[index]); }
      if (graph[index]) { fused.push(graph[index]); }
      if (kv[index]) { fused.push(kv[index]); }
    }
    state.multiRecall = {
      query: state.recall.query,
      at: fullTs(state),
      channels: { vector: vector.length, kv: kv.length, graph: graph.length },
      entries: fused.slice(0, 6)
    };
  }

  function pushPipelineRun(state, trigger, turnId, stats, durationMs, sample) {
    var id = 'run-' + ('00' + (state.pipelineRuns.length + 1)).slice(-2);
    state.pipelineRuns.unshift({
      id: id,
      at: fullTs(state),
      trigger: trigger,
      turnId: turnId,
      sessionNumber: state.session,
      stats: stats,
      durationMs: durationMs,
      sample: sample || {},
      model: {
        chatModel: state.modelConfig.chatModel,
        embeddingModel: state.modelConfig.embeddingModel,
        milvusUri: state.modelConfig.milvusUri
      }
    });
    state.pipelineRuns = state.pipelineRuns.slice(0, 8);
  }

  function latestSession(state) {
    return state.sessions[state.sessions.length - 1].session;
  }

  function viewingHistory(state) {
    return state.session !== latestSession(state);
  }

  function sessionExists(state, sessionNumber) {
    return state.sessions.some(function (item) { return item.session === sessionNumber; });
  }

  function canApply(state, action) {
    if (!action) { return false; }
    if (action.type === 'RESET' || action.type === 'UPDATE_MODEL_CONFIG') { return true; }
    if (action.type === 'SELECT_SESSION') {
      return sessionExists(state, action.session) && action.session !== state.session;
    }
    if (action.type === 'RESTART' || action.type === 'CUSTOM_MESSAGE') {
      return !viewingHistory(state);
    }
    if (action.type === 'NEXT_TURN') {
      return !viewingHistory(state) && state.turnIndex < TURNS.length &&
        TURNS[state.turnIndex].session === state.session;
    }
    if (action.type === 'NEW_SESSION') {
      return !viewingHistory(state) && state.session === 1;
    }
    if (action.type === 'REPLAY_RECALL') {
      return !viewingHistory(state) && !!state.recall;
    }
    return false;
  }

  function applyTurn(state, turn) {
    state.recall = null;
    state.graphRecall = null;
    state.multiRecall = null;
    var context = state.messages.filter(function (message) {
      return message.session === state.session && message.role !== 'system';
    }).slice(-3).map(function (message) {
      return (message.role === 'customer' ? '客户' : '智能体') + '：' + message.text;
    });
    context.push('客户：' + turn.text);
    addMessage(state, 'customer', turn.text, turn.ts);
    var added = 0;
    var updated = 0;
    var deleted = 0;
    (turn.writes || []).forEach(function (write) {
      insertMemory(state, write, turn.ts);
      added += 1;
    });
    (turn.updates || []).forEach(function (update) {
      updateMemory(state, update, turn.ts);
      updated += 1;
    });
    (turn.deletes || []).forEach(function (del) {
      deleteMemory(state, del.memId);
      deleted += 1;
    });
    (turn.vars || []).forEach(function (change) { upsertVariable(state, change, turn.ts); });
    var graphStats = applyGraphOps(state, turn.graph);
    if (turn.recall) {
      emitRecall(state, turn.recall.query);
      emitGraphRecall(state, turn.recall.query);
      buildMultiRecall(state);
    }
    saveCheckpoint(state);
    syncKvRows(state);
    var assistantExtra = {};
    if (state.recall) {
      assistantExtra.cites = state.recall.hits.length;
      assistantExtra.graphCites = state.graphRecall ? state.graphRecall.hits.length : 0;
    }
    if (added || updated || deleted || graphStats.entitiesAdded || graphStats.relationsAdded) {
      assistantExtra.processed = {
        added: added,
        updated: updated,
        deleted: deleted,
        graph: graphStats.entitiesAdded + graphStats.relationsAdded
      };
    }
    addMessage(state, 'assistant', turn.assistant.text, turn.ts, assistantExtra);
    state.lastAction = {
      type: 'TURN',
      added: added,
      updated: updated,
      deleted: deleted,
      recalled: state.recall ? state.recall.hits.length : 0,
      graph: graphStats
    };
    pushPipelineRun(state, '对话后异步触发', turn.id, {
      llmCalls: (added + updated + graphStats.entitiesAdded + graphStats.relationsAdded) > 0 ? 4 : 1,
      memoriesAdded: added,
      memoriesUpdated: updated,
      memoriesDeleted: deleted,
      variablesUpserted: (turn.vars || []).length,
      graphEntitiesAdded: graphStats.entitiesAdded,
      graphRelationsAdded: graphStats.relationsAdded,
      graphEpisodesAdded: graphStats.episodesAdded,
      graphObjectsRemoved: graphStats.removed,
      writeTargets: ['SQLite KV / 消息表', '向量索引', 'InMemory 图存储（演示）']
    }, 1150 + 160 * (added + graphStats.entitiesAdded), {
      input: context,
      outputs: {
        memories: (turn.writes || []).map(function (write) { return write.content; })
          .concat((turn.updates || []).map(function (update) { return update.content; })),
        graph: (turn.graph && (turn.graph.entities || []).map(function (entity) { return entity.label; }) || [])
          .concat((turn.graph && (turn.graph.relations || []).map(function (relation) {
            var source = graphEntity(state, relation.source);
            var target = graphEntity(state, relation.target);
            return (source ? source.label : relation.source) + ' · ' +
              relation.label + ' · ' + (target ? target.label : relation.target);
          })) || []),
        removed: (turn.deletes || []).map(function (del) { return del.memId; })
          .concat((turn.graph && (turn.graph.removeEntities || [])) || [])
      }
    });
  }

  function applyAction(state, action) {
    if (!canApply(state, action)) { return state; }
    var next = clone(state);
    next.newMemIds = [];

    if (action.type === 'RESET') {
      return initialState();
    }

    if (action.type === 'SELECT_SESSION') {
      next.session = action.session;
      next.recall = null;
      next.graphRecall = null;
      next.multiRecall = null;
      return next;
    }

    if (action.type === 'NEXT_TURN') {
      applyTurn(next, TURNS[next.turnIndex]);
      next.turnIndex += 1;
      return next;
    }

    if (action.type === 'NEW_SESSION') {
      var summary = insertMemory(next, {
        memId: 'm-sum-session1',
        layer: 'summary',
        content: DATA.summaryTemplate,
        memType: 'SUMMARY'
      }, '09:26');
      syncKvRows(next);
      next.session = 2;
      next.sessions.push({ session: 2 });
      addMessage(next, 'assistant', '新对话已开始，上一轮咨询已生成摘要，记住的信息都还在。您想继续聊什么？', '10:00');
      next.lastAction = { type: 'NEW_SESSION', added: 1, updated: 0, deleted: 0, recalled: 0 };
      pushPipelineRun(next, '新对话归档', 'summary', {
        llmCalls: 1,
        memoriesAdded: 1,
        memoriesUpdated: 0,
        memoriesDeleted: 0,
        variablesUpserted: 0,
        graphEntitiesAdded: 0,
        graphRelationsAdded: 0,
        graphEpisodesAdded: 0,
        graphObjectsRemoved: 0,
        writeTargets: ['SQLite KV', '向量索引'],
        summaryOnly: true
      }, 620, {
        input: state.messages.filter(function (message) {
          return message.session === 1 && message.role !== 'system';
        }).slice(-3).map(function (message) {
          return (message.role === 'customer' ? '客户' : '智能体') + '：' + message.text;
        }),
        outputs: { memories: [DATA.summaryTemplate], graph: [], removed: [] }
      });
      return next;
    }

    if (action.type === 'RESTART') {
      next.vectorStatus = 'empty';
      next.hasRestarted = true;
      next.recall = null;
      next.graphRecall = null;
      next.multiRecall = null;
      addMessage(next, 'assistant', '应用已重新打开，您的信息都还在。需要我继续帮您分析吗？', null);
      next.lastAction = { type: 'RESTART', added: 0, updated: 0, deleted: 0, recalled: 0 };
      return next;
    }

    if (action.type === 'REPLAY_RECALL') {
      addMessage(next, 'customer', '重新打开后，你还记得我的偏好吗？', null);
      emitRecall(next, '周砚清 还款偏好 结清计划');
      emitGraphRecall(next, '周砚清 还款偏好 结清计划');
      buildMultiRecall(next);
      addMessage(next, 'assistant', '记得：您偏好等额本息、月供敏感，计划 3 年内结清，不接受先息后本。',
        { cites: next.recall.hits.length, graphCites: next.graphRecall.hits.length });
      next.lastAction = { type: 'RECALL', added: 0, updated: 0, deleted: 0, recalled: next.recall.hits.length };
      return next;
    }

    if (action.type === 'UPDATE_MODEL_CONFIG') {
      next.modelConfig = Object.assign({}, next.modelConfig, action.config || {});
      next.modelConfig.configured = !!(next.modelConfig.apiBase && next.modelConfig.chatModel &&
        next.modelConfig.embeddingModel);
      return next;
    }

    if (action.type === 'CUSTOM_MESSAGE') {
      addMessage(next, 'customer', action.text, null);
      addMessage(next, 'assistant', '收到，我继续帮您分析。');
      saveCheckpoint(next);
      next.lastAction = { type: 'CUSTOM', added: 0, updated: 0, deleted: 0, recalled: 0 };
      return next;
    }

    return state;
  }

  function layerCounts(state) {
    var counts = { session: 0, summary: 0, profile: 0, semantic: 0, episodic: 0, variable: 0 };
    counts.session = state.db.userMessages.length;
    state.memories.forEach(function (memory) {
      if (memory.status === 'active') { counts[memory.layer] += 1; }
    });
    counts.variable = Object.keys(state.vars).length;
    return counts;
  }

  function activeMemoryCount(state) {
    return state.memories.filter(function (memory) { return memory.status === 'active'; }).length;
  }

  window.MemoryModel = {
    TURNS: TURNS,
    categories: DATA.categories,
    initialState: initialState,
    applyAction: applyAction,
    canApply: canApply,
    layerCounts: layerCounts,
    activeMemoryCount: activeMemoryCount,
    activeSessionMessages: activeSessionMessages,
    categoryOf: categoryOf,
    latestSession: latestSession,
    viewingHistory: viewingHistory,
    graphTypes: DATA.graphTypes,
    initialVars: function () { return {}; }
  };
})();
