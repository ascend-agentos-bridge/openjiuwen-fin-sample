/* PROTOTYPE · 客户对话与记忆页面（一次性原型，不是生产代码）
 *
 * 左侧：客户与智能体的对话；右侧：智能体记住了什么、存在哪里、本轮用到了哪些。
 * 技术细节（SQLite 表名 / SDK 类名 / 版本约束）不进入本页面，只留在 README 与 research/。
 */
(function () {
  'use strict';

  var DATA = window.ProtoData;
  var model = window.MemoryModel;
  var app = document.getElementById('app');

  var state = model.initialState();
  var sidebarCollapsed = false;
  var draft = '';
  var ui = { memoryTab: 'longterm', graphSelected: null, modalOpen: false };
  var pendingBottom = false;

  /* ---------------- 工具 ---------------- */

  function esc(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function category(id) {
    return model.categoryOf(id) || { name: '', color: '#64748b', storage: '', tint: '#f8fafc' };
  }

  function variableLabel(name) {
    return DATA.variableLabels[name] || name;
  }

  function activeMemories(layer) {
    return state.memories.filter(function (memory) {
      return memory.layer === layer && memory.status === 'active';
    });
  }

  function recalledIds() {
    var ids = {};
    if (state.recall) {
      state.recall.hits.forEach(function (hit) { ids[hit.memId] = true; });
    }
    return ids;
  }

  function isNew(id) {
    return (state.newMemIds || []).indexOf(id) >= 0;
  }

  /* ---------------- 动作 ---------------- */

  function dispatch(action) {
    var before = state.messages.length;
    state = model.applyAction(state, action);
    if (state.messages.length !== before || action.type === 'SELECT_SESSION') {
      pendingBottom = true;
    }
    if (action.type === 'CUSTOM_MESSAGE') { draft = ''; }
    render();
  }

  function sendCustom() {
    var text = String(draft || '').trim();
    if (!text) { return; }
    dispatch({ type: 'CUSTOM_MESSAGE', text: text });
  }

  function focusComposer() {
    var input = document.getElementById('composer-input');
    if (input) { input.focus(); }
  }

  function render() {
    var chatScroll = document.getElementById('chat-scroll');
    var chatTop = chatScroll ? chatScroll.scrollTop : 0;
    app.innerHTML = renderHeader() + renderWorkspace() + (ui.modalOpen ? renderSettingsModal() : '');
    var nextScroll = document.getElementById('chat-scroll');
    if (nextScroll) {
      nextScroll.scrollTop = pendingBottom || !chatScroll ? nextScroll.scrollHeight : chatTop;
    }
    pendingBottom = false;
    setTimeout(clearFlash, 1400);
  }

  function clearFlash() {
    Array.prototype.forEach.call(document.querySelectorAll('.is-new'), function (node) {
      node.classList.remove('is-new');
    });
  }

  /* ---------------- 顶部 ---------------- */

  function renderHeader() {
    var config = state.modelConfig;
    var modelChip = '<button class="model-chip' + (config.configured ? ' is-on' : ' is-off') +
      '" data-act="open-settings" title="配置记忆抽取与向量化使用的模型服务">' +
        '<span class="status-dot"></span>' +
        (config.configured
          ? '记忆模型：' + esc(config.chatModel)
          : '未配置记忆模型') +
      '</button>';
    return '<header class="app-header">' +
      '<div class="brand">' +
        '<div class="brand-mark">智</div>' +
        '<div class="brand-text"><strong>信贷智能体</strong><span>客户对话中的长期记忆</span></div>' +
      '</div>' +
      '<div class="header-actions">' +
        '<span class="demo-pill">演示原型 · 虚构数据</span>' +
        modelChip +
        '<button class="quiet-btn" data-act="open-settings">模型设置</button>' +
        '<button class="quiet-btn" data-act="reset">重新开始</button>' +
      '</div>' +
      '</header>';
  }

  /* ---------------- 工作区 ---------------- */

  function renderWorkspace() {
    return '<div class="app-body">' + renderSidebar() +
      '<main class="workspace">' + renderChat() + renderMemory() + '</main>' +
      '</div>';
  }

  function sessionTitle(sessionNumber) {
    var meta = DATA.sessionMeta[sessionNumber];
    return meta ? meta.title : '对话 ' + sessionNumber;
  }

  function sessionMessages(sessionNumber) {
    return state.messages.filter(function (message) { return message.session === sessionNumber; });
  }

  function sessionPreview(sessionNumber) {
    var customers = sessionMessages(sessionNumber).filter(function (message) {
      return message.role === 'customer';
    });
    var source = customers.length ? customers[customers.length - 1] : sessionMessages(sessionNumber)[0];
    return source ? source.text : '';
  }

  function sessionTime(sessionNumber) {
    var messages = sessionMessages(sessionNumber);
    return messages.length ? messages[messages.length - 1].ts : '09:10';
  }

  function renderSidebar() {
    var sessions = state.sessions.slice().reverse();
    var items = sessions.map(function (item) {
      var sessionNumber = item.session;
      var active = state.session === sessionNumber;
      var title = sessionTitle(sessionNumber);
      var preview = sessionPreview(sessionNumber);
      var count = sessionMessages(sessionNumber).filter(function (message) {
        return message.role !== 'system';
      }).length;
      return '<button class="session-item' + (active ? ' is-active' : '') + '"' +
        ' data-act="select-session" data-session="' + sessionNumber + '"' +
        ' title="' + esc(title + ' · 今天 ' + sessionTime(sessionNumber)) + '">' +
        '<span class="session-avatar">' + esc(title.slice(0, 1)) + '</span>' +
        (sidebarCollapsed ? '' :
          '<span class="session-copy">' +
            '<b>' + esc(title) + '</b>' +
            '<span class="session-time">今天 ' + esc(sessionTime(sessionNumber)) + ' · ' + count + ' 条消息</span>' +
            '<em>' + esc(preview.slice(0, 30)) + (preview.length > 30 ? '…' : '') + '</em>' +
          '</span>' +
          (active ? '<span class="session-active-dot"></span>' : '')) +
        '</button>';
    }).join('');

    var newDisabled = !model.canApply(state, { type: 'NEW_SESSION' });
    var head = '<div class="side-head">' +
      (sidebarCollapsed ? '' : '<span class="side-title">会话</span>') +
      '<button class="icon-btn side-new" data-act="new-session" title="' +
        (newDisabled ? '完成当前对话后可开启新对话' : '开启新对话') + '"' +
        (newDisabled ? ' disabled' : '') + '>＋</button>' +
      '<button class="icon-btn side-toggle" data-act="toggle-sidebar" title="' +
        (sidebarCollapsed ? '展开会话列表' : '收起会话列表') + '">' +
        (sidebarCollapsed ? '›' : '‹') + '</button>' +
      '</div>';

    return '<aside class="sidebar' + (sidebarCollapsed ? ' is-collapsed' : '') + '">' +
      head +
      '<div class="side-list">' + items + '</div>' +
      (sidebarCollapsed ? '' : '<div class="side-foot">记忆会随对话自动沉淀</div>') +
      '</aside>';
  }

  function sessionLabel() {
    return state.session === 1 ? '首次对话' : '新的对话';
  }

  function nextHint() {
    var turn = model.TURNS[state.turnIndex];
    if (!turn) { return { kind: 'done' }; }
    if (turn.session !== state.session) { return { kind: 'new-session', turn: turn }; }
    if (turn.id === 'T7' && !state.hasRestarted) { return { kind: 'restart', turn: turn }; }
    return { kind: 'turn', turn: turn };
  }

  function canOpenNewSession() {
    return nextHint().kind === 'new-session';
  }

  function renderChat() {
    var customer = DATA.customer;
    var history = model.viewingHistory(state);
    var header =
      '<header class="chat-head">' +
        '<div class="avatar avatar-customer">' + esc(customer.avatar) + '</div>' +
        '<div class="chat-person">' +
          '<b>' + esc(customer.name) + '</b>' +
          '<span>客户 · ' + esc(customer.topic) + '</span>' +
        '</div>' +
        '<div class="chat-head-right">' +
          '<span class="session-pill">' + esc(sessionTitle(state.session)) + '</span>' +
          (history ? '<span class="history-tag">历史会话</span>' : '') +
        '</div>' +
      '</header>';

    var messages = state.messages
      .filter(function (message) { return message.session === state.session; })
      .map(renderMessage)
      .join('');

    return '<section class="panel chat-panel">' +
      header +
      '<div class="chat-scroll' + (history ? ' is-history' : '') + '" id="chat-scroll">' +
        messages + renderSuggestion() +
      '</div>' +
      (history ? '' : renderComposer()) +
      '</section>';
  }

  function renderMessage(message) {
    if (message.role === 'system') {
      return '<div class="chat-system">' + esc(message.text) + '</div>';
    }
    if (message.role === 'customer') {
      return '<div class="msg msg-customer">' +
        '<div class="bubble bubble-customer">' + esc(message.text) + '</div>' +
        '<div class="msg-meta">' + esc(DATA.customer.name) + ' · ' + esc(message.ts) + '</div>' +
        '</div>';
    }
    return '<div class="msg msg-agent">' +
      '<div class="avatar avatar-agent">智</div>' +
      '<div class="msg-body">' +
        '<div class="msg-name">信贷智能体</div>' +
        '<div class="bubble bubble-agent">' + esc(message.text) + '</div>' +
        (message.cites
          ? '<div class="cite-line">本次回答参考了 ' + message.cites + ' 条长期记忆' +
            (message.graphCites ? ' · ' + message.graphCites + ' 个图关联' : '') + '</div>'
          : '') +
        (message.processed
          ? '<div class="processed-line">已提炼 ' + message.processed.added + ' 条长期记忆' +
            (message.processed.graph ? ' · ' + message.processed.graph + ' 个图对象' : '') +
            (message.processed.updated ? ' · 更新 ' + message.processed.updated + ' 条' : '') +
            (message.processed.deleted ? ' · 删除 ' + message.processed.deleted + ' 条' : '') +
            ' → 已写入记忆库</div>'
          : '') +
      '</div>' +
      '</div>';
  }

  function renderSuggestion() {
    if (model.viewingHistory(state)) {
      return '<div class="suggest-box suggest-nudge">' +
        '<div class="suggest-label">历史会话 · 仅供查看</div>' +
        '<div class="suggest-text">正在回看「' + esc(sessionTitle(state.session)) + '」。' +
          '右侧展示的是智能体当前记住的信息。</div>' +
        '<button class="primary-btn" data-act="select-session" data-session="' +
          model.latestSession(state) + '">回到当前对话</button>' +
        '</div>';
    }
    var hint = nextHint();
    if (hint.kind === 'turn') {
      return '<div class="suggest-box">' +
        '<div class="suggest-label">接下来，客户可以说</div>' +
        '<button class="suggest-bubble" data-act="next-turn">' +
          '<span>' + esc(hint.turn.text) + '</span>' +
          '<span class="suggest-send">发送</span>' +
        '</button>' +
        '</div>';
    }
    if (hint.kind === 'new-session') {
      return '<div class="suggest-box suggest-nudge">' +
        '<div class="suggest-label">试试换个对话</div>' +
        '<div class="suggest-text">开启新对话后，之前的记忆会保留；客户会接着问：' +
          '<b>' + esc(hint.turn.text) + '</b></div>' +
        '<button class="primary-btn" data-act="new-session">开启新对话</button>' +
        '</div>';
    }
    if (hint.kind === 'restart') {
      return '<div class="suggest-box suggest-nudge">' +
        '<div class="suggest-label">验证记忆是否还在</div>' +
        '<div class="suggest-text">先模拟重新打开应用，再看看客户再问一次时，智能体是否还记得。</div>' +
        '<button class="primary-btn" data-act="restart">模拟重新打开应用</button>' +
        '</div>';
    }
    return '<div class="suggest-box suggest-nudge">' +
      '<div class="suggest-label">演示完成</div>' +
      '<div class="suggest-text">记忆已经沉淀在本机。可以重新打开应用验证持久化，或重新开始演示。</div>' +
      '<div class="suggest-actions">' +
        '<button class="primary-btn" data-act="restart">模拟重新打开应用</button>' +
        '<button class="ghost-btn" data-act="reset">重新开始</button>' +
      '</div>' +
      '</div>';
  }

  function renderComposer() {
    return '<div class="composer">' +
      '<input id="composer-input" type="text" maxlength="200"' +
      ' placeholder="以客户身份回复…" value="' + esc(draft) + '">' +
      '<button class="send-btn" data-act="send"' + (draft.trim() ? '' : ' disabled') + '>发送</button>' +
      '</div>';
  }

  /* ---------------- 右侧：记忆 ---------------- */

  function renderMemory() {
    var counts = model.layerCounts(state);
    var memoryCount = model.activeMemoryCount(state);
    var graphCount = state.graph.entities.length + state.graph.relations.length + state.graph.episodes.length;
    var stats =
      '<span class="stat"><b>' + state.db.userMessages.length + '</b> 条消息</span>' +
      '<span class="stat"><b>' + memoryCount + '</b> 条记忆</span>' +
      '<span class="stat"><b>' + graphCount + '</b> 个图对象</span>';

    var tabs = [
      { id: 'longterm', label: '长期记忆', count: memoryCount },
      { id: 'graph', label: '图记忆', count: state.graph.entities.length },
      { id: 'pipeline', label: '加工流程', count: state.pipelineRuns.length }
    ].map(function (tab) {
      return '<button class="memory-tab' + (ui.memoryTab === tab.id ? ' is-active' : '') +
        '" data-act="mem-tab" data-tab="' + tab.id + '">' +
        esc(tab.label) + '<b>' + tab.count + '</b></button>';
    }).join('');

    var content = ui.memoryTab === 'graph' ? renderGraphTab()
      : ui.memoryTab === 'pipeline' ? renderPipelineTab()
      : renderLongTermTab();

    return '<aside class="panel memory-panel">' +
      '<header class="memory-head">' +
        '<div class="memory-title">智能体的记忆 <span class="memory-count">' + memoryCount + '</span></div>' +
        '<div class="memory-sub">从对话里自动记住的信息</div>' +
      '</header>' +
      '<div class="storage-card">' +
        '<div class="storage-icon">' +
          '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">' +
            '<ellipse cx="12" cy="5" rx="7" ry="3"></ellipse>' +
            '<path d="M5 5v6c0 1.7 3.1 3 7 3s7-1.3 7-3V5"></path>' +
            '<path d="M5 11v6c0 1.7 3.1 3 7 3s7-1.3 7-3v-6"></path>' +
          '</svg>' +
        '</div>' +
        '<div class="storage-copy">' +
          '<b>' + esc(DATA.meta.storageTitle) + '</b>' +
          '<span>' + esc(DATA.meta.storageDesc) + '</span>' +
        '</div>' +
        '<div class="storage-stats">' + stats + '</div>' +
      '</div>' +
      (model.viewingHistory(state)
        ? '<div class="history-note">正在回看历史会话 · 右侧展示智能体当前记住的信息</div>'
        : '') +
      '<div class="memory-tabs">' + tabs + '</div>' +
      content +
      '</aside>';
  }

  function renderLongTermTab() {
    return '<div class="memory-tab-content">' +
      (model.viewingHistory(state) ? '' : renderRecallCard()) +
      '<div class="memory-scroll">' + renderGroups() + '</div>' +
      '<footer class="memory-foot">' +
        '<span class="activity">' + esc(activityText()) + '</span>' +
        (model.viewingHistory(state)
          ? '<button class="link-btn" data-act="select-session" data-session="' +
            model.latestSession(state) + '">回到当前对话</button>'
          : '<button class="link-btn" data-act="restart">模拟重新打开应用</button>') +
      '</footer>' +
      '</div>';
  }

  function renderRecallCard() {
    if (!state.recall || !state.recall.hits.length) { return ''; }
    var multi = state.multiRecall;
    var channels = multi ? multi.channels : { vector: state.recall.hits.length, kv: 0, graph: 0 };
    var channelChips =
      '<span class="channel-chip channel-vector"><i></i>向量 ' + channels.vector + '</span>' +
      '<span class="channel-chip channel-kv"><i></i>KV ' + channels.kv + '</span>' +
      '<span class="channel-chip channel-graph"><i></i>图记忆 ' + channels.graph + '</span>';
    var entries = multi ? multi.entries : state.recall.hits.map(function (hit) {
      return { source: '向量', label: hit.memId };
    });
    var list = entries.map(function (entry) {
      return '<div class="multi-entry">' +
        '<span class="source-tag source-' + (entry.source === '向量' ? 'vector' : entry.source === 'KV' ? 'kv' : 'graph') + '">' +
          esc(entry.source) + '</span>' +
        '<span class="multi-label">' + esc(entry.label) + '</span>' +
        '</div>';
    }).join('');
    return '<section class="recall-card multi-recall-card">' +
      '<div class="recall-head">' +
        '<span class="recall-title">本轮召回 · 多路融合</span>' +
        '<span class="recall-count">' + state.recall.hits.length + ' 条</span>' +
        '<span class="recall-sub">向量 / KV / 图记忆三路结果，由样例层融合</span>' +
      '</div>' +
      '<div class="recall-channels">' + channelChips + '</div>' +
      '<div class="multi-list">' + list + '</div>' +
      '</section>';
  }

  function renderGroups() {
    var recall = recalledIds();
    var groups = [];
    DATA.categories.forEach(function (cat) {
      var items = cat.id === 'variable' ? variableItems() : activeMemories(cat.id).slice().reverse();
      if (!items.length) { return; }
      groups.push(
        '<section class="memory-group">' +
          '<div class="group-head">' +
            '<span class="group-dot" style="background:' + cat.color + '"></span>' +
            '<span class="group-name">' + esc(cat.name) + '</span>' +
            '<span class="group-count">' + items.length + '</span>' +
            '<span class="group-storage"><span class="storage-pin"></span>存于 ' + esc(cat.storage) + '</span>' +
          '</div>' +
          '<div class="group-hint">' + esc(cat.hint) + '</div>' +
          '<div class="group-items">' + items.map(function (item) {
            return renderMemoryItem(item, cat, !!recall[item.id]);
          }).join('') + '</div>' +
        '</section>'
      );
    });
    if (!groups.length) {
      return '<div class="memory-empty">' +
        '<div class="memory-empty-icon">✦</div>' +
        '<div class="memory-empty-title">对话开始后，记忆会出现在这里</div>' +
        '<div class="memory-empty-text">智能体会把客户说的重要信息按类型记住：用户画像、语义记忆、情景记忆、摘要记忆和变量记忆。</div>' +
        '</div>';
    }
    return groups.join('');
  }

  function variableItems() {
    return Object.keys(state.vars).map(function (name) {
      var variable = state.vars[name];
      return {
        id: 'var-' + name,
        layer: 'variable',
        content: variableLabel(name) + '：' + variable.value,
        version: variable.version,
        previous: variable.previous,
        updatedAt: variable.updatedAt
      };
    }).reverse();
  }

  function renderMemoryItem(item, cat, recalled) {
    var badges = [];
    var fresh = isNew(item.id);
    if (item.version > 1) {
      badges.push('<span class="badge badge-update">' + (fresh ? '刚刚更新' : '已更新') + '</span>');
    } else if (fresh) {
      badges.push('<span class="badge badge-new">' + (cat.id === 'summary' ? '刚刚生成' : '刚刚记住') + '</span>');
    }
    if (recalled) { badges.push('<span class="badge badge-recall">本轮用到</span>'); }
    var oldValue = item.updatedFrom || item.previous;
    return '<div class="mem-card' + (fresh ? ' is-new' : '') + (recalled ? ' is-recalled' : '') + '">' +
      '<div class="mem-card-main">' +
        '<div class="mem-text">' + esc(item.content) + '</div>' +
        (badges.length ? '<div class="mem-badges">' + badges.join('') + '</div>' : '') +
      '</div>' +
      (oldValue ? '<div class="mem-old">原：' + esc(oldValue) + '</div>' : '') +
      '</div>';
  }

  function activityText() {
    var action = state.lastAction;
    if (!action) { return ''; }
    if (action.type === 'TURN') {
      var parts = [];
      if (action.added) { parts.push('记住 ' + action.added + ' 条'); }
      if (action.updated) { parts.push('更新 ' + action.updated + ' 条'); }
      if (action.deleted) { parts.push('删除 ' + action.deleted + ' 条'); }
      if (action.recalled) { parts.push('本轮用到 ' + action.recalled + ' 条记忆'); }
      return parts.length ? '刚刚：' + parts.join(' · ') : '记忆已同步';
    }
    if (action.type === 'NEW_SESSION') { return '刚刚：已生成摘要记忆'; }
    if (action.type === 'RESTART') { return '刚刚：应用重新打开，记忆仍在'; }
    if (action.type === 'CUSTOM') { return '刚刚：客户补充了一句'; }
    if (action.type === 'RESET') { return '记忆会随对话自动更新'; }
    return '';
  }

  /* ---------------- 图记忆 ---------------- */

  function graphRecallIds() {
    var ids = {};
    if (state.graphRecall) {
      state.graphRecall.hits.forEach(function (hit) { ids[hit.id] = true; });
    }
    return ids;
  }

  function graphType(type) {
    return DATA.graphTypes[type] || { name: type, color: '#64748b' };
  }

  function graphLayout(entities) {
    var positions = {};
    if (!entities.length) { return positions; }
    var center = entities.filter(function (entity) { return entity.type === 'person'; })[0] || entities[0];
    positions[center.id] = { x: 240, y: 142, center: true };
    var priority = { product: 0, preference: 1, plan: 2, event: 3, company: 4, location: 5 };
    var others = entities.filter(function (entity) { return entity.id !== center.id; }).slice();
    others.sort(function (a, b) {
      var pa = priority[a.type] == null ? 9 : priority[a.type];
      var pb = priority[b.type] == null ? 9 : priority[b.type];
      return pa - pb;
    });
    if (others.length <= 8) {
      others.forEach(function (entity, index) {
        var angle = -Math.PI / 2 + index * (2 * Math.PI / Math.max(others.length, 1));
        var radius = 108;
        positions[entity.id] = {
          x: 240 + Math.cos(angle) * radius,
          y: 142 + Math.sin(angle) * radius,
          center: false
        };
      });
    } else {
      var innerCount = 6;
      var outerCount = others.length - innerCount;
      others.forEach(function (entity, index) {
        var inner = index < innerCount;
        var idx = inner ? index : index - innerCount;
        var count = inner ? innerCount : outerCount;
        var radius = inner ? 96 : 126;
        var offset = inner ? -Math.PI / 2 : -Math.PI / 2 + Math.PI / Math.max(count, 1);
        var angle = offset + idx * (2 * Math.PI / Math.max(count, 1));
        positions[entity.id] = {
          x: 240 + Math.cos(angle) * radius,
          y: 142 + Math.sin(angle) * radius,
          center: false
        };
      });
    }
    return positions;
  }

  function truncateLabel(label) {
    return label.length > 5 ? label.slice(0, 4) + '…' : label;
  }

  function renderGraphSvg() {
    var entities = state.graph.entities;
    var relations = state.graph.relations;
    var positions = graphLayout(entities);
    var recall = graphRecallIds();
    var edges = relations.map(function (relation) {
      var from = positions[relation.source];
      var to = positions[relation.target];
      if (!from || !to) { return ''; }
      var midX = (from.x + to.x) / 2;
      var midY = (from.y + to.y) / 2;
      return '<g class="g-edge' + (recall[relation.id] ? ' is-recalled' : '') + '">' +
        '<line x1="' + from.x + '" y1="' + from.y + '" x2="' + to.x + '" y2="' + to.y +
          '" marker-end="url(#graph-arrow)"></line>' +
        '<text class="g-edge-label" x="' + midX + '" y="' + (midY - 4) + '">' +
          esc(relation.label) + '</text>' +
        '</g>';
    }).join('');

    var nodes = entities.map(function (entity) {
      var position = positions[entity.id];
      if (!position) { return ''; }
      var type = graphType(entity.type);
      var selected = ui.graphSelected === entity.id;
      var label = truncateLabel(entity.label);
      var width = position.center ? 78 : Math.max(48, label.length * 12 + 16);
      var height = position.center ? 32 : 26;
      return '<g class="g-node' + (selected ? ' is-selected' : '') + (recall[entity.id] ? ' is-recalled' : '') +
        '" data-act="graph-node" data-id="' + esc(entity.id) + '" transform="translate(' +
        position.x + ',' + position.y + ')">' +
        '<rect class="g-node-bg" x="' + (-width / 2) + '" y="' + (-height / 2) + '" width="' + width +
          '" height="' + height + '" rx="9" fill="' + type.color + '"></rect>' +
        '<text class="g-node-text" x="0" y="4" text-anchor="middle">' + esc(label) + '</text>' +
        '<title>' + esc(entity.label) + ' · ' + esc(type.name) + '</title>' +
        '</g>';
    }).join('');

    return '<svg class="graph-svg" viewBox="0 0 480 284" role="img" aria-label="图记忆实体关系图">' +
      '<defs><marker id="graph-arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse">' +
        '<path d="M0 0 L10 5 L0 10 z" fill="#94a3b8"></path></marker></defs>' +
      edges + nodes + '</svg>';
  }

  function renderGraphLegend() {
    var used = {};
    state.graph.entities.forEach(function (entity) { used[entity.type] = true; });
    var items = Object.keys(used).map(function (type) {
      var meta = graphType(type);
      return '<span class="legend-item"><i style="background:' + meta.color + '"></i>' + esc(meta.name) + '</span>';
    }).join('');
    return '<div class="graph-legend">' + items + '<span class="legend-hint">点击实体查看关联关系</span></div>';
  }

  function renderGraphDetail(entity) {
    var type = graphType(entity.type);
    var relations = state.graph.relations.filter(function (relation) {
      return relation.source === entity.id || relation.target === entity.id;
    }).map(function (relation) {
      var outgoing = relation.source === entity.id;
      var otherId = outgoing ? relation.target : relation.source;
      var other = graphEntityById(otherId);
      return '<span class="relation-chip">' + (outgoing ? '→ ' : '← ') + esc(relation.label) + ' ' +
        esc(other ? other.label : otherId) + '</span>';
    });
    return '<div class="graph-detail">' +
      '<span class="detail-type" style="background:' + type.color + '1a;color:' + type.color + '">' + esc(type.name) + '</span>' +
      '<b>' + esc(entity.label) + '</b>' +
      (relations.length ? '<div class="relation-chips">' + relations.join('') + '</div>' : '') +
      '</div>';
  }

  function graphEntityById(id) {
    return state.graph.entities.filter(function (entity) { return entity.id === id; })[0];
  }

  function renderGraphRecallCard() {
    if (!state.graphRecall || !state.graphRecall.hits.length) { return ''; }
    var hits = state.graphRecall.hits.map(function (hit) {
      var typeName = hit.kind === 'relation' ? '关系' : graphType(hit.type).name;
      return '<span class="graph-hit"><span class="hit-kind">' + (hit.kind === 'relation' ? '关系' : '实体') + '</span>' +
        '<span class="hit-label">' + esc(hit.label) + '</span><span class="hit-type">' + esc(typeName) + '</span></span>';
    }).join('');
    return '<section class="graph-recall-card">' +
      '<div class="recall-head"><span class="recall-title">本轮图召回</span>' +
        '<span class="recall-count">' + state.graphRecall.hits.length + ' 个</span>' +
        '<span class="recall-sub">来自图记忆（进程内）</span></div>' +
      '<div class="graph-hits">' + hits + '</div>' +
      '</section>';
  }

  function renderEpisodesCard() {
    if (!state.graph.episodes.length) { return ''; }
    return '<section class="episodes-card">' +
      '<div class="mini-title">情节（Episodes）</div>' +
      state.graph.episodes.map(function (episode) {
        return '<div class="episode-row"><span class="episode-dot"></span>' + esc(episode.label) + '</div>';
      }).join('') +
      '</section>';
  }

  function renderGraphTab() {
    var content;
    if (!state.graph.entities.length) {
      content = '<div class="memory-empty"><div class="memory-empty-icon">◌</div>' +
        '<div class="memory-empty-title">还没有图记忆</div>' +
        '<div class="memory-empty-text">完成一轮咨询后，实体与关系会写入图存储，并在这里可视化。</div></div>';
    } else {
      content = renderGraphRecallCard() +
        '<section class="graph-card">' +
          '<div class="graph-card-head"><span>实体关系图</span><span class="graph-store"><i></i>图记忆 · InMemory（演示）</span></div>' +
          renderGraphSvg() +
          renderGraphLegend() +
          (ui.graphSelected && graphEntityById(ui.graphSelected)
            ? renderGraphDetail(graphEntityById(ui.graphSelected))
            : '') +
        '</section>' +
        renderEpisodesCard();
    }
    return '<div class="memory-tab-content">' +
      '<div class="memory-scroll graph-scroll">' + content + '</div>' +
      '<footer class="memory-foot">' +
        '<span class="activity">图存储：InMemoryGraphStore · 进程内（Milvus 待 0.1.17）</span>' +
        '<button class="link-btn" data-act="open-settings">模型设置</button>' +
      '</footer></div>';
  }

  /* ---------------- 记忆加工流程 ---------------- */

  function pipelineStageDetail(stageKey, run) {
    var stats = run.stats;
    if (stageKey === 'ingest') {
      return run.turnId === 'summary' ? '新对话归档 · 触发摘要加工' : '对话结束后触发 · ' + run.turnId;
    }
    if (stageKey === 'analyze') { return 'LLM 调用 ' + stats.llmCalls + ' 次'; }
    if (stageKey === 'extract') {
      return '长期记忆 +' + stats.memoriesAdded + ' · 图实体 +' + stats.graphEntitiesAdded +
        ' / 关系 +' + stats.graphRelationsAdded;
    }
    if (stageKey === 'merge') {
      return '更新 ' + stats.memoriesUpdated + ' · 删除 ' + stats.memoriesDeleted + ' · 合并冲突';
    }
    if (stageKey === 'write') { return (stats.writeTargets || []).join(' + '); }
    return run.durationMs + ' ms · 可被下一轮召回';
  }

  function renderPipelineStages(run) {
    return DATA.pipelineStages.map(function (stage, index) {
      return '<div class="pipeline-stage">' +
        '<span class="stage-index">' + (index + 1) + '</span>' +
        '<div class="stage-copy"><b>' + esc(stage.label) + '</b>' +
          '<span>' + esc(pipelineStageDetail(stage.key, run)) + '</span></div>' +
        '<span class="stage-check">✓</span>' +
        '</div>';
    }).join('');
  }

  function renderPipelineHistory(runs) {
    if (!runs.length) { return ''; }
    return '<section class="pipeline-history">' +
      '<div class="mini-title">加工记录</div>' +
      runs.slice(0, 6).map(function (run) {
        return '<div class="pipeline-run">' +
          '<span class="run-dot"></span>' +
          '<div class="run-main"><b>' + esc(run.trigger) + '</b>' +
            '<span>' + esc(run.at.slice(11)) + ' · 记忆+' + run.stats.memoriesAdded +
            ' · 图+' + (run.stats.graphEntitiesAdded + run.stats.graphRelationsAdded) + '</span></div>' +
          '<span class="run-duration">' + (run.durationMs / 1000).toFixed(1) + 's</span>' +
          '</div>';
      }).join('') +
      '</section>';
  }

  function renderModelSummary() {
    var config = state.modelConfig;
    return '<section class="model-summary">' +
      '<div class="mini-title">记忆模型服务</div>' +
      '<div class="model-row"><span>抽取 / 分析 LLM</span><b>' + esc(config.chatModel || '未配置') + '</b></div>' +
      '<div class="model-row"><span>向量化 Embedding</span><b>' + esc(config.embeddingModel || '未配置') + '</b></div>' +
      '<div class="model-row"><span>图存储</span><b>InMemoryGraphStore（演示）</b></div>' +
      '<button class="ghost-btn" data-act="open-settings">修改模型设置</button>' +
      '</section>';
  }

  function renderProcessingSample(run) {
    var sample = run.sample || {};
    var input = sample.input || [];
    var outputs = sample.outputs || { memories: [], graph: [] };
    if (!input.length && !outputs.memories.length && !outputs.graph.length) { return ''; }
    var context = input.map(function (line) {
      return '<div class="sample-context-line">' + esc(line) + '</div>';
    }).join('');
    var memoryChips = outputs.memories.map(function (text) {
      return '<span class="sample-chip memory">' + esc(text) + '</span>';
    }).join('');
    var graphChips = outputs.graph.map(function (text) {
      return '<span class="sample-chip graph">' + esc(text) + '</span>';
    }).join('');
    return '<section class="pipeline-sample">' +
      '<div class="mini-title">这次加工：会话上下文 → 长期记忆</div>' +
      '<div class="sample-label">输入 · 最近会话上下文（节选）</div>' +
      '<div class="sample-context">' + context + '</div>' +
      '<div class="sample-arrow">LLM 总结提炼 ↓</div>' +
      '<div class="sample-label">产出 · 长期记忆 / 图对象</div>' +
      '<div class="sample-chips">' +
        (memoryChips || '<span class="sample-empty">本轮无新增长期记忆</span>') + graphChips +
      '</div>' +
      '</section>';
  }

  function renderPipelineTab() {
    var runs = state.pipelineRuns.filter(function (run) {
      var stats = run.stats;
      return stats.llmCalls > 1 || stats.memoriesAdded > 0 || stats.memoriesUpdated > 0 ||
        stats.memoriesDeleted > 0 || stats.graphObjectsRemoved > 0 || stats.summaryOnly;
    });
    var last = runs.filter(function (run) { return !run.stats.summaryOnly; })[0] || runs[0];
    var stats = last ? last.stats : null;
    var hero;
    if (!last) {
      hero = '<div class="memory-empty"><div class="memory-empty-icon">◌</div>' +
        '<div class="memory-empty-title">还没有加工记录</div>' +
        '<div class="memory-empty-text">客户发来消息后，记忆加工流水线会在这里记录每一步。</div></div>';
    } else {
      hero = '<section class="pipeline-card">' +
        '<div class="pipeline-hero">' +
          '<div><span class="pipeline-eyebrow">最近一次记忆加工</span><b>' + esc(last.trigger) + '</b></div>' +
          '<span class="pipeline-time">' + esc(last.at.slice(11)) + ' · ' + (last.durationMs / 1000).toFixed(1) + 's</span>' +
        '</div>' +
        '<div class="pipeline-stats">' +
          '<span><b>' + stats.llmCalls + '</b>次 LLM</span>' +
          '<span><b>' + stats.memoriesAdded + '</b>条新记忆</span>' +
          '<span><b>' + (stats.graphEntitiesAdded + stats.graphRelationsAdded) + '</b>个图对象</span>' +
        '</div>' +
        '<div class="pipeline-stages">' + renderPipelineStages(last) + '</div>' +
        '</section>' +
        renderProcessingSample(last) +
        renderPipelineHistory(runs);
    }
    return '<div class="memory-tab-content">' +
      '<div class="memory-scroll pipeline-scroll">' + hero +
        '<div class="pipeline-note">' +
          'jiuwen 0.1.16 的 <code>addMessages</code> / <code>addMemory</code> 是同步调用；样例在对话结束后' +
          '由应用侧异步触发，因此呈现为一次「离线」加工流程。抽取使用真实 LLM，写入 SQLite、向量索引与 InMemory 图存储（Milvus 待 0.1.17）。' +
        '</div>' +
        renderModelSummary() +
      '</div>' +
      '<footer class="memory-foot">' +
        '<span class="activity">模型：' + esc(state.modelConfig.chatModel) + '</span>' +
        '<button class="link-btn" data-act="open-settings">模型设置</button>' +
      '</footer></div>';
  }

  /* ---------------- 模型设置 ---------------- */

  function renderField(id, label, value, type, placeholder, help) {
    return '<label class="form-field">' +
      '<span>' + esc(label) + '</span>' +
      '<input id="' + id + '" type="' + (type || 'text') + '" value="' + esc(value) + '"' +
        (placeholder ? ' placeholder="' + esc(placeholder) + '"' : '') + '>' +
      (help ? '<em>' + esc(help) + '</em>' : '') +
      '</label>';
  }

  function renderSettingsModal() {
    var config = state.modelConfig;
    return '<div class="modal-mask">' +
      '<div class="modal-card">' +
        '<div class="modal-head">' +
          '<div><b>记忆模型服务</b><span>用于记忆抽取、向量化与图记忆，对话回答的模型可另行配置</span></div>' +
          '<button class="icon-btn" data-act="close-settings" title="关闭">×</button>' +
        '</div>' +
        '<div class="modal-body">' +
          '<div class="form-grid">' +
            '<label class="form-field"><span>Provider</span><select id="model-provider">' +
              ['OpenAI 兼容', '华为云 MaaS', '本地 vLLM'].map(function (provider) {
                return '<option' + (provider === config.provider ? ' selected' : '') + '>' + provider + '</option>';
              }).join('') +
            '</select><em>记忆抽取与 GraphMemory 使用同一套配置</em></label>' +
            renderField('model-api-base', 'API Base URL', config.apiBase, 'text', 'https://api.deepseek.com') +
            renderField('model-api-key', 'API Key', config.apiKey, 'password', 'sk-...') +
            renderField('model-chat', '对话模型（记忆抽取）', config.chatModel, 'text', 'deepseek-chat') +
            renderField('model-embedding', 'Embedding 模型', config.embeddingModel, 'text', 'text-embedding-3-small') +
            renderField('model-reranker', 'Reranker 模型（可选）', config.rerankerModel, 'text', 'bge-reranker-v2-m3') +
            renderField('model-temperature', '抽取温度', String(config.temperature), 'number', '0.2') +
            renderField('model-milvus', 'Milvus 地址（0.1.17 后启用）', config.milvusUri, 'text', 'http://localhost:19530',
              '当前样例用 InMemoryGraphStore 演示；Compose 文件已准备，等 memory 0.1.17 发布后再切') +
          '</div>' +
          '<div class="modal-note">原型配置只保存在本次页面内，不会上传；真实样例从环境变量 / 本机配置文件读取，API Key 不写入代码库。</div>' +
        '</div>' +
        '<div class="modal-foot">' +
          '<span id="model-test-status" class="test-status"></span>' +
          '<button class="quiet-btn" data-act="close-settings">取消</button>' +
          '<button class="ghost-btn" data-act="test-model">测试连接</button>' +
          '<button class="primary-btn" data-act="save-settings">保存</button>' +
        '</div>' +
      '</div>' +
      '</div>';
  }

  function readField(id) {
    var element = document.getElementById(id);
    return element ? element.value.trim() : '';
  }

  function collectModelConfig() {
    var temperature = Number(readField('model-temperature'));
    return {
      provider: readField('model-provider'),
      apiBase: readField('model-api-base'),
      apiKey: readField('model-api-key'),
      chatModel: readField('model-chat'),
      embeddingModel: readField('model-embedding'),
      rerankerModel: readField('model-reranker'),
      temperature: isFinite(temperature) ? temperature : 0.2,
      milvusUri: readField('model-milvus')
    };
  }

  function saveModelSettings() {
    var config = collectModelConfig();
    ui.modalOpen = false;
    dispatch({ type: 'UPDATE_MODEL_CONFIG', config: config });
  }

  function testModelConnection() {
    var status = document.getElementById('model-test-status');
    if (!status) { return; }
    if (!readField('model-api-base') || !readField('model-chat')) {
      status.className = 'test-status is-error';
      status.textContent = '请先填写 API Base 和对话模型';
      return;
    }
    status.className = 'test-status';
    status.textContent = '测试中…';
    setTimeout(function () {
      var current = document.getElementById('model-test-status');
      if (current) {
        current.className = 'test-status is-ok';
        current.textContent = '连接成功（演示，不会发起真实请求）';
      }
    }, 550);
  }

  /* ---------------- 事件 ---------------- */

  app.addEventListener('click', function (event) {
    if (event.target.classList && event.target.classList.contains('modal-mask')) {
      ui.modalOpen = false;
      render();
      return;
    }
    var target = event.target.closest('[data-act]');
    if (!target || target.disabled) { return; }
    var action = target.getAttribute('data-act');
    if (action === 'mem-tab') {
      ui.memoryTab = target.getAttribute('data-tab');
      ui.graphSelected = null;
      render();
      return;
    }
    if (action === 'graph-node') {
      var nodeId = target.getAttribute('data-id');
      ui.graphSelected = ui.graphSelected === nodeId ? null : nodeId;
      render();
      return;
    }
    if (action === 'open-settings') { ui.modalOpen = true; render(); return; }
    if (action === 'close-settings') { ui.modalOpen = false; render(); return; }
    if (action === 'save-settings') { saveModelSettings(); return; }
    if (action === 'test-model') { testModelConnection(); return; }
    if (action === 'next-turn') { dispatch({ type: 'NEXT_TURN' }); return; }
    if (action === 'new-session') { dispatch({ type: 'NEW_SESSION' }); return; }
    if (action === 'select-session') {
      dispatch({ type: 'SELECT_SESSION', session: Number(target.getAttribute('data-session')) });
      return;
    }
    if (action === 'toggle-sidebar') { sidebarCollapsed = !sidebarCollapsed; render(); return; }
    if (action === 'restart') { dispatch({ type: 'RESTART' }); return; }
    if (action === 'reset') { draft = ''; dispatch({ type: 'RESET' }); return; }
    if (action === 'send') { sendCustom(); return; }
  });

  app.addEventListener('input', function (event) {
    if (event.target && event.target.id === 'composer-input') {
      draft = event.target.value;
      var send = app.querySelector('.send-btn');
      if (send) { send.disabled = !draft.trim(); }
    }
  });

  app.addEventListener('keydown', function (event) {
    if (event.key === 'Escape' && ui.modalOpen) {
      ui.modalOpen = false;
      render();
      return;
    }
    if (event.target && event.target.id === 'composer-input' && event.key === 'Enter') {
      event.preventDefault();
      sendCustom();
    }
  });

  /* 截图/演示快捷入口：?demo=first|recall|updated|deleted|reopened|reasked */
  var DEMOS = {
    first: [{ type: 'NEXT_TURN' }, { type: 'NEXT_TURN' }, { type: 'NEXT_TURN' }],
    recall: [{ type: 'NEXT_TURN' }, { type: 'NEXT_TURN' }, { type: 'NEXT_TURN' }, { type: 'NEW_SESSION' }, { type: 'NEXT_TURN' }],
    updated: [{ type: 'NEXT_TURN' }, { type: 'NEXT_TURN' }, { type: 'NEXT_TURN' }, { type: 'NEW_SESSION' }, { type: 'NEXT_TURN' }, { type: 'NEXT_TURN' }],
    deleted: [{ type: 'NEXT_TURN' }, { type: 'NEXT_TURN' }, { type: 'NEXT_TURN' }, { type: 'NEW_SESSION' }, { type: 'NEXT_TURN' }, { type: 'NEXT_TURN' }, { type: 'NEXT_TURN' }],
    reopened: [{ type: 'NEXT_TURN' }, { type: 'NEXT_TURN' }, { type: 'NEXT_TURN' }, { type: 'NEW_SESSION' }, { type: 'NEXT_TURN' }, { type: 'NEXT_TURN' }, { type: 'NEXT_TURN' }, { type: 'RESTART' }],
    reasked: [{ type: 'NEXT_TURN' }, { type: 'NEXT_TURN' }, { type: 'NEXT_TURN' }, { type: 'NEW_SESSION' }, { type: 'NEXT_TURN' }, { type: 'NEXT_TURN' }, { type: 'NEXT_TURN' }, { type: 'RESTART' }, { type: 'NEXT_TURN' }]
  };
  var query = new URLSearchParams(window.location.search);
  var demoName = query.get('demo');
  if (DEMOS[demoName]) {
    state = model.initialState();
    DEMOS[demoName].forEach(function (action) { state = model.applyAction(state, action); });
    pendingBottom = true;
  }
  if (query.get('history') === '1' && state.sessions.length > 1) {
    state = model.applyAction(state, { type: 'SELECT_SESSION', session: 1 });
    pendingBottom = true;
  }
  if (query.get('collapsed') === '1') { sidebarCollapsed = true; }
  var tabParam = query.get('tab');
  if (tabParam === 'graph' || tabParam === 'pipeline' || tabParam === 'longterm') {
    ui.memoryTab = tabParam;
  }
  if (query.get('settings') === '1') { ui.modalOpen = true; }

  render();
  window.__PROTO__ = {
    getState: function () { return state; },
    openNewSession: function () { dispatch({ type: 'NEW_SESSION' }); }
  };
})();
