/* PROTOTYPE · 页面渲染与交互（一次性产品原型，不是生产代码）
 *
 * 三栏结构：
 *   左：Agent Memory 服务状态 + 可收起的历史会话
 *   中：客户 ↔ 信贷智能体对话；每轮出「有记忆 · MemoryRail」和「无记忆 · 基线」两张回答卡
 *   右：记忆工作台（演进 / 召回 / 关联 / 服务）
 *
 * 数据来自 data.js 的 4 步演示脚本；模型状态由 model.js 按步骤重建。
 * 不连后端、不调模型、不伪造“真实运行输出”。
 */
(function () {
  'use strict';

  var DATA = window.ProtoData;
  var model = window.MemoryModel;
  var app = document.getElementById('app');

  var state = model.buildState(0);
  var draft = '';
  var pendingBottom = true;

  var ui = {
    tab: 'evolution',
    drawer: false,
    collapsed: false,
    compare: true,
    expandedSteps: {},
    expandedUnits: {},
    graphSelected: null,
    loadingStep: null,
    toast: null,
    focusUnit: null
  };

  /* ---------------- 工具 ---------------- */

  function esc(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function annotated(text) {
    return esc(text)
      .replace(/\{\{([^}]+)\}\}/g, '<mark class="mark-echo">$1</mark>')
      .replace(/\[\[([^\]]+)\]\]/g, '<mark class="mark-gap">$1</mark>');
  }

  function tierMeta(tier) { return DATA.tiers[tier] || DATA.tiers.semantic; }
  function lifeMeta(lifecycle) { return DATA.lifecycle[lifecycle] || DATA.lifecycle.active; }
  function opMeta(type) { return DATA.ops[type] || DATA.ops.extract; }
  function stepData(n) { return DATA.steps[n - 1]; }

  function fmtScore(score) {
    return typeof score === 'number' ? score.toFixed(2) : '';
  }

  function plural(count, unit) { return count + ' ' + unit; }

  function recallHitOfStep(stepNumber, unitId) {
    var step = stepData(stepNumber);
    if (!step || !step.recall) { return null; }
    return step.recall.hits.filter(function (hit) { return hit.unitId === unitId; })[0] || null;
  }

  /* ---------------- 渲染入口 ---------------- */

  function render() {
    var chatScroll = document.getElementById('chat-scroll');
    var chatTop = chatScroll ? chatScroll.scrollTop : 0;
    var wbBody = document.getElementById('wb-body');
    var wbTop = wbBody ? wbBody.scrollTop : 0;

    app.innerHTML = renderHeader() + renderBody() + (ui.drawer ? renderDrawer() : '') + renderToast();

    var nextChat = document.getElementById('chat-scroll');
    if (nextChat) {
      nextChat.scrollTop = pendingBottom ? nextChat.scrollHeight : chatTop;
    }
    var nextWb = document.getElementById('wb-body');
    if (nextWb) { nextWb.scrollTop = ui.resetWbScroll ? 0 : wbTop; }
    ui.resetWbScroll = false;
    pendingBottom = false;

    if (ui.focusUnit) {
      var target = document.querySelector('.unit-row[data-unit="' + ui.focusUnit + '"]');
      if (target) { target.scrollIntoView({ block: 'center' }); }
      ui.focusUnit = null;
    }
  }

  /* ---------------- 顶部 ---------------- */

  function renderHeader() {
    var stepLabel = state.step + '/4';
    return '<header class="app-header">' +
      '<div class="brand">' +
        '<div class="brand-mark">智</div>' +
        '<div class="brand-text">' +
          '<strong>信贷智能体</strong>' +
          '<span>Agent Memory 服务 · <b>MemoryRail 双回答对照</b></span>' +
        '</div>' +
      '</div>' +
      '<div class="header-right">' +
        '<span class="demo-pill">演示原型 · 虚构数据</span>' +
        '<span class="svc-chip"><span class="dot pulse"></span>Agent Memory · 运行中</span>' +
        '<button class="btn btn-ghost-brand" data-act="open-drawer">演示脚本' +
          '<span class="step-badge">' + stepLabel + '</span></button>' +
        '<button class="btn btn-quiet" data-act="reset">重新开始</button>' +
      '</div>' +
      '</header>';
  }

  function renderBody() {
    return '<div class="app-body">' + renderSidebar() +
      '<main class="workspace">' + renderChat() + renderWorkbench() + '</main>' +
      '</div>';
  }

  /* ---------------- 左侧 ---------------- */

  function renderSidebar() {
    return '<aside class="sidebar' + (ui.collapsed ? ' is-collapsed' : '') + '">' +
      renderServiceCard() + renderSessionsPanel() +
      '</aside>';
  }

  function renderServiceCard() {
    var stats = model.stats(state);
    if (ui.collapsed) {
      return '<button class="service-card is-rail" data-act="toggle-collapsed" title="展开 Agent Memory 服务卡">' +
        '<div class="svc-top"><div class="svc-orb">' + iconDatabase(17) +
          '<span class="rail-dot"></span></div></div>' +
        '<div class="svc-count">' + stats.totalUnits + '</div>' +
        '<div class="svc-label">记忆单元</div>' +
        '</button>';
    }
    return '<div class="service-card">' +
      '<div class="svc-top">' +
        '<div class="svc-orb">' + iconDatabase(17) + '</div>' +
        '<div class="svc-main">' +
          '<div class="svc-name"><b>Agent Memory</b>' +
            '<span class="svc-status"><span class="dot"></span>运行中</span></div>' +
          '<div class="svc-ver">服务版本 <code>' + esc(DATA.service.version) + '</code> · 本地部署</div>' +
        '</div>' +
      '</div>' +
      '<div class="svc-stats">' +
        '<div class="svc-stat is-brand" title="包含有效 / 已取代 / 已遗忘的历史记忆单元">' +
          '<b>' + stats.totalUnits + '</b><span>记忆单元</span></div>' +
        '<div class="svc-stat"><b>' + stats.entities + '</b><span>图关联</span></div>' +
        '<div class="svc-stat"><b>' + stats.injected + '</b><span>本轮召回</span></div>' +
      '</div>' +
      '<button class="portal-btn" data-act="open-portal">打开记忆管理 <span class="arrow">↗</span></button>' +
      '<div class="svc-storage"><i></i>SQLite 真源 · 向量 / 全文 / 图索引进程内</div>' +
      '</div>';
  }

  function sessionStats(sessionId) {
    var messages = model.messagesForSession(state, sessionId);
    var customers = messages.filter(function (message) { return message.role === 'customer'; });
    var preview = customers.length ? customers[customers.length - 1].text : '还没有消息，点「发送并演示」开始';
    var units = state.units.filter(function (unit) {
      return unit.step > 0 && (unit.session || '').indexOf('-' + ('0' + sessionId).slice(-2)) >= 0;
    }).length;
    return {
      messages: messages.filter(function (message) { return message.role !== 'system'; }).length,
      preview: preview,
      units: units
    };
  }

  function renderSessionsPanel() {
    var items = state.sessions.slice().reverse().map(function (session) {
      var active = state.currentSession === session.id;
      var info = sessionStats(session.id);
      var delta = info.units ? '+' + info.units + ' 条' : '';
      if (ui.collapsed) {
        return '<button class="session-item' + (active ? ' is-active' : '') + '"' +
          ' data-act="select-session" data-session="' + session.id + '"' +
          ' title="' + esc(session.title) + '">' +
          '<span class="session-avatar">' + esc(session.title.slice(0, 1)) + '</span>' +
          '</button>';
      }
      return '<button class="session-item' + (active ? ' is-active' : '') + '"' +
        ' data-act="select-session" data-session="' + session.id + '"' +
        ' title="' + esc(session.title + ' · 今天 ' + session.time) + '">' +
        '<span class="session-avatar">' + esc(session.title.slice(0, 1)) + '</span>' +
        '<span class="session-copy">' +
          '<b title="' + esc(session.title) + '">' + esc(session.title) + '</b>' +
          '<span class="meta">今天 ' + esc(session.time) + ' · ' + info.messages + ' 条消息</span>' +
          '<em>' + esc(info.preview) + '</em>' +
        '</span>' +
        (delta ? '<span class="session-delta" title="这段会话期间沉淀的记忆单元">' + esc(delta) + '</span>' : '') +
        '</button>';
    }).join('');

    var canOpenNew = model.canOpenNewSession(state);
    var newTitle = canOpenNew ? '开启新对话（演示第 2 步）' :
      state.step === 0 ? '完成第 1 步后可开启新对话' : '演示已进入新会话';

    return '<div class="sessions-panel">' +
      '<div class="sessions-head">' +
        (ui.collapsed ? '' : '<span class="sessions-title">历史会话<span>' + state.sessions.length + '</span></span>') +
        '<button class="icon-btn is-new" data-act="new-session" title="' + esc(newTitle) + '"' +
          (canOpenNew ? '' : ' disabled') + '>＋</button>' +
        '<button class="icon-btn" data-act="toggle-collapsed" title="' +
          (ui.collapsed ? '展开左侧' : '收起历史会话') + '">' + (ui.collapsed ? '›' : '‹') + '</button>' +
      '</div>' +
      '<div class="sessions-list">' + items + '</div>' +
      (ui.collapsed ? '' : '<div class="sessions-foot">记忆跨会话保留；点会话可回看<br>右栏为本轮记忆工作台（演示数据）</div>') +
      '</div>';
  }

  function iconDatabase(size) {
    return '<svg width="' + size + '" height="' + size + '" viewBox="0 0 24 24" fill="none" ' +
      'stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">' +
      '<ellipse cx="12" cy="5" rx="7" ry="3"></ellipse>' +
      '<path d="M5 5v6c0 1.7 3.1 3 7 3s7-1.3 7-3V5"></path>' +
      '<path d="M5 11v6c0 1.7 3.1 3 7 3s7-1.3 7-3v-6"></path>' +
      '</svg>';
  }

  /* ---------------- 中间：对话 ---------------- */

  function renderChat() {
    var history = model.viewingHistory(state);
    var sessionTitle = (state.sessions.filter(function (session) {
      return session.id === state.currentSession;
    })[0] || {}).title || '对话';
    var header = '<header class="chat-head">' +
      '<div class="avatar-customer">' + esc(DATA.customer.avatar) + '</div>' +
      '<div class="chat-person">' +
        '<b>' + esc(DATA.customer.name) + '</b>' +
        '<span>客户 · ' + esc(DATA.customer.profile) + '</span>' +
      '</div>' +
      '<div class="chat-head-right">' +
        '<span class="session-pill">' + esc(sessionTitle) + '</span>' +
        (history ? '<span class="history-pill">历史回看</span>' : '') +
        '<span class="compare-toggle">双回答对照' +
          '<button class="switch' + (ui.compare ? ' is-on' : '') + '" data-act="toggle-compare" ' +
            'title="' + (ui.compare ? '隐藏无记忆基线' : '显示无记忆基线') + '" aria-label="切换双回答对照"></button>' +
        '</span>' +
      '</div>' +
      '</header>';

    var messages = model.messagesForSession(state, state.currentSession);
    var body = messages.map(renderMessage).join('');
    if (ui.loadingStep && !history) { body += renderPendingTurn(); }

    var tail = '';
    if (history) {
      tail = renderHistoryNote();
    } else if (ui.loadingStep) {
      tail = renderLoadingStrip();
    } else {
      tail = renderStepStrip();
    }

    return '<section class="panel chat-panel">' + header +
      '<div class="chat-scroll" id="chat-scroll">' + body + tail + '</div>' +
      (history ? '' : renderComposer()) +
      '</section>';
  }

  function renderMessage(message) {
    if (message.role === 'system') {
      return '<div class="chat-system">' + esc(message.text) + '</div>';
    }
    if (message.role === 'customer') {
      return '<div class="msg-customer">' +
        '<div class="bubble-customer">' + esc(message.text) + '</div>' +
        '<div class="msg-meta">' + esc(DATA.customer.name) + ' · ' + esc(message.ts) + '</div>' +
        '</div>';
    }
    if (message.kind === 'dual') {
      return renderTurn(message);
    }
    return '<div class="turn-plain">' +
      '<div class="turn-head"><span class="agent-mark">智</span><b>信贷智能体</b>' +
        '<span class="turn-time">' + esc(message.ts) + '</span></div>' +
      '<div class="plain-bubble">' + esc(message.text) + '</div>' +
      '</div>';
  }

  function renderTurn(message) {
    var answers = message.answers;
    var html = '<div class="turn">' +
      '<div class="turn-head"><span class="agent-mark">智</span><b>信贷智能体</b>' +
        '<span class="turn-label">双回答对照</span>' +
        '<span class="turn-time">第 ' + message.step + ' 步 · ' + esc(message.stepTitle) + '</span></div>';
    if (ui.compare) {
      html += '<div class="dual-grid">' + renderMemoryCard(message) + renderBaselineCard(message) + '</div>';
    } else {
      html += '<div class="dual-grid is-single">' + renderMemoryCard(message) + '</div>' +
        '<div class="compare-off-note">无记忆基线已隐藏 · 打开右上角「双回答对照」可恢复</div>';
    }
    html += renderDiffStrip(message);
    html += '</div>';
    return html;
  }

  function renderMemoryCard(message) {
    var answers = message.answers;
    var citations = (answers.cited || []).map(function (unitId) {
      var unit = model.unitById(state, unitId);
      var hit = recallHitOfStep(message.step, unitId);
      var score = hit ? hit.score : model.recallScore(state, unitId);
      return '<button class="cite-chip" data-act="cite" data-unit="' + esc(unitId) + '"' +
        ' data-step="' + message.step + '"' +
        ' title="查看记忆单元 ' + esc(unitId) + ' 的演进与血缘">' +
        '<span>' + esc(unitId) + '</span>' +
        '<span class="score">' + fmtScore(score) + '</span>' +
        '<span class="tier">' + esc(unit ? tierMeta(unit.tier).label : '') + '</span>' +
        '</button>';
    }).join('');

    var emptyBlock = '';
    if (!answers.injectedCount) {
      emptyBlock = '<div class="empty-recall">本轮没有可召回记忆：Agent Memory 里还没有"已建立索引"的记忆。' +
        '回答与基线一致；对话结束后完成首次抽取。</div>';
    }

    var foot;
    if (!answers.injectedCount) {
      foot = '首轮无差异 · 本轮对话结束后完成抽取 / 合并 / 关联';
    } else if (answers.missing && answers.missing.length) {
      foot = '比基线多 ' + answers.missing.length + ' 处客户事实';
    } else {
      foot = '回答基于 ' + answers.injectedCount + ' 条召回记忆';
    }

    return '<article class="answer-card is-memory">' +
      '<div class="ac-head">' +
        '<span class="ac-dot"></span>' +
        '<span class="ac-title">有记忆 · MemoryRail</span>' +
        '<span class="ac-sub">注入 ' + answers.injectedCount + ' 条</span>' +
        '<span class="ac-chip">' + (answers.injectedCount ? '已注入' : '无召回') + '</span>' +
      '</div>' +
      emptyBlock +
      '<div class="answer-text">' + annotated(answers.memory) + '</div>' +
      (citations ? '<div class="cite-row">' + citations + '</div>' : '') +
      '<div class="card-foot"><i></i>' + esc(foot) + '</div>' +
      '</article>';
  }

  function renderBaselineCard(message) {
    var answers = message.answers;
    var gaps = (answers.missing || []).map(function (item) {
      return '<span class="gap-chip">缺失 · ' + esc(item) + '</span>';
    }).join('');
    var foot = (answers.missing && answers.missing.length)
      ? '需要客户再补充 ' + answers.missing.length + ' 项信息'
      : '与「有记忆」回答一致（首轮无可召回记忆）';
    return '<article class="answer-card is-baseline">' +
      '<div class="ac-head">' +
        '<span class="ac-dot"></span>' +
        '<span class="ac-title">无记忆 · 基线</span>' +
        '<span class="ac-sub">未注入记忆</span>' +
        '<span class="ac-chip">基线</span>' +
      '</div>' +
      '<div class="answer-text">' + annotated(answers.baseline) + '</div>' +
      (gaps ? '<div class="gap-row">' + gaps + '</div>' : '') +
      '<div class="card-foot"><i></i>' + esc(foot) + '</div>' +
      '</article>';
  }

  function renderDiffStrip(message) {
    var answers = message.answers;
    if (!answers.diff || !answers.diff.length) {
      return '<div class="diff-strip">' +
        '<div class="diff-row"><span class="diff-label">差异 0 处</span>' +
          '<span class="diff-empty">' + esc(answers.verdict) + '</span></div>' +
        '</div>';
    }
    var chips = answers.diff.map(function (item) {
      return '<span class="diff-chip"><b>' + esc(item.aspect) + '</b>' +
        esc(item.memory) + '<span class="vs">vs</span>' + esc(item.baseline) + '</span>';
    }).join('');
    return '<div class="diff-strip">' +
      '<div class="diff-row"><span class="diff-label">差异 ' + answers.diff.length + ' 处</span>' + chips + '</div>' +
      '<div class="diff-verdict">' + esc(answers.verdict) + '</div>' +
      '</div>';
  }

  function renderPendingTurn() {
    var step = stepData(ui.loadingStep);
    if (!step) { return ''; }
    return '<div class="pending-turn">' +
      '<div class="msg-customer"><div class="bubble-customer">' + esc(step.customerText) + '</div>' +
        '<div class="msg-meta">' + esc(DATA.customer.name) + ' · ' + esc(step.at) + '</div></div>' +
      '<div class="turn">' +
        '<div class="turn-head"><span class="agent-mark">智</span><b>信贷智能体</b>' +
          '<span class="turn-label">双回答生成中</span><span class="turn-time">' + esc(step.at) + '</span></div>' +
        '<div class="dual-grid">' +
          '<article class="answer-card is-memory sk-card">' +
            '<div class="sk-head"><span class="ac-dot"></span>' +
              '<span class="ac-title">有记忆 · MemoryRail</span><span class="ac-chip">检索中</span></div>' +
            '<div class="sk-body">' +
              '<div class="sk-loading-label"><span class="spinner"></span>Agent Memory 召回中：关键词 / 向量 / 图</div>' +
              '<div class="sk-line w90"></div><div class="sk-line w75"></div><div class="sk-line w60"></div>' +
            '</div>' +
          '</article>' +
          '<article class="answer-card is-baseline sk-card">' +
            '<div class="sk-head"><span class="ac-dot"></span>' +
              '<span class="ac-title">无记忆 · 基线</span><span class="ac-chip">生成中</span></div>' +
            '<div class="sk-body">' +
              '<div class="sk-loading-label"><span class="spinner"></span>同一对话上下文 · 未注入记忆</div>' +
              '<div class="sk-line w90"></div><div class="sk-line w75"></div><div class="sk-line w60"></div>' +
            '</div>' +
          '</article>' +
        '</div>' +
      '</div>' +
      '</div>';
  }

  function renderStepStrip() {
    var next = model.nextStep(state);
    var progress = '<div class="step-progress"><b>演示 ' + state.step + ' / 4</b>' +
      '<span>' + (next ? '脚本模式 · 点右侧按钮推进' : '演示完成') + '</span></div>';
    if (next) {
      var preview = next.customerText.length > 42 ? next.customerText.slice(0, 42) + '…' : next.customerText;
      return '<div class="step-strip">' + progress +
        '<div class="step-next">下一步：客户说 <b>「' + esc(preview) + '」</b></div>' +
        '<button class="btn btn-primary" data-act="next-step">发送并演示</button>' +
        '<button class="btn" data-act="open-drawer">演示脚本</button>' +
        '</div>';
    }
    return '<div class="step-strip">' + progress +
      '<div class="step-next">4 步演示已完成：抽取、合并、关联、更新、演进、遗忘都已经在右栏可查。</div>' +
      '<button class="btn" data-act="reset">重新开始</button>' +
      '<button class="btn" data-act="open-drawer">演示脚本</button>' +
      '</div>';
  }

  function renderLoadingStrip() {
    return '<div class="step-strip">' +
      '<div class="step-progress"><b>正在处理第 ' + ui.loadingStep + ' 步</b><span>检索记忆 → 注入 → 生成双回答</span></div>' +
      '<div class="step-next">真实样例中，这一步会调用 Agent Memory 的 <b>/v1/search</b> 召回，' +
        '再分别生成两个回答；原型用 0.9 秒模拟等待。</div>' +
      '</div>';
  }

  function renderHistoryNote() {
    return '<div class="history-note">正在回看历史会话 · 右栏展示的是当前记忆库（' +
      state.units.filter(function (unit) { return unit.lifecycle === 'active'; }).length + ' 条有效记忆）。' +
      '历史消息只读。' +
      '<button class="btn" data-act="select-session" data-session="' + model.latestSessionId(state) + '">' +
        '回到当前会话</button></div>';
  }

  function renderComposer() {
    return '<div class="composer">' +
      '<input class="composer-input" id="composer-input" type="text" maxlength="200" ' +
        'placeholder="以客户身份输入…（演示原型只演示预设脚本）" value="' + esc(draft) + '">' +
      '<button class="send-btn" data-act="send"' + (draft.trim() ? '' : ' disabled') + '>发送</button>' +
      '</div>';
  }

  /* ---------------- 右侧：记忆工作台 ---------------- */

  function renderWorkbench() {
    var tabs = [
      { id: 'evolution', label: '演进', count: state.ops.length },
      { id: 'recall', label: '召回', count: state.recall ? state.recall.hits.length : 0 },
      { id: 'graph', label: '关联', count: model.stats(state).entities },
      { id: 'service', label: '服务', count: null }
    ].map(function (tab) {
      return '<button class="wb-tab' + (ui.tab === tab.id ? ' is-active' : '') + '"' +
        ' data-act="tab" data-tab="' + tab.id + '">' + esc(tab.label) +
        (tab.count != null ? '<b>' + tab.count + '</b>' : '') + '</button>';
    }).join('');

    var body = ui.tab === 'recall' ? renderRecallTab()
      : ui.tab === 'graph' ? renderGraphTab()
      : ui.tab === 'service' ? renderServiceTab()
      : renderEvolutionTab();

    return '<aside class="panel workbench">' +
      '<div class="wb-head">' +
        '<div class="wb-title"><b>记忆工作台</b><span>Agent Memory · 本轮加工与召回</span></div>' +
        '<span class="wb-live"><span class="dot"></span>演示数据</span>' +
      '</div>' +
      '<div class="wb-tabs">' + tabs + '</div>' +
      '<div class="wb-body" id="wb-body">' + body + '</div>' +
      renderWbFoot() +
      '</aside>';
  }

  function renderWbFoot() {
    var activity = '记忆服务就绪';
    if (state.ops.length) {
      var last = state.ops[state.ops.length - 1];
      activity = '最近加工 ' + last.at + ' · ' + opMeta(last.type).label + ' · ' +
        (state.units.length) + ' 条记忆单元';
    }
    return '<div class="wb-foot">' +
      '<span class="activity">' + esc(activity) + ' · 演示数据</span>' +
      '<button data-act="open-portal">打开记忆管理</button>' +
      '</div>';
  }

  /* ---- tab 1：记忆演进 ---- */

  function renderEvolutionTab() {
    if (!state.units.length) {
      return '<div class="empty-state"><div class="empty-icon">◌</div>' +
        '<b>还没有记忆</b><span>第 1 步对话结束后，Agent Memory 会抽取记忆单元，' +
        '在这里按「抽取 → 合并 → 关联 → 更新 → 演进」展示全过程。</span></div>';
    }

    var counts = model.opCounts(state);
    var life = model.lifecycleCounts(state);
    var opChips = ['extract', 'merge', 'update', 'associate', 'evolve', 'forget'].map(function (type) {
      return '<span class="op-count op-' + type + '"><b>' + counts[type] + '</b>' + opMeta(type).label + '</span>';
    }).join('');

    var groups = model.opsByStep(state).map(function (group) {
      var open = ui.expandedSteps[group.step] == null ? group.step === state.step : !!ui.expandedSteps[group.step];
      var isLatest = group.step === state.step;
      var ops = group.ops.map(renderOpCard).join('');
      return '<div class="step-group' + (open ? ' is-open' : '') + (isLatest ? ' is-latest' : '') + '">' +
        '<button class="step-group-head" data-act="toggle-step" data-step="' + group.step + '">' +
          '<span class="sg-num">' + group.step + '</span>' +
          '<b>第 ' + group.step + ' 步 · ' + esc(group.title.split(' · ')[0]) + '</b>' +
          '<span class="sg-meta">' + group.ops.length + ' 个操作 · ' + esc(group.at) + '</span>' +
          '<span class="sg-caret">' + (open ? '▾' : '▸') + '</span>' +
        '</button>' +
        (open ? '<div class="step-group-body">' + ops + '</div>' : '') +
        '</div>';
    }).join('');

    return '<div class="wb-summary">' +
      '<div class="wb-summary-head"><b>今日记忆演进</b>' +
        '<span>' + state.ops.length + ' 个操作 · ' + DATA.steps.length + ' 轮</span></div>' +
      '<div class="op-count-row">' + opChips + '</div>' +
      '<div class="lib-dist"><span>有效 <b>' + life.active + '</b></span><span class="sep">·</span>' +
        '<span>已取代 <b>' + life.superseded + '</b></span><span class="sep">·</span>' +
        '<span>已遗忘 <b>' + life.forgotten + '</b></span></div>' +
      '</div>' +
      '<div class="timeline">' + groups + '</div>' +
      '<div class="assoc-callout"><b>怎么看：</b>每条记忆都能追到「从哪来（provenance）、怎么变' +
        '（version / supersedes）、现在是否有效（lifecycle）」。点记忆单元可展开原始字段。</div>';
  }

  function renderOpCard(op) {
    var meta = opMeta(op.type);
    var apiLabel = {
      extract: 'add(infer=true)',
      merge: 'associator · dedup',
      update: 'update(SUPERSEDE)',
      associate: 'graph index',
      evolve: 'evolve · abstractor',
      forget: 'delete(mode=forget)'
    }[op.type] || '';

    var body = '';
    if (op.type === 'extract') {
      body = '<div class="unit-list">' + (op.unitIds || []).map(function (unitId) {
        return renderUnitRow(unitId);
      }).join('') + '</div>';
    } else if (op.type === 'merge') {
      var srcs = (op.fromLabels || []).map(function (label) {
        return '<div class="merge-src"><b>片段</b>' + esc(label) + '</div>';
      }).join('');
      body = '<div class="merge-flow">' + srcs +
        '<div class="flow-arrow">associator 判定同一偏好 · 去重合并</div>' +
        '<div class="unit-list">' + renderUnitRow(op.unitId) + '</div></div>';
    } else if (op.type === 'update') {
      body = '<div class="supersede-chain">' +
        renderUnitRow(op.oldUnitId) +
        '<div class="flow-arrow">新版本 supersedes 旧版本（旧版非破坏式保留）</div>' +
        renderUnitRow(op.unitId) +
        '</div>' + (op.detail ? '<div class="op-detail">' + esc(op.detail) + '</div>' : '');
    } else if (op.type === 'associate') {
      var edges = (op.edges || []).map(function (edge) {
        return '<span class="edge-chip' + (edge.indexOf('移除') === 0 ? ' is-removed' : '') + '">' +
          esc(edge) + '</span>';
      }).join('');
      body = (edges ? '<div class="edge-chips">' + edges + '</div>' : '') +
        (op.unitIds && op.unitIds.length
          ? '<div class="unit-list">' + op.unitIds.map(function (unitId) { return renderUnitRow(unitId); }).join('') + '</div>'
          : '');
    } else if (op.type === 'evolve') {
      var prov = (op.provenance || []).map(function (unitId) {
        var provUnit = model.unitById(state, unitId);
        var cls = provUnit && provUnit.lifecycle === 'forgotten' ? ' is-forgotten'
          : provUnit && provUnit.lifecycle === 'superseded' ? ' is-superseded' : ' is-evolve';
        return '<span class="prov-chip' + cls + '">' + esc(unitId) +
          (provUnit && provUnit.lifecycle === 'forgotten' ? ' · 已遗忘' : '') + '</span>';
      }).join('');
      body = (prov ? '<div class="provenance-chips">' + prov + '</div>' : '') +
        (op.oldUnitId
          ? '<div class="supersede-chain">' + renderUnitRow(op.oldUnitId) +
            '<div class="flow-arrow">遗忘后重新演进（v2 supersedes v1）</div>' + renderUnitRow(op.unitId) + '</div>'
          : '<div class="merge-flow"><div class="flow-arrow">abstractor / evolver 抽象升华</div>' +
            '<div class="unit-list">' + renderUnitRow(op.unitId) + '</div></div>');
    } else if (op.type === 'forget') {
      body = '<div class="unit-list">' + renderUnitRow(op.unitId) + '</div>';
    }

    return '<div class="op-card op-' + op.type + '">' +
      '<div class="op-head">' +
        '<span class="op-badge op-' + op.type + '">' + esc(meta.label) + '</span>' +
        '<span class="op-at">' + esc(op.at) + '</span>' +
        '<span class="op-api">' + esc(apiLabel) + '</span>' +
      '</div>' +
      '<div class="op-title">' + esc(op.title) + '</div>' +
      (op.type === 'extract' || op.type === 'associate' ? esc(op.detail) ? '<div class="op-detail">' + esc(op.detail) + '</div>' : '' : '') +
      body +
      (op.type === 'associate' && op.detail ? '<div class="op-detail">' + esc(op.detail) + '</div>' : '') +
      (op.type === 'forget' && op.detail ? '<div class="op-detail">' + esc(op.detail) + '</div>' : '') +
      (op.type === 'evolve' && op.detail ? '<div class="op-detail">' + esc(op.detail) + '</div>' : '') +
      '</div>';
  }

  function renderUnitRow(unitId) {
    var unit = model.unitById(state, unitId);
    if (!unit) {
      return '<div class="unit-row"><span class="unit-id">' + esc(unitId) + '</span>' +
        '<span class="unit-text">（记忆单元不存在）</span></div>';
    }
    var expanded = !!ui.expandedUnits[unitId];
    var score = unit.lifecycle === 'forgotten' ? null : model.recallScore(state, unitId);
    var lifeClass = unit.lifecycle === 'superseded' ? ' is-superseded'
      : unit.lifecycle === 'forgotten' ? ' is-forgotten' : '';
    var row = '<button class="unit-row' + lifeClass + (expanded ? ' is-expanded' : '') + '"' +
      ' data-act="unit" data-unit="' + esc(unitId) + '" title="' + esc(unit.content) + '">' +
      '<span class="unit-id">' + esc(unitId) + '</span>' +
      '<span class="tier-chip tier-' + esc(unit.tier) + '">' + esc(unit.tier) + '</span>' +
      '<span class="life-chip life-' + esc(unit.lifecycle) + '">' + esc(unit.lifecycle) + '</span>' +
      '<span class="unit-text">' + esc(unit.content) + '</span>' +
      (score != null ? '<span class="unit-score">' + fmtScore(score) + '</span>' : '') +
      '</button>';
    return row + (expanded ? renderUnitDetail(unit) : '');
  }

  function renderUnitDetail(unit) {
    var prov = (unit.provenance || []).map(function (item) {
      var kindClass = item.kind === 'merge' ? ' is-merge'
        : item.kind === 'evolve' ? ' is-evolve'
        : item.kind === 'forgotten' ? ' is-forgotten' : '';
      return '<span class="prov-chip' + kindClass + '">' + esc(item.label) + '</span>';
    }).join('');
    var fields = [
      { k: 'tier', v: unit.tier + ' · ' + tierMeta(unit.tier).zh },
      { k: 'lifecycle', v: unit.lifecycle },
      { k: 'version', v: 'v' + unit.version + (unit.supersedes ? ' · supersedes ' + unit.supersedes : '') },
      { k: 'source', v: unit.source },
      { k: 't_ingest', v: unit.tIngest },
      { k: 't_event', v: unit.tEvent || '—' }
    ].map(function (field) {
      return '<div class="uv"><span>' + esc(field.k) + '</span><b>' + esc(field.v) + '</b></div>';
    }).join('');

    return '<div class="unit-expanded">' + fields +
      '<div class="uv wide"><span>provenance · 从哪来</span><div class="prov-chips">' +
        (prov || '<span class="prov-chip">—</span>') + '</div></div>' +
      '<div class="uv wide"><span>tags</span><b>' + esc((unit.tags || []).map(function (tag) { return '#' + tag; }).join(' ')) + '</b></div>' +
      '<div class="uv wide"><span>scope</span><b>org=local · user=C1001 · agent=credit-agent · session=' +
        esc(unit.session || '—') + '</b></div>' +
      '<div class="uv-actions">' +
        '<button data-act="goto-recall" data-unit="' + esc(unit.id) + '">在召回轨迹里看</button>' +
        '<button data-act="open-portal">在 Portal 查看</button>' +
      '</div>' +
      '</div>';
  }

  /* ---- tab 2：召回轨迹 ---- */

  function renderRecallTab() {
    if (!state.recall) {
      return '<div class="empty-state"><div class="empty-icon">◎</div>' +
        '<b>还没有召回轨迹</b><span>首轮对话结束后，记忆才会建立索引；从第 2 步开始，' +
        '每轮都会先检索 Agent Memory，再给出双回答。</span></div>';
    }
    var recall = state.recall;
    var hits = model.recallHits(state);

    var channelCards = recall.channels.map(function (channel) {
      return '<div class="channel-card ch-' + channel.key + '">' +
        '<b>' + channel.count + '</b><span>' + esc(channel.label) + '</span></div>';
    }).join('');

    var keywords = (recall.keywords || []).map(function (keyword) {
      return '<span class="kw-chip">' + esc(keyword) + '</span>';
    }).join('');

    var hitRows = hits.map(function (hit, index) {
      var tier = tierMeta(hit.tier);
      var life = lifeMeta(hit.lifecycle);
      var channelTags = hit.channels.map(function (channel) {
        var label = channel === 'keyword' ? '关键词' : channel === 'vector' ? '向量' : '图';
        return '<span class="ch-tag">' + label + '</span>';
      }).join('');
      return '<div class="hit-row ' + (hit.injected ? 'is-injected' : 'is-out') + '">' +
        '<span class="hit-rank">' + (index + 1) + '</span>' +
        '<div class="hit-main">' +
          '<div class="hit-top"><span class="hit-id">' + esc(hit.unitId) + '</span>' +
            '<span class="tier-chip tier-' + esc(hit.tier) + '">' + esc(tier.label) + '</span>' +
            '<span class="life-chip life-' + esc(hit.lifecycle) + '">' + esc(life.label) + '</span>' +
            '<span class="hit-score">' + fmtScore(hit.score) + '</span></div>' +
          '<div class="hit-content">' + esc(hit.content) + '</div>' +
          '<div class="score-bar"><i style="width:' + Math.round(hit.score * 100) + '%"></i></div>' +
          '<div class="hit-bottom">' + channelTags +
            (hit.injected
              ? '<span class="inject-flag">已注入 MemoryRail</span>'
              : '<span class="inject-flag is-out">未注入 · ' + esc(hit.reason || '') + '</span>') +
          '</div>' +
        '</div>' +
        '</div>';
    }).join('');

    var graphHits = (recall.graphHits || []).map(function (hit) {
      var type = DATA.graphTypes[hit.type] || { name: hit.type, color: '#64748b' };
      return '<span class="graph-hit-chip" style="color:' + type.color + ';border-color:' + type.color +
        '33;background:' + type.color + '0d">' + esc(type.name) + ' · ' + esc(hit.label) +
        ' <b>' + fmtScore(hit.score) + '</b></span>';
    }).join('');

    return '<div class="recall-card">' +
      '<div class="mini-label">本轮查询 · 客户原话</div>' +
      '<div class="recall-query">「' + esc(recall.query) + '」</div>' +
      '<div class="recall-rewrite"><span class="rewrite-tag">改写</span>' +
        '<div>' + esc(recall.rewritten) + '</div></div>' +
      '<div class="kw-chips">' + keywords + '</div>' +
      '</div>' +
      '<div class="channel-grid">' + channelCards + '</div>' +
      '<div class="recall-settings">' +
        '<span>top_k <b>' + recall.topK + '</b></span>' +
        '<span>相关性阈值 <b>' + recall.threshold + '</b></span>' +
        '<span>融合 <b>RRF</b></span>' +
        '<span>耗时 <b>' + recall.latencyMs + ' ms</b></span>' +
        '<span>轨迹 <b>with_trajectory=true</b></span>' +
      '</div>' +
      '<div class="hit-list">' + hitRows + '</div>' +
      (graphHits ? '<div class="graph-hits-card"><div class="mini-title">图通道命中（实体 / 关系）</div>' +
        '<div class="graph-hits">' + graphHits + '</div></div>' : '') +
      '<div class="inject-note"><b>注入方式：</b>MemoryRail 在 <code>beforeModelCall</code> 之前调用 ' +
        'Agent Memory <code>/v1/search</code>，把 top-' + recall.topK + ' 记忆组装成 ' +
        '<code>&lt;memory-context&gt;</code> 注入对话模型；基线不挂 MemoryRail，所以拿不到这些记忆。</div>';
  }

  /* ---- tab 3：关联图 ---- */

  var GRAPH_POS = {
    e_zhou: { x: 200, y: 174, center: true },
    e_loan: { x: 200, y: 48 },
    e_company: { x: 322, y: 92 },
    e_hz: { x: 70, y: 96 },
    e_equal: { x: 348, y: 176 },
    e_monthly: { x: 306, y: 270 },
    e_plan: { x: 86, y: 268 },
    e_overdue: { x: 200, y: 302 },
    e_change: { x: 96, y: 176 }
  };

  function renderGraphTab() {
    if (!state.graph.entities.length) {
      return '<div class="empty-state"><div class="empty-icon">◇</div>' +
        '<b>还没有关联图</b><span>associator 会把记忆里的客户、公司、产品、偏好、计划、事件抽成实体，' +
        '并建立关系；第 1 步对话结束后即可看到。</span></div>';
    }
    var recallIds = model.graphRecallIds(state);
    var selected = ui.graphSelected ? model.entityById(state, ui.graphSelected) : null;

    var entityMarkup = state.graph.entities.map(function (entity) {
      var pos = GRAPH_POS[entity.id];
      if (!pos) { pos = { x: 200, y: 174 }; }
      var type = DATA.graphTypes[entity.type] || { name: entity.type, color: '#64748b' };
      var label = entity.label.length > 6 ? entity.label.slice(0, 5) + '…' : entity.label;
      var width = pos.center ? 76 : Math.max(48, label.length * 10.5 + 18);
      var height = pos.center ? 30 : 24;
      var recalled = recallIds[entity.id] && !entity.forgotten;
      var isForgotten = entity.forgotten;
      var ring = recalled
        ? '<rect class="g-ring" x="' + (-width / 2 - 4) + '" y="' + (-height / 2 - 4) + '" width="' +
          (width + 8) + '" height="' + (height + 8) + '" rx="' + (pos.center ? 13 : 11) + '"></rect>'
        : '';
      return '<g class="g-node' + (selected && selected.id === entity.id ? ' is-selected' : '') +
        (isForgotten ? ' is-forgotten' : '') + '" data-act="graph-node" data-id="' + esc(entity.id) +
        '" transform="translate(' + pos.x + ',' + pos.y + ')">' +
        ring +
        '<rect class="g-node-bg" x="' + (-width / 2) + '" y="' + (-height / 2) + '" width="' + width +
          '" height="' + height + '" rx="' + (pos.center ? 9 : 7) + '" fill="' + type.color + '"></rect>' +
        '<text class="g-node-text" x="0" y="3.5" text-anchor="middle">' + esc(label) + '</text>' +
        '<title>' + esc(entity.label + ' · ' + type.name + (isForgotten ? ' · 已遗忘' : '')) + '</title>' +
        '</g>';
    }).join('');

    var edgeMarkup = state.graph.relations.map(function (relation) {
      var fromPos = GRAPH_POS[relation.source];
      var toPos = GRAPH_POS[relation.target];
      if (!fromPos || !toPos) { return ''; }
      var midX = (fromPos.x + toPos.x) / 2;
      var midY = (fromPos.y + toPos.y) / 2;
      return '<g class="g-edge' + (recallIds[relation.id] ? ' is-recalled' : '') + '">' +
        '<line x1="' + fromPos.x + '" y1="' + fromPos.y + '" x2="' + toPos.x + '" y2="' + toPos.y +
          '" marker-end="url(#arrow-v2)"></line>' +
        '<text class="g-edge-label" x="' + midX + '" y="' + (midY - 3) + '">' + esc(relation.label) + '</text>' +
        '</g>';
    }).join('');

    var legendTypes = {};
    state.graph.entities.forEach(function (entity) { legendTypes[entity.type] = true; });
    var legend = Object.keys(legendTypes).map(function (type) {
      var meta = DATA.graphTypes[type];
      return '<span class="legend-item"><i style="background:' + meta.color + '"></i>' + esc(meta.name) + '</span>';
    }).join('') + '<span class="legend-item"><i class="recall-line"></i>本轮图召回</span>';

    var episodes = state.graph.episodes.slice().reverse().map(function (episode) {
      return '<div class="episode-row"><span class="ep-dot"></span>' + esc(episode.label) +
        '<span class="ep-at">' + esc(episode.at) + '</span></div>';
    }).join('');

    var recallCount = state.recall && state.recall.graphHits ? state.recall.graphHits.length : 0;
    var assocText = 'associator 让记忆围绕「周砚清」这个客户节点组织：金额 / 期限 / 偏好 / 计划 / 事件' +
      '各自成为实体和关系。' + (recallCount ? '本轮图通道召回 ' + recallCount + ' 个节点。' : '本轮暂无图召回。');

    return '<div class="graph-toolbar"><b>实体关系图</b>' +
      '<span class="graph-note">图形化展示关联 / 合并结果</span></div>' +
      '<div class="graph-card">' +
        '<svg class="graph-svg" viewBox="0 0 400 330" role="img" aria-label="客户实体关系图">' +
          '<defs><marker id="arrow-v2" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="5" markerHeight="5" ' +
            'orient="auto-start-reverse"><path d="M0 0 L10 5 L0 10 z" fill="#b9c6d6"></path></marker></defs>' +
          edgeMarkup + entityMarkup +
        '</svg>' +
        '<div class="graph-legend">' + legend + '</div>' +
        '<div class="graph-hint">点击节点查看关联记忆与关系</div>' +
      '</div>' +
      (selected ? renderNodeDetail(selected) : '') +
      (episodes ? '<div class="episodes-card"><div class="svc-card-title"><b>情节 Episodes</b>' +
        '<span>' + state.graph.episodes.length + ' 条</span></div>' + episodes + '</div>' : '') +
      '<div class="assoc-callout"><b>关联与合并：</b>' + esc(assocText) + '</div>';
  }

  function renderNodeDetail(entity) {
    var type = DATA.graphTypes[entity.type] || { name: entity.type, color: '#64748b' };
    var relations = state.graph.relations.filter(function (relation) {
      return relation.source === entity.id || relation.target === entity.id;
    }).map(function (relation) {
      var outgoing = relation.source === entity.id;
      var otherId = outgoing ? relation.target : relation.source;
      var other = model.entityById(state, otherId);
      return '<span class="edge-chip">' + (outgoing ? '→ ' : '← ') + esc(relation.label) + ' ' +
        esc(other ? other.label : otherId) + '</span>';
    }).join('');
    var units = model.unitsForEntity(state, entity.id);
    var memRows = units.map(function (unit) {
      return '<button class="unit-row" data-act="cite" data-unit="' + esc(unit.id) + '" title="' +
        esc(unit.content) + '"><span class="unit-id">' + esc(unit.id) + '</span>' +
        '<span class="tier-chip tier-' + esc(unit.tier) + '">' + esc(unit.tier) + '</span>' +
        '<span class="life-chip life-' + esc(unit.lifecycle) + '">' + esc(unit.lifecycle) + '</span>' +
        '<span class="unit-text">' + esc(unit.content) + '</span></button>';
    }).join('');

    return '<div class="node-detail">' +
      '<div class="node-detail-head">' +
        '<span class="node-dot" style="background:' + type.color + '"></span>' +
        '<b>' + esc(entity.label) + '</b>' +
        '<span class="node-meta">' + esc(type.name) + (entity.forgotten ? ' · 已遗忘' : '') +
          ' · ' + units.length + ' 条记忆</span>' +
      '</div>' +
      (relations ? '<div class="node-relations">' + relations + '</div>' : '') +
      (memRows ? '<div class="node-mem-list"><div class="mini-label">关联记忆</div>' + memRows + '</div>' : '') +
      '</div>';
  }

  /* ---- tab 4：服务 ---- */

  function renderServiceTab() {
    var stats = model.stats(state);
    var svc = DATA.service;

    var apiChips = svc.apiCapabilities.map(function (api) {
      return '<span class="api-chip"><b>' + esc(api.name) + '</b><span>' + esc(api.desc) + '</span></span>';
    }).join('');

    var configRows = svc.config.map(function (row) {
      return '<div class="svc-row"><span>' + esc(row.label) + '</span><b>' + esc(row.value) + '</b></div>';
    }).join('');

    var rawSteps = svc.rawDataSteps.map(function (text, index) {
      return '<div class="raw-step"><i>' + (index + 1) + '</i><div>' + esc(text) + '</div></div>';
    }).join('');

    return '<div class="portal-card">' +
      '<div class="portal-head">' +
        '<span class="portal-logo">AM</span>' +
        '<div class="portal-main"><b>Agent Memory Portal</b>' +
          '<span>记忆管理 · 个人视图 · 可检视 / 纠正 / 遗忘</span></div>' +
        '<span class="svc-status"><span class="dot"></span>运行中</span>' +
      '</div>' +
      '<div class="portal-url">' + esc(svc.portal) + '</div>' +
      '<div class="portal-note">' + esc(svc.portalHint) + '</div>' +
      '<div class="portal-actions">' +
        '<button class="btn btn-primary" data-act="open-portal">打开记忆管理</button>' +
        '<button class="btn" data-act="copy-portal">复制地址</button>' +
      '</div>' +
      '</div>' +

      '<div class="svc-card">' +
        '<div class="svc-card-title"><b>服务状态</b><span>演示态</span></div>' +
        '<div class="svc-row"><span>API 地址</span><b>' + esc(svc.api) + '</b></div>' +
        '<div class="svc-row"><span>健康检查</span><b>' + esc(svc.health) + ' · <span class="ok">200</span></b></div>' +
        '<div class="svc-row"><span>服务版本</span><b>' + esc(svc.version) + ' · ' + esc(svc.deployment) + '</b></div>' +
        '<div class="svc-row"><span>运行时长</span><b>' + esc(svc.uptime) + '</b></div>' +
        '<div class="svc-row"><span>Scope</span><b>org=' + esc(svc.scope.org) + ' · user=' +
          esc(svc.scope.user) + ' · agent=' + esc(svc.scope.agent) + '</b></div>' +
      '</div>' +

      '<div class="svc-card">' +
        '<div class="svc-card-title"><b>存储与索引</b><span>本机单实例</span></div>' +
        '<div class="svc-row"><span>真源</span><b>SQLite <code>memory.db</code>（docker volume）</b></div>' +
        '<div class="svc-row"><span>索引</span><b>向量 / 全文 / 图索引（进程内，随服务重建）</b></div>' +
        '<div class="svc-row"><span>数据量</span><b>记忆单元 ' + stats.totalUnits + '（有效 ' +
          stats.activeUnits + ' · 已取代 ' + stats.supersededUnits + ' · 已遗忘 ' +
          stats.forgottenUnits + '）· 图实体 ' + stats.entities + ' · 关系 ' + stats.relations + '</b></div>' +
      '</div>' +

      '<div class="svc-card">' +
        '<div class="svc-card-title"><b>MemoryAPI</b><span>add / search / list / update / evolve</span></div>' +
        '<div class="api-chip-grid">' + apiChips + '</div>' +
      '</div>' +

      '<div class="svc-card">' +
        '<div class="svc-card-title"><b>配置摘要</b><span>演示数据</span></div>' + configRows +
      '</div>' +

      '<div class="svc-card">' +
        '<div class="svc-card-title"><b>怎么看原始数据</b><span>Portal / API</span></div>' +
        '<div class="raw-steps">' + rawSteps + '</div>' +
      '</div>' +

      '<div class="boundary-card"><b>原型边界：</b>' + esc(DATA.meta.boundary) +
        ' 记忆服务与真实模型由 Java 样例接入（<code>memory-service/</code> + <code>scripts/start-agent-memory.sh</code>）。' +
        '</div>';
  }

  /* ---------------- 演示脚本抽屉 ---------------- */

  function renderDrawer() {
    var current = Math.min(state.step + 1, DATA.steps.length);
    var cards = DATA.demoScript.map(function (item) {
      var status = state.step >= item.step ? 'is-done' : item.step === current ? 'is-current' : '';
      var stateText = state.step >= item.step ? '已完成' : item.step === current ? '待演示' : '未开始';
      var memoryList = item.memory.map(function (line) { return '<li>' + esc(line) + '</li>'; }).join('');
      return '<article class="dstep ' + status + '">' +
        '<div class="dstep-head"><span class="dnum">' + item.step + '</span>' +
          '<b>' + esc(item.title) + '</b><span class="dstate">' + stateText + '</span></div>' +
        '<div class="dstep-body">' +
          '<div class="dlabel">客户会说</div>' +
          '<p class="dquote">「' + esc(item.input) + '」</p>' +
          '<div class="dlabel">预期记忆变化（语义级）</div>' +
          '<ul class="dlist">' + memoryList + '</ul>' +
          '<div class="dlabel">预期召回</div>' +
          '<ul class="dlist"><li>' + esc(item.recall) + '</li></ul>' +
          '<div class="diff-y">预期双回答差异：' + esc(item.diff) + '</div>' +
          '<button class="btn ' + (state.step >= item.step ? '' : 'btn-ghost-brand') + '"' +
            ' data-act="jump-step" data-step="' + item.step + '">' +
            (state.step >= item.step ? '回看这一步' : '演示到这一步') + '</button>' +
        '</div>' +
        '</article>';
    }).join('');

    return '<div class="drawer-mask" data-act="close-drawer">' +
      '<aside class="drawer">' +
        '<div class="drawer-head"><b>演示脚本</b><span>4 步</span>' +
          '<button class="icon-btn" data-act="close-drawer" title="关闭">×</button></div>' +
        '<div class="drawer-sub">页面内所有记忆、分数与回答都是演示数据；' +
          '脚本说明「输入什么 → 预期记忆变化 → 预期召回 → 预期双回答差异」，真实运行请看 Java 样例。</div>' +
        '<div class="drawer-body">' + cards + '</div>' +
        '<div class="drawer-foot">与 <code>prototype/README.md</code> 的「演示脚本」一节一致。' +
          '原型不连接 Agent Memory 服务，也不调用真实模型。</div>' +
        '</aside>' +
      '</div>';
  }

  function renderToast() {
    if (!ui.toast) { return ''; }
    return '<div class="toast' + (ui.toast.kind === 'ok' ? ' is-ok' : '') + '">' +
      '<span class="toast-dot"></span><span>' + esc(ui.toast.text) + '</span></div>';
  }

  /* ---------------- 动作 ---------------- */

  function showToast(text, kind) {
    ui.toast = { text: text, kind: kind || '' };
    render();
    clearTimeout(showToast.timer);
    showToast.timer = setTimeout(function () {
      ui.toast = null;
      render();
    }, 3200);
  }

  var STEP_TOAST = {
    1: '第 1 步完成：抽取 6 条记忆 · 合并 1 条画像 · 关联 8 个实体；下一次对话才会出现双回答差异。',
    2: '第 2 步完成：召回 4 条记忆并注入 MemoryRail，双回答出现 3 处差异。',
    3: '第 3 步完成：更新 1 条（SUPERSEDE）· 演进 1 条客户画像 · 图索引已同步。',
    4: '第 4 步完成：遗忘 1 条敏感记忆 · 画像重新演进为 v2 · 图关系已移除。'
  };

  function finishStep(stepNumber) {
    state = model.buildState(stepNumber);
    ui.loadingStep = null;
    ui.expandedSteps[stepNumber] = true;
    pendingBottom = true;
    showToast(STEP_TOAST[stepNumber] || '演示已推进', 'ok');
  }

  function runNextStep() {
    if (ui.loadingStep) { return; }
    var next = model.nextStep(state);
    if (!next) { return; }
    if (model.viewingHistory(state)) {
      state = model.selectSession(state, model.latestSessionId(state));
    }
    if (next.createsSession && state.currentSession !== next.session) {
      state = model.openNewSession(state);
    }
    ui.loadingStep = next.n;
    pendingBottom = true;
    render();
    setTimeout(function () {
      if (ui.loadingStep === next.n) { finishStep(next.n); }
    }, 900);
  }

  function jumpToStep(stepNumber) {
    ui.loadingStep = null;
    state = model.buildState(stepNumber);
    ui.expandedSteps = {};
    ui.expandedUnits = {};
    if (stepNumber > 0) { ui.expandedSteps[stepNumber] = true; }
    ui.drawer = false;
    pendingBottom = true;
    showToast(stepNumber === 0 ? '已重置演示' : '已切换到第 ' + stepNumber + ' 步（演示数据）', 'ok');
  }

  function openPortal() {
    ui.tab = 'service';
    showToast('原型占位：真实 Portal 地址由样例启动时打印（' + DATA.service.portal + '，带一次性 access token），' +
      '本页面不会打开真实服务。');
  }

  function copyPortal() {
    var text = DATA.service.portal;
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(function () {
        showToast('已复制演示地址：' + text, 'ok');
      }, function () {
        showToast('演示地址：' + text);
      });
    } else {
      showToast('演示地址：' + text);
    }
  }

  function toggleExpandedUnit(unitId) {
    ui.expandedUnits[unitId] = !ui.expandedUnits[unitId];
    render();
  }

  function focusUnit(unitId) {
    var unit = model.unitById(state, unitId);
    if (!unit) { return; }
    ui.tab = 'evolution';
    ui.expandedUnits[unitId] = true;
    ui.focusUnit = unitId;
    render();
  }

  function gotoRecall(unitId) {
    ui.tab = 'recall';
    ui.focusUnit = null;
    render();
    showToast('召回轨迹里已用红色标出被注入的记忆（演示数据）');
  }

  /* ---------------- 事件 ---------------- */

  app.addEventListener('click', function (event) {
    var target = event.target.closest('[data-act]');
    if (!target) { return; }
    var action = target.getAttribute('data-act');
    if (target.disabled) { return; }

    if (action === 'close-drawer') {
      ui.drawer = false;
      render();
      return;
    }
    if (action === 'open-drawer') { ui.drawer = true; render(); return; }
    if (action === 'tab') {
      ui.tab = target.getAttribute('data-tab');
      ui.resetWbScroll = true;
      render();
      return;
    }
    if (action === 'toggle-step') {
      var stepNumber = Number(target.getAttribute('data-step'));
      var current = ui.expandedSteps[stepNumber] == null ? stepNumber === state.step : !!ui.expandedSteps[stepNumber];
      ui.expandedSteps[stepNumber] = !current;
      render();
      return;
    }
    if (action === 'unit') {
      toggleExpandedUnit(target.getAttribute('data-unit'));
      return;
    }
    if (action === 'cite') {
      focusUnit(target.getAttribute('data-unit'));
      return;
    }
    if (action === 'graph-node') {
      var nodeId = target.getAttribute('data-id');
      ui.graphSelected = ui.graphSelected === nodeId ? null : nodeId;
      render();
      return;
    }
    if (action === 'toggle-collapsed') {
      ui.collapsed = !ui.collapsed;
      render();
      return;
    }
    if (action === 'toggle-compare') {
      ui.compare = !ui.compare;
      render();
      return;
    }
    if (action === 'select-session') {
      state = model.selectSession(state, Number(target.getAttribute('data-session')));
      pendingBottom = true;
      render();
      return;
    }
    if (action === 'new-session') {
      if (!model.canOpenNewSession(state)) { return; }
      state = model.openNewSession(state);
      pendingBottom = true;
      render();
      return;
    }
    if (action === 'next-step') { runNextStep(); return; }
    if (action === 'jump-step') {
      jumpToStep(Number(target.getAttribute('data-step')));
      return;
    }
    if (action === 'reset') { jumpToStep(0); return; }
    if (action === 'open-portal') { openPortal(); return; }
    if (action === 'copy-portal') { copyPortal(); return; }
    if (action === 'goto-recall') { gotoRecall(target.getAttribute('data-unit')); return; }
    if (action === 'send') { sendDraft(); return; }
  });

  function sendDraft() {
    var text = String(draft || '').trim();
    if (!text) { return; }
    draft = '';
    var input = document.getElementById('composer-input');
    if (input) { input.value = ''; }
    var sendBtn = app.querySelector('.send-btn');
    if (sendBtn) { sendBtn.disabled = true; }
    showToast('本原型是脚本演示，不接受自由输入：请点「发送并演示」按 4 步脚本推进（演示数据，不连真实服务）。');
  }

  app.addEventListener('input', function (event) {
    if (event.target && event.target.id === 'composer-input') {
      draft = event.target.value;
      var sendBtn = app.querySelector('.send-btn');
      if (sendBtn) { sendBtn.disabled = !draft.trim(); }
    }
  });

  app.addEventListener('keydown', function (event) {
    if (event.key === 'Escape' && ui.drawer) {
      ui.drawer = false;
      render();
      return;
    }
    if (event.target && event.target.id === 'composer-input' && event.key === 'Enter') {
      event.preventDefault();
      sendDraft();
    }
  });

  /* ---------------- 启动 / 截图参数 ----------------
   * ?step=0..4          演示到第几步（默认 2）
   * ?tab=evolution|recall|graph|service
   * ?drawer=1           打开演示脚本抽屉
   * ?collapsed=1        收起左侧
   * ?session=1          回看历史会话
   * ?node=e_loan        关联图选中节点
   * ?open=1,3           展开指定步骤的记忆演进分组
   * ?compare=0          关闭双回答（只留记忆回答）
   */

  var query = new URLSearchParams(window.location.search);
  var startStep = query.get('step');
  var stepCount = startStep == null ? 2 : Math.max(0, Math.min(4, Number(startStep) || 0));
  state = model.buildState(stepCount);
  if (state.step > 0) {
    ui.expandedSteps[state.step] = true;
    if (state.step > 1) { ui.expandedSteps[1] = true; }
  }

  var tabParam = query.get('tab');
  if (['evolution', 'recall', 'graph', 'service'].indexOf(tabParam) >= 0) { ui.tab = tabParam; }
  if (query.get('drawer') === '1') { ui.drawer = true; }
  if (query.get('collapsed') === '1') { ui.collapsed = true; }
  if (query.get('compare') === '0') { ui.compare = false; }
  if (query.get('session') === '1' && state.sessions.length > 1) {
    state = model.selectSession(state, 1);
  }
  if (query.get('node')) { ui.graphSelected = query.get('node'); }
  if (query.get('open')) {
    query.get('open').split(',').forEach(function (value) {
      var n = Number(value);
      if (n) { ui.expandedSteps[n] = true; }
    });
  }
  if (window.location.hash === '#memory-portal') {
    ui.tab = 'service';
  }
  var loadingParam = Number(query.get('loading'));
  if (loadingParam >= 1 && loadingParam <= 4 && state.step === loadingParam - 1) {
    ui.loadingStep = loadingParam;
    pendingBottom = true;
  }

  render();
  window.__PROTO__ = {
    getState: function () { return state; },
    getUi: function () { return ui; },
    nextStep: runNextStep,
    jumpTo: jumpToStep
  };
})();
