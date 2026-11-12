/* PROTOTYPE · 状态模型（纯逻辑，不碰 DOM）
 *
 * 演示是「脚本驱动」的：4 个步骤完全由 data.js 定义。
 * 状态 = 从空记忆开始，按顺序应用第 1..n 步（抽取 / 合并 / 更新 / 关联 / 演进 / 遗忘），
 * 因此任何一步都可以从零重建，截图与演示可复现。
 *
 * 这里只模拟产品视角的状态；真实 Agent Memory 的 add/search/list/update/evolve
 * 由 Java 样例调用，原型不连任何后端。
 */
(function () {
  'use strict';

  var DATA = window.ProtoData;

  function clone(value) {
    return JSON.parse(JSON.stringify(value));
  }

  function initialState() {
    return {
      step: 0,
      currentSession: 1,
      nextMessageId: 1,
      sessions: [{
        id: 1,
        title: DATA.sessions[0].title,
        time: DATA.sessions[0].time,
        memoryDelta: DATA.sessions[0].memoryDelta
      }],
      messages: [{
        id: 'msg-0',
        session: 1,
        role: 'assistant',
        kind: 'plain',
        ts: '10:00',
        text: '您好，我是信贷智能体。开始对话后，我会把重要信息交给 Agent Memory 记住，' +
          '并在下一次对话时带着记忆回答——每轮都会同时给出「有记忆」和「无记忆基线」两个回答。'
      }],
      units: [],
      ops: [],
      graph: { entities: [], relations: [], episodes: [] },
      recall: null
    };
  }

  /* ---------------- 基础操作 ---------------- */

  function pushMessage(state, session, role, text, ts, extra) {
    var message = {
      id: 'msg-' + state.nextMessageId++,
      session: session,
      role: role,
      text: text,
      ts: ts
    };
    if (extra) {
      Object.keys(extra).forEach(function (key) { message[key] = extra[key]; });
    }
    state.messages.push(message);
    return message;
  }

  function setLifecycle(state, unitId, lifecycle) {
    var unit = state.units.filter(function (item) { return item.id === unitId; })[0];
    if (unit) { unit.lifecycle = lifecycle; }
  }

  function upsertEntity(graph, entity) {
    var existing = graph.entities.filter(function (item) { return item.id === entity.id; })[0];
    if (existing) {
      Object.keys(entity).forEach(function (key) {
        if (key === 'unitIds') {
          existing.unitIds = (existing.unitIds || []).concat(entity.unitIds || []);
        } else {
          existing[key] = entity[key];
        }
      });
      return;
    }
    graph.entities.push(clone(entity));
  }

  function upsertRelation(graph, relation) {
    var existing = graph.relations.filter(function (item) { return item.id === relation.id; })[0];
    if (existing) {
      Object.assign(existing, relation);
    } else {
      graph.relations.push(clone(relation));
    }
  }

  function applyGraph(state, ops) {
    if (!ops) { return; }
    (ops.addEntities || []).forEach(function (entity) { upsertEntity(state.graph, entity); });
    (ops.updateEntities || []).forEach(function (patch) {
      var entity = state.graph.entities.filter(function (item) { return item.id === patch.id; })[0];
      if (entity) {
        Object.keys(patch).forEach(function (key) {
          if (key === 'unitIds') {
            entity.unitIds = (entity.unitIds || []).concat(patch.unitIds || []);
          } else {
            entity[key] = patch[key];
          }
        });
      }
    });
    (ops.forgetEntities || []).forEach(function (id) {
      var entity = state.graph.entities.filter(function (item) { return item.id === id; })[0];
      if (entity) { entity.forgotten = true; }
    });
    (ops.addRelations || []).forEach(function (relation) { upsertRelation(state.graph, relation); });
    (ops.updateRelations || []).forEach(function (patch) {
      var relation = state.graph.relations.filter(function (item) { return item.id === patch.id; })[0];
      if (relation) { Object.assign(relation, patch); }
    });
    (ops.removeRelations || []).forEach(function (id) {
      state.graph.relations = state.graph.relations.filter(function (item) { return item.id !== id; });
    });
    (ops.addEpisodes || []).forEach(function (episode) {
      state.graph.episodes.push(clone(episode));
    });
  }

  /* ---------------- 应用一步 ---------------- */

  function applyStep(state, step) {
    state.step = step.n;

    if (step.createsSession && !state.sessions.some(function (session) { return session.id === step.session; })) {
      state.currentSession = step.session;
      var meta = DATA.sessions[step.session - 1];
      state.sessions.push({
        id: meta.id,
        title: meta.title,
        time: meta.time,
        memoryDelta: meta.memoryDelta
      });
    }

    if (step.createsSession && step.systemText) {
      pushMessage(state, step.session, 'system', step.systemText, step.at);
    }
    if (step.createsSession && step.sessionIntro) {
      pushMessage(state, step.session, 'assistant', step.sessionIntro, step.at);
    }

    pushMessage(state, step.session, 'customer', step.customerText, step.at);

    (step.units || []).forEach(function (unit) {
      var copy = clone(unit);
      copy.step = step.n;
      copy.createdAt = step.at;
      state.units.push(copy);
    });

    (step.ops || []).forEach(function (op, index) {
      if (op.type === 'update' && op.oldUnitId) { setLifecycle(state, op.oldUnitId, 'superseded'); }
      if (op.type === 'forget' && op.unitId) { setLifecycle(state, op.unitId, 'forgotten'); }
      if (op.type === 'evolve' && op.oldUnitId) { setLifecycle(state, op.oldUnitId, 'superseded'); }
      var copy = clone(op);
      copy.id = 'op-' + step.n + '-' + index;
      copy.step = step.n;
      copy.stepTitle = step.title;
      state.ops.push(copy);
    });

    applyGraph(state, step.graph);

    if (step.recall) {
      state.recall = clone(step.recall);
      state.recall.step = step.n;
      state.recall.at = step.at;
      state.recall.customerText = step.customerText;
    }

    pushMessage(state, step.session, 'agent', null, step.at, {
      kind: 'dual',
      step: step.n,
      stepTitle: step.title,
      answers: clone(step.answers)
    });

    state.currentSession = step.session;
  }

  function buildState(stepCount) {
    var state = initialState();
    var count = Math.max(0, Math.min(stepCount || 0, DATA.steps.length));
    for (var index = 0; index < count; index++) {
      applyStep(state, DATA.steps[index]);
    }
    return state;
  }

  /* ---------------- 派生数据 ---------------- */

  function unitById(state, unitId) {
    return state.units.filter(function (unit) { return unit.id === unitId; })[0] || null;
  }

  function entityById(state, entityId) {
    return state.graph.entities.filter(function (entity) { return entity.id === entityId; })[0] || null;
  }

  function relationById(state, relationId) {
    return state.graph.relations.filter(function (relation) { return relation.id === relationId; })[0] || null;
  }

  function unitsForEntity(state, entityId) {
    return state.units.filter(function (unit) {
      return (unit.entities || []).indexOf(entityId) >= 0;
    });
  }

  function lifecycleCounts(state) {
    var counts = { active: 0, superseded: 0, forgotten: 0, archived: 0 };
    state.units.forEach(function (unit) {
      if (counts[unit.lifecycle] == null) { counts[unit.lifecycle] = 0; }
      counts[unit.lifecycle] += 1;
    });
    return counts;
  }

  function tierCounts(state) {
    var counts = {};
    state.units.forEach(function (unit) {
      if (unit.lifecycle === 'forgotten') { return; }
      counts[unit.tier] = (counts[unit.tier] || 0) + 1;
    });
    return counts;
  }

  function opCounts(state) {
    var counts = { extract: 0, merge: 0, update: 0, associate: 0, evolve: 0, forget: 0 };
    state.ops.forEach(function (op) {
      if (counts[op.type] == null) { counts[op.type] = 0; }
      counts[op.type] += 1;
    });
    return counts;
  }

  function opsByStep(state) {
    var groups = [];
    DATA.steps.forEach(function (step) {
      var ops = state.ops.filter(function (op) { return op.step === step.n; });
      if (!ops.length) { return; }
      groups.push({
        step: step.n,
        title: step.title,
        short: step.short,
        at: step.at,
        completedStep: state.step,
        ops: ops
      });
    });
    return groups.reverse();
  }

  function recallHits(state) {
    if (!state.recall) { return []; }
    return state.recall.hits.map(function (hit) {
      var unit = unitById(state, hit.unitId);
      return Object.assign({}, hit, {
        unit: unit,
        content: unit ? unit.content : hit.unitId,
        tier: unit ? unit.tier : 'semantic',
        lifecycle: unit ? unit.lifecycle : 'active'
      });
    });
  }

  function injectedHits(state) {
    return recallHits(state).filter(function (hit) { return hit.injected; });
  }

  function isRecalledUnit(state, unitId) {
    if (!state.recall) { return false; }
    return state.recall.hits.some(function (hit) {
      return hit.unitId === unitId && hit.injected;
    });
  }

  function recallScore(state, unitId) {
    if (!state.recall) { return null; }
    var hit = state.recall.hits.filter(function (item) { return item.unitId === unitId; })[0];
    return hit ? hit.score : null;
  }

  function graphRecallIds(state) {
    var ids = {};
    if (state.recall && state.recall.graphHits) {
      state.recall.graphHits.forEach(function (hit) { ids[hit.id] = true; });
    }
    return ids;
  }

  function messagesForSession(state, sessionId) {
    return state.messages.filter(function (message) { return message.session === sessionId; });
  }

  function latestSessionId(state) {
    return state.sessions[state.sessions.length - 1].id;
  }

  function viewingHistory(state) {
    return state.currentSession !== latestSessionId(state);
  }

  function nextStep(state) {
    return state.step < DATA.steps.length ? DATA.steps[state.step] : null;
  }

  function canOpenNewSession(state) {
    return state.step === 1 && state.currentSession === 1;
  }

  function selectSession(state, sessionId) {
    if (!state.sessions.some(function (session) { return session.id === sessionId; })) { return state; }
    var next = clone(state);
    next.currentSession = sessionId;
    return next;
  }

  function openNewSession(state) {
    if (!canOpenNewSession(state)) { return state; }
    var next = clone(state);
    var meta = DATA.sessions[1];
    next.sessions.push({
      id: meta.id,
      title: meta.title,
      time: meta.time,
      memoryDelta: meta.memoryDelta
    });
    next.currentSession = 2;
    pushMessage(next, 2, 'system', '新会话已开始 · 上一段对话的记忆仍可被 Agent Memory 召回', '10:05');
    return next;
  }

  function stats(state) {
    var life = lifecycleCounts(state);
    return {
      totalUnits: state.units.length,
      activeUnits: life.active,
      supersededUnits: life.superseded,
      forgottenUnits: life.forgotten,
      entities: state.graph.entities.filter(function (entity) { return !entity.forgotten; }).length,
      relations: state.graph.relations.length,
      episodes: state.graph.episodes.length,
      messages: state.messages.filter(function (message) { return message.role !== 'system'; }).length,
      injected: injectedHits(state).length
    };
  }

  window.MemoryModel = {
    DATA: DATA,
    initialState: initialState,
    buildState: buildState,
    selectSession: selectSession,
    openNewSession: openNewSession,
    unitById: unitById,
    entityById: entityById,
    relationById: relationById,
    unitsForEntity: unitsForEntity,
    lifecycleCounts: lifecycleCounts,
    tierCounts: tierCounts,
    opCounts: opCounts,
    opsByStep: opsByStep,
    recallHits: recallHits,
    injectedHits: injectedHits,
    isRecalledUnit: isRecalledUnit,
    recallScore: recallScore,
    graphRecallIds: graphRecallIds,
    messagesForSession: messagesForSession,
    latestSessionId: latestSessionId,
    viewingHistory: viewingHistory,
    nextStep: nextStep,
    canOpenNewSession: canOpenNewSession,
    stats: stats
  };
})();
