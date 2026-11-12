/* 信贷客户记忆助手 · Java 样例前端页面
 *
 * 原生 HTML/CSS/JS，无构建、无外部依赖。
 * 页面不内置任何演示数据：会话、对话、记忆、图记忆、加工流程、召回结果与模型配置
 * 全部来自 Java 后端 /api/*（契约见 java/API.md）。
 *
 * 交互主线：
 *   1. GET /api/bootstrap            → 会话列表 + 模型配置状态
 *   2. GET /api/sessions/{id}/state  → 完整会话状态（对话 / 记忆 / 图 / 召回 / 加工流程）
 *   3. POST /api/chat                → 同步返回回复；随后轮询 /state 直到 pipeline.running=false
 *   4. POST /api/sessions            → 新建会话（记忆与图是全局的，跨会话保留）
 *   5. POST /api/settings            → 保存对话模型 / 记忆模型（含 Embedding）配置
 */
(function () {
  'use strict';

  /* ================= 常量 ================= */

  var API = {
    bootstrap: '/api/bootstrap',
    sessions: '/api/sessions',
    chat: '/api/chat',
    settings: '/api/settings',
    state: function (id) { return '/api/sessions/' + encodeURIComponent(id) + '/state'; }
  };

  var POLL_INTERVAL = 1000;      // 契约要求 800–1200ms
  var POLL_MAX_MS = 600000;      // 本地模型记忆抽取较慢（单轮可达数分钟），最多轮询 10 分钟
  var DEFAULT_TIMEOUT = 15000;
  var CHAT_TIMEOUT = 300000;     // 真实模型回复可能 1–2 分钟甚至更久，留足 5 分钟

  /* 长期记忆分层（与 SDK MemoryType 对齐） */
  var LAYERS = [
    { id: 'profile', name: '用户画像', hint: '客户身份与偏好', color: '#c7000b', storage: '本机 SQLite' },
    { id: 'semantic', name: '语义记忆', hint: '事实、概念与计划（无明确时间）', color: '#0f766e', storage: '本机 SQLite' },
    { id: 'episodic', name: '情景记忆', hint: '带时间点的事件与沟通记录', color: '#1d4ed8', storage: '本机 SQLite' },
    { id: 'summary', name: '摘要记忆', hint: '一轮对话结束后的压缩摘要', color: '#b45309', storage: '本机 SQLite' },
    { id: 'variable', name: '变量记忆', hint: '金额、期数等结构化字段', color: '#15803d', storage: '本机 SQLite · KV' },
    { id: 'other', name: '其他记忆', hint: '未归类的记忆条目', color: '#64748b', storage: '本机 SQLite' }
  ];

  var LAYER_ALIASES = {
    profile: 'profile', user_profile: 'profile', userprofile: 'profile', '用户画像': 'profile',
    semantic: 'semantic', semantic_memory: 'semantic', '语义记忆': 'semantic',
    episodic: 'episodic', episodic_memory: 'episodic', '情景记忆': 'episodic',
    summary: 'summary', summary_memory: 'summary', '摘要记忆': 'summary',
    variable: 'variable', variable_memory: 'variable', '变量记忆': 'variable'
  };

  var VARIABLE_LABELS = {
    customerId: '客户号', customerName: '客户姓名', product: '产品', amount: '金额',
    term: '期数', plannedYears: '结清计划', rate: '利率', repayment: '还款方式'
  };

  var GRAPH_TYPES = {
    person: { name: '人物', color: '#c7000b' },
    company: { name: '企业', color: '#0f766e' },
    organization: { name: '企业', color: '#0f766e' },
    org: { name: '企业', color: '#0f766e' },
    location: { name: '所在地', color: '#64748b' },
    product: { name: '产品', color: '#1d4ed8' },
    preference: { name: '偏好', color: '#b45309' },
    plan: { name: '计划', color: '#0891b2' },
    event: { name: '事件', color: '#be123c' },
    episode: { name: '情节', color: '#be123c' },
    topic: { name: '主题', color: '#7c3aed' },
    amount: { name: '金额', color: '#15803d' },
    other: { name: '其他', color: '#64748b' }
  };

  var GRAPH_TYPE_ALIASES = {
    '人物': 'person', '客户': 'person', '用户': 'person',
    '企业': 'company', '公司': 'company', '机构': 'company', '组织': 'company',
    '所在地': 'location', '地点': 'location', '城市': 'location',
    '产品': 'product', '偏好': 'preference', '计划': 'plan', '事件': 'event', '情节': 'episode'
  };

  var GRAPH_COLORS = ['#c7000b', '#0f766e', '#1d4ed8', '#b45309', '#0891b2', '#be123c', '#7c3aed', '#64748b', '#15803d'];

  var STAGES = [
    { key: 'ingest', label: '对话进入加工队列' },
    { key: 'analyze', label: 'LLM 记忆分析' },
    { key: 'extract', label: '结构化抽取' },
    { key: 'merge', label: '去重与合并' },
    { key: 'write', label: '写入存储' },
    { key: 'ready', label: '可召回' }
  ];

  var MAX_GRAPH_NODES = 18;

  /* ================= 应用状态 ================= */

  var app = {
    bootstrap: null,
    sessions: [],
    activeSessionId: null,     // 当前可发送消息的会话（后端 activeSessionId）
    locallyCreatedSessionId: null,
    sessionId: null,           // 正在查看的会话（可能是历史会话）
    session: null,
    snapshot: null,
    bootstrapError: null,
    loading: true,
    loadingSession: false,
    sending: false,
    processing: false,          // /api/chat 返回后、轮询确认前的加工提示
    creating: false,
    pendingCustomer: null,
    pendingReply: null,
    seenSessionId: null,
    memSeen: {},               // id -> { version, content }
    memPrev: {},               // id -> 变更前内容
    flash: { new: {}, updated: {} },
    loadToken: 0,
    poll: { sessionId: null, timer: null, deadline: 0, failures: 0 },
    banner: { type: null, timer: null },
    lastMemoryError: null,
    retryFn: null,
    ui: { tab: 'longterm', graphSelected: null, sidebarCollapsed: false, settingsOpen: false, saving: false },
    deepLink: { tab: null, session: null, settings: false }
  };

  var refs = {};

  /* ================= 工具函数 ================= */

  function esc(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function pad2(n) { return n < 10 ? '0' + n : String(n); }

  function nowTime() {
    var d = new Date();
    return pad2(d.getHours()) + ':' + pad2(d.getMinutes());
  }

  function el(id) { return document.getElementById(id); }

  function num(value, fallback) {
    var n = Number(value);
    return isFinite(n) ? n : (fallback == null ? 0 : fallback);
  }

  function safeArray(value) {
    return Array.isArray(value) ? value : [];
  }

  function truncate(text, limit) {
    var s = String(text == null ? '' : text);
    if (s.length <= limit) return s;
    return s.slice(0, Math.max(1, limit - 1)) + '…';
  }

  function hashCode(text) {
    var hash = 0;
    var s = String(text == null ? '' : text);
    for (var i = 0; i < s.length; i++) {
      hash = ((hash << 5) - hash + s.charCodeAt(i)) | 0;
    }
    return Math.abs(hash);
  }

  /** 后端按创建顺序返回会话；页面按时间倒序展示（新会话在上）。 */
  function sessionsForDisplay() {
    var items = app.sessions.map(function (session, index) {
      var parsed = NaN;
      if (session.createdAt) {
        parsed = Date.parse(String(session.createdAt).replace(' ', 'T'));
      }
      return { session: session, index: index, time: isFinite(parsed) ? parsed : timeSortFallback(index) };
    });
    var allParsed = app.sessions.every(function (session) {
      return session.createdAt && isFinite(Date.parse(String(session.createdAt).replace(' ', 'T')));
    });
    if (!allParsed) return app.sessions;
    items.sort(function (a, b) {
      if (b.time !== a.time) return b.time - a.time;
      return b.index - a.index;
    });
    return items.map(function (item) { return item.session; });
  }

  function timeSortFallback(index) { return index; }

  function findSession(id) {
    for (var i = 0; i < app.sessions.length; i++) {
      if (String(app.sessions[i].id) === String(id)) return app.sessions[i];
    }
    return null;
  }

  function ApiError(message, detail) {
    this.name = 'ApiError';
    this.message = message || '请求失败';
    this.detail = detail || '';
  }
  ApiError.prototype = Object.create(Error.prototype);
  ApiError.prototype.constructor = ApiError;

  /* ================= 网络请求 ================= */

  function request(method, path, body, timeoutMs) {
    var timeout = timeoutMs || DEFAULT_TIMEOUT;
    var hasAbort = typeof AbortController !== 'undefined';
    var controller = hasAbort ? new AbortController() : null;
    var timer = setTimeout(function () {
      if (controller) { controller.abort(); }
    }, timeout);

    var init = { method: method, headers: { 'Accept': 'application/json' } };
    if (controller) { init.signal = controller.signal; }
    if (body !== undefined) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body);
    }

    return fetch(path, init).then(function (res) {
      return res.text().then(function (text) {
        clearTimeout(timer);
        var data = null;
        var parsed = false;
        if (text) {
          try { data = JSON.parse(text); parsed = true; } catch (e) { parsed = false; }
        }
        if (!res.ok) {
          var message = '';
          if (data && typeof data === 'object') {
            message = data.error || data.message || data.msg || '';
          }
          if (!message) {
            message = 'HTTP ' + res.status + (res.statusText ? ' ' + res.statusText : '');
          }
          throw new ApiError(message + '（' + method + ' ' + path + '）', text);
        }
        if (text && !parsed) {
          throw new ApiError('服务返回了非 JSON 响应（' + method + ' ' + path + '）', text);
        }
        return data;
      });
    }).catch(function (err) {
      clearTimeout(timer);
      if (err && err.name === 'ApiError') throw err;
      if (err && err.name === 'AbortError') {
        throw new ApiError('请求超时（' + Math.round(timeout / 1000) + ' 秒）：' + method + ' ' + path);
      }
      if (err && err.name === 'SyntaxError') {
        throw new ApiError('服务返回了无法解析的响应（' + method + ' ' + path + '）');
      }
      throw new ApiError('无法连接后端服务（' + method + ' ' + path + '）。请确认 Java 样例已启动并监听 8099 端口。',
        err && err.message ? String(err.message) : '');
    });
  }

  function get(path, timeout) { return request('GET', path, undefined, timeout); }
  function post(path, body, timeout) { return request('POST', path, body === undefined ? {} : body, timeout); }

  /* ================= 顶部提示条 ================= */

  function showBanner(type, text, retryFn, autoHideMs) {
    if (app.banner.timer) { clearTimeout(app.banner.timer); app.banner.timer = null; }
    app.banner.type = type;
    app.retryFn = typeof retryFn === 'function' ? retryFn : null;

    refs.errorBar.className = type === 'error' ? 'error-bar' : 'error-bar is-' + type;
    refs.errorText.textContent = text;
    refs.errorRetry.hidden = !app.retryFn;
    refs.errorBar.hidden = false;

    if (type === 'success' || type === 'info') {
      app.banner.timer = setTimeout(function () { hideBanner(); }, autoHideMs || 4200);
    }
  }

  function hideBanner() {
    if (app.banner.timer) { clearTimeout(app.banner.timer); app.banner.timer = null; }
    app.banner.type = null;
    app.retryFn = null;
    refs.errorBar.hidden = true;
    refs.errorRetry.hidden = true;
  }

  function clearErrorBanner() {
    if (app.banner.type === 'error') hideBanner();
  }

  /* ================= 初始化 ================= */

  function bindEvents() {
    document.addEventListener('click', function (event) {
      var target = event.target;
      if (!target || !target.closest) return;
      var actEl = target.closest('[data-act]');
      if (!actEl) return;
      handleAction(actEl.getAttribute('data-act'), actEl, event);
    });

    refs.input.addEventListener('input', function () {
      updateComposer();
    });

    refs.input.addEventListener('keydown', function (event) {
      if (event.key === 'Enter' && !event.isComposing) {
        event.preventDefault();
        sendMessage();
      }
    });

    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape' && app.ui.settingsOpen && !app.ui.saving) {
        closeSettings();
      }
    });

    document.addEventListener('visibilitychange', function () {
      if (!document.hidden && isPipelineRunning() && !app.poll.sessionId && app.sessionId) {
        startPolling(app.sessionId);
      }
    });
  }

  function handleAction(action, targetEl, event) {
    switch (action) {
      case 'open-settings':
        openSettings();
        break;
      case 'close-settings':
        if (targetEl.classList.contains('modal-mask') && event.target !== targetEl) return;
        if (!app.ui.saving) closeSettings();
        break;
      case 'save-settings':
        saveSettings();
        break;
      case 'new-session':
        createSession();
        break;
      case 'toggle-sidebar':
        toggleSidebar();
        break;
      case 'select-session':
        selectSession(targetEl.getAttribute('data-session'));
        break;
      case 'back-current':
        selectSession(app.activeSessionId);
        break;
      case 'tab':
        setTab(targetEl.getAttribute('data-tab'));
        break;
      case 'graph-node':
        selectGraphNode(targetEl.getAttribute('data-id'));
        break;
      case 'send':
        sendMessage();
        break;
      case 'error-retry':
        if (app.retryFn) { app.retryFn(); }
        break;
      case 'error-close':
        hideBanner();
        break;
      case 'retry-bootstrap':
        initialLoad();
        break;
      default:
        break;
    }
  }

  function initialLoad() {
    app.loading = true;
    app.bootstrapError = null;
    hideBanner();
    renderSessions();
    renderChat();
    renderMemory();

    loadBootstrap(false).then(function () {
      app.loading = false;
      var target = app.deepLink.session || app.activeSessionId;
      if (target && !findSession(target)) { target = app.activeSessionId; }
      if (!target) {
        renderAll();
        if (app.deepLink.settings) openSettings();
        return;
      }
      loadSession(target, { scrollBottom: true }).then(function () {
        if (app.deepLink.settings) openSettings();
      });
    }).catch(function () {
      app.loading = false;
      renderAll();
      if (app.deepLink.settings) openSettings();
    });
  }

  function loadBootstrap(silent) {
    return get(API.bootstrap).then(function (data) {
      app.bootstrap = data && typeof data === 'object' ? data : {};
      app.sessions = safeArray(app.bootstrap.sessions);
      app.bootstrapError = null;

      var backendActive = app.bootstrap.activeSessionId || null;
      if (!app.activeSessionId) {
        app.activeSessionId = backendActive;
      } else if (!findSession(app.activeSessionId)) {
        // 刚创建的会话可能还没出现在 bootstrap 列表里：以本地为准，避免立刻变成「历史会话」
        if (String(app.locallyCreatedSessionId) !== String(app.activeSessionId)) {
          app.activeSessionId = backendActive;
        }
      } else if (String(app.locallyCreatedSessionId) === String(app.activeSessionId)) {
        app.locallyCreatedSessionId = null;
      }
      renderHeaderChip();
      renderSessions();

      if (!silent) {
        if (app.bootstrap.memoryError) {
          showBanner('warn', '记忆引擎初始化异常：' + truncate(app.bootstrap.memoryError, 180) +
            '。对话仍可用，但记忆抽取、召回与图记忆可能不可用。');
          app.lastMemoryError = String(app.bootstrap.memoryError);
        } else if (!app.bootstrap.configured) {
          showBanner('warn', '尚未配置模型服务：请先在「模型设置」中填写对话模型与记忆模型，否则对话与记忆抽取会失败。');
        } else {
          clearErrorBanner();
        }
      }
      return app.bootstrap;
    }).catch(function (err) {
      app.bootstrapError = err;
      app.loading = false;
      renderHeaderChip();
      renderSessions();
      renderChat();
      renderMemory();
      if (!silent || !app.bootstrap) {
        showBanner('error', '加载初始化数据失败：' + err.message, initialLoad);
      }
      throw err;
    });
  }

  function loadSession(id, opts) {
    opts = opts || {};
    if (!id) { return Promise.resolve(); }

    var token = ++app.loadToken;
    app.loadingSession = true;
    renderChat();

    return get(API.state(id)).then(function (data) {
      if (token !== app.loadToken) return;
      app.loadingSession = false;
      app.sessionId = String(id);
      app.viewingHistory = String(id) !== String(app.activeSessionId);
      app.ui.graphSelected = null;
      clearErrorBanner();
      applySnapshot(data, { scrollBottom: opts.scrollBottom !== false });

      if (isPipelineRunning()) { startPolling(String(id)); }
      else { stopPolling(); updateComposer(); }
    }).catch(function (err) {
      if (token !== app.loadToken) return;
      app.loadingSession = false;
      showBanner('error', '加载会话状态失败：' + err.message, function () {
        loadSession(id, { scrollBottom: true });
      });
      renderChat();
      renderMemory();
    });
  }

  function applySnapshot(data, opts) {
    opts = opts || {};
    var snapshot = data && typeof data === 'object' ? data : {};
    var previousSessionId = app.seenSessionId;

    app.snapshot = snapshot;
    app.session = snapshot.session || { id: app.sessionId, title: app.session ? app.session.title : '' };
    app.pendingCustomer = null;
    app.pendingReply = null;
    app.processing = isPipelineRunning();

    if (snapshot.memoryError && String(snapshot.memoryError) !== app.lastMemoryError &&
        app.banner.type !== 'error') {
      app.lastMemoryError = String(snapshot.memoryError);
      showBanner('warn', '记忆引擎初始化异常：' + truncate(snapshot.memoryError, 180) +
        '。对话仍可用，但记忆抽取、召回与图记忆可能不可用。');
    }

    var resetTracking = previousSessionId !== app.sessionId;
    trackMemories(snapshot, resetTracking);

    renderSessions();
    renderChat({ scrollBottom: opts.scrollBottom });
    renderMemory();
    updateComposer();
  }

  /* ================= 记忆状态追踪（新 / 已更新 / 本轮用到） ================= */

  function normalizeLayer(layer) {
    var raw = String(layer == null ? '' : layer).trim();
    if (!raw) return 'other';
    var alias = LAYER_ALIASES[raw] || LAYER_ALIASES[raw.toLowerCase()];
    return alias || 'other';
  }

  function collectMemoryItems(snapshot) {
    snapshot = snapshot || {};
    var items = [];
    var byContent = {};

    safeArray(snapshot.memories).forEach(function (memory, index) {
      if (!memory || typeof memory !== 'object') return;
      var content = String(memory.content == null ? '' : memory.content).trim();
      var rawId = memory.id != null ? String(memory.id) : ('mem-' + index);
      items.push({
        id: 'm:' + rawId,
        rawId: rawId,
        layer: normalizeLayer(memory.layer),
        content: content,
        version: num(memory.version, 1),
        createdAt: memory.createdAt || '',
        oldValue: memory.updatedFrom || '',
        kind: 'memory'
      });
      byContent[content] = true;
    });

    safeArray(snapshot.variables).forEach(function (variable) {
      if (!variable || !variable.name) return;
      var name = String(variable.name);
      var label = VARIABLE_LABELS[name] || name;
      var content = label + '：' + String(variable.value == null ? '' : variable.value);
      if (byContent[content]) return;
      byContent[content] = true;
      items.push({
        id: 'v:' + name,
        rawId: 'var-' + name,
        layer: 'variable',
        content: content,
        version: variable.previous ? 2 : 1,
        createdAt: '',
        oldValue: variable.previous ? (label + '：' + String(variable.previous)) : '',
        kind: 'variable'
      });
    });

    return items;
  }

  function trackMemories(snapshot, reset) {
    var items = collectMemoryItems(snapshot);
    var nextSeen = {};
    var nextPrev = {};

    if (reset) {
      items.forEach(function (item) {
        nextSeen[item.id] = { version: item.version, content: item.content };
      });
      app.memSeen = nextSeen;
      app.memPrev = {};
      app.flash = { new: {}, updated: {} };
      app.seenSessionId = app.sessionId;
      return;
    }

    items.forEach(function (item) {
      var before = app.memSeen[item.id];
      nextSeen[item.id] = { version: item.version, content: item.content };
      if (!before) {
        app.flash.new[item.id] = true;
      } else if (before.version !== item.version || before.content !== item.content) {
        app.flash.updated[item.id] = true;
        nextPrev[item.id] = before.content;
      }
    });

    app.memSeen = nextSeen;
    app.memPrev = nextPrev;
    app.seenSessionId = app.sessionId;
  }

  function isRecalledMemory(content) {
    var text = String(content == null ? '' : content).trim();
    if (!text) return false;
    var recall = app.snapshot && app.snapshot.recall;
    var entries = safeArray(recall && recall.entries);
    for (var i = 0; i < entries.length; i++) {
      var entry = entries[i] || {};
      if (sourceKey(entry.source) === 'graph') continue;
      var label = String(entry.label == null ? '' : entry.label).trim();
      if (!label) continue;
      if (label === text) return true;
      if (label.length >= 6 && text.indexOf(label) >= 0) return true;
      if (text.length >= 6 && label.indexOf(text) >= 0) return true;
    }
    return false;
  }

  function formatRecallLabel(source, label) {
    var text = String(label == null ? '' : label);
    if (sourceKey(source) !== 'kv') return text;
    var match = text.match(/^([A-Za-z][A-Za-z0-9_]*)\s*[=＝:]\s*(.*)$/);
    if (!match) return text;
    var name = match[1];
    return (VARIABLE_LABELS[name] || name) + '：' + match[2];
  }

  function sourceKey(source) {
    var raw = String(source == null ? '' : source).trim();
    var low = raw.toLowerCase();
    if (low === 'vector' || raw === '向量' || raw === '向量召回') return 'vector';
    if (low === 'kv' || raw === 'KV' || raw === 'kv' || raw === '变量') return 'kv';
    if (low === 'graph' || raw === '图' || raw === '图记忆') return 'graph';
    return 'other';
  }

  /* ================= 通用渲染 ================= */

  function renderAll() {
    renderHeaderChip();
    renderSessions();
    renderChat();
    renderMemory();
  }

  function renderHeaderChip() {
    var b = app.bootstrap;
    if (!b) {
      refs.modelChip.className = 'model-chip is-off';
      refs.modelChipText.textContent = '未连接后端';
      refs.modelChip.title = '尚未成功连接后端，可点击重试或打开模型设置';
      return;
    }
    if (!b.configured) {
      refs.modelChip.className = 'model-chip is-off';
      refs.modelChipText.textContent = '未配置模型服务';
      refs.modelChip.title = '请点击「模型设置」填写对话模型与记忆模型';
      return;
    }
    var chat = b.chat || {};
    var memory = b.memory || {};
    var model = memory.model || chat.model || '已配置';
    refs.modelChip.className = 'model-chip is-on';
    refs.modelChipText.textContent = '记忆模型：' + model;

    var lines = [];
    if (chat.model) lines.push('对话模型：' + chat.model);
    if (memory.model) lines.push('记忆模型：' + memory.model);
    if (memory.embeddingModel) lines.push('Embedding：' + memory.embeddingModel);
    if (b.graphStore) lines.push('图存储：' + b.graphStore);
    refs.modelChip.title = lines.length ? lines.join('\n') : '模型设置';
  }

  function renderSessions() {
    var collapsed = app.ui.sidebarCollapsed;
    refs.sidebar.classList.toggle('is-collapsed', collapsed);
    refs.toggleSidebar.textContent = collapsed ? '›' : '‹';
    refs.toggleSidebar.title = collapsed ? '展开会话列表' : '收起会话列表';

    if (app.loading && !app.bootstrap) {
      refs.sessionList.innerHTML =
        '<div class="skeleton-list">' +
        '<div class="skeleton-row"></div><div class="skeleton-row"></div><div class="skeleton-row"></div>' +
        '</div>';
      return;
    }

    if (!app.sessions.length) {
      refs.sessionList.innerHTML = '<div class="side-empty">' +
        (app.bootstrapError ? '未连接后端，暂无会话。' : '还没有会话，点击上方 ＋ 开启新对话。') +
        '</div>';
      return;
    }

    refs.sessionList.innerHTML = sessionsForDisplay().map(function (session) {
      var id = String(session.id);
      var title = session.title || '未命名会话';
      var isViewed = String(app.sessionId) === id;
      var isActive = String(app.activeSessionId) === id;
      var meta = [];
      if (session.createdAt) meta.push(String(session.createdAt));
      if (session.messageCount != null) meta.push(num(session.messageCount) + ' 条消息');
      var preview = session.preview == null ? '' : String(session.preview);
      var tooltip = title + (meta.length ? ' · ' + meta.join(' · ') : '');

      return '<button class="session-item' + (isViewed ? ' is-active' : '') + '" type="button"' +
        ' data-act="select-session" data-session="' + esc(id) + '" title="' + esc(tooltip) + '">' +
        '<span class="session-avatar">' + esc(title.slice(0, 1) || '会') + '</span>' +
        '<span class="session-copy">' +
          '<b>' + esc(title) + '</b>' +
          '<span class="session-time">' + esc(meta.join(' · ')) + '</span>' +
          (preview ? '<em>' + esc(preview) + '</em>' : '') +
        '</span>' +
        (isActive ? '<span class="session-active-dot" title="当前会话"></span>' : '') +
        '</button>';
    }).join('');
  }

  /* ================= 对话区 ================= */

  function renderChat(opts) {
    opts = opts || {};
    refs.sidebar.classList.toggle('is-collapsed', app.ui.sidebarCollapsed);
    refs.memoryCount.textContent = String(collectMemoryItems(app.snapshot).length);

    var snapshot = app.snapshot || {};
    var messages = safeArray(snapshot.messages);
    var session = app.session;
    var title = session && session.title ? session.title : '';

    refs.chatSub.textContent = session
      ? (title || '新的咨询') + ' · ' + messages.length + ' 条消息'
      : (app.bootstrapError ? '未连接后端' : '正在加载会话…');

    refs.sessionPill.hidden = !session;
    refs.sessionPill.textContent = session && session.id ? String(session.id) : '';
    refs.historyTag.hidden = !app.viewingHistory;
    refs.historyNote.hidden = !app.viewingHistory;
    refs.composer.hidden = !!app.viewingHistory;
    if (app.viewingHistory) { refs.composerStatus.hidden = true; }

    var scroller = refs.chatScroll;
    var atBottom = !scroller || (scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < 56);
    var html = '';
    var canShowMessages = !!(app.session && (messages.length || app.pendingCustomer || app.pendingReply));

    if (app.loadingSession && !messages.length) {
      html = '<div class="chat-empty">' +
        '<div class="chat-empty-icon">◌</div>' +
        '<div class="chat-empty-title">正在加载会话…</div>' +
        '<div class="chat-empty-text">从后端读取对话、记忆与加工流程状态。</div>' +
        '</div>';
    } else if (canShowMessages) {
      messages.forEach(function (message) {
        html += renderMessage(message);
      });

      if (app.pendingCustomer && !hasMessageText(messages, 'customer', app.pendingCustomer.text)) {
        html += renderMessage({
          id: 'pending-customer',
          role: 'customer',
          text: app.pendingCustomer.text,
          ts: app.pendingCustomer.ts,
          pending: true
        });
      }
      if (app.pendingReply && !hasMessageText(messages, 'agent', app.pendingReply.text)) {
        html += renderMessage({
          id: 'pending-reply',
          role: 'agent',
          text: app.pendingReply.text,
          ts: app.pendingReply.ts,
          cites: app.pendingReply.cites,
          graphCites: app.pendingReply.graphCites,
          pending: true
        });
      }
    } else if (app.bootstrapError) {
      html = '<div class="chat-empty">' +
        '<div class="chat-empty-icon">!</div>' +
        '<div class="chat-empty-title">无法加载会话</div>' +
        '<div class="chat-empty-text">' + esc(app.bootstrapError.message) + '</div>' +
        '<button class="primary-btn" type="button" data-act="retry-bootstrap">重新连接</button>' +
        '</div>';
    } else if (!app.sessionId) {
      html = '<div class="chat-empty">' +
        '<div class="chat-empty-icon">＋</div>' +
        '<div class="chat-empty-title">还没有会话</div>' +
        '<div class="chat-empty-text">点击左侧「＋」开启新对话。客户发送的消息会由后端调用真实模型回复，' +
        '并在对话结束后异步提炼长期记忆与图记忆。</div>' +
        '<button class="primary-btn" type="button" data-act="new-session">开启新对话</button>' +
        '</div>';
    } else {
      html = '<div class="chat-empty">' +
        '<div class="chat-empty-icon">✦</div>' +
        '<div class="chat-empty-title">发送第一条消息</div>' +
        '<div class="chat-empty-text">智能体会在回复后异步提炼长期记忆与图记忆，右侧工作台会同步更新。</div>' +
        '</div>';
    }

    refs.chatScroll.innerHTML = html;
    var nextScroller = refs.chatScroll;
    if (nextScroller) {
      if (opts.scrollBottom || atBottom) {
        nextScroller.scrollTop = nextScroller.scrollHeight;
      }
    }
    updateComposer();
  }

  function hasMessageText(messages, role, text) {
    var wanted = String(text == null ? '' : text).trim();
    if (!wanted) return false;
    for (var i = messages.length - 1; i >= 0; i--) {
      var message = messages[i] || {};
      var messageRole = message.role === 'user' ? 'customer' : message.role;
      if (messageRole === role && String(message.text == null ? '' : message.text).trim() === wanted) {
        return true;
      }
    }
    return false;
  }

  function renderMessage(message) {
    message = message || {};
    var role = message.role === 'user' ? 'customer' : message.role;
    var pendingClass = message.pending ? ' msg-pending' : '';
    var ts = message.ts ? String(message.ts) : '';

    if (role === 'system') {
      return '<div class="chat-top-note">' + esc(message.text) + '</div>';
    }

    if (role === 'agent') {
      var citeParts = [];
      if (num(message.cites) > 0) citeParts.push(num(message.cites) + ' 条长期记忆');
      if (num(message.graphCites) > 0) citeParts.push(num(message.graphCites) + ' 个图关联');
      var citeLine = citeParts.length
        ? '<div class="cite-line">本次回答参考了 ' + citeParts.join(' · ') + '</div>'
        : '';

      var processed = message.processed;
      var processedLine = '';
      if (processed && typeof processed === 'object') {
        var parts = [];
        if (num(processed.added) > 0) parts.push('已提炼 ' + num(processed.added) + ' 条长期记忆');
        if (num(processed.graph) > 0) parts.push(num(processed.graph) + ' 个图对象');
        if (num(processed.updated) > 0) parts.push('更新 ' + num(processed.updated) + ' 条');
        if (num(processed.deleted) > 0) parts.push('删除 ' + num(processed.deleted) + ' 条');
        if (parts.length) {
          processedLine = '<div class="processed-line">' + esc(parts.join(' · ')) + ' → 已写入记忆库</div>';
        }
      }

      return '<div class="msg msg-agent' + pendingClass + '">' +
        '<div class="avatar avatar-agent">智</div>' +
        '<div class="msg-body">' +
          '<div class="msg-name">信贷智能体' + (message.pending ? ' · 生成中' : '') + '</div>' +
          '<div class="bubble bubble-agent">' + esc(message.text) + '</div>' +
          (message.pending ? '' : citeLine) +
          (message.pending ? '' : processedLine) +
          (ts && !message.pending ? '<div class="msg-meta">' + esc(ts) + '</div>' : '') +
        '</div>' +
        '</div>';
    }

    return '<div class="msg msg-customer' + pendingClass + '">' +
      '<div class="bubble bubble-customer">' + esc(message.text) + '</div>' +
      '<div class="msg-meta">客户' + (ts ? ' · ' + esc(ts) : '') + (message.pending ? ' · 发送中' : '') + '</div>' +
      '</div>';
  }

  function updateComposer() {
    var canSend = !!app.sessionId && !app.viewingHistory && !app.sending;
    refs.input.disabled = !canSend;
    refs.sendBtn.disabled = !canSend || !refs.input.value.trim();
    refs.input.placeholder = app.viewingHistory
      ? '历史会话只读'
      : (!app.sessionId ? '请先新建会话' : '以客户身份回复…');

    var statusText = '';
    if (app.sending) {
      statusText = '正在发送客户消息并等待模型回复…';
    } else if (app.processing || isPipelineRunning()) {
      statusText = '智能体已回复 · 记忆加工中（0.1.16 同步抽取由应用异步触发），右侧实时刷新';
    }
    refs.composerStatus.hidden = !statusText;
    refs.composerStatusText.textContent = statusText;
  }

  /* ================= 右侧记忆工作台 ================= */

  function ensureGraph(snapshot) {
    var graph = (snapshot && snapshot.graph) || {};
    return {
      entities: safeArray(graph.entities),
      relations: safeArray(graph.relations),
      episodes: safeArray(graph.episodes)
    };
  }

  function isPipelineRunning() {
    return !!(app.snapshot && app.snapshot.pipeline && app.snapshot.pipeline.running);
  }

  function renderMemory() {
    var snapshot = app.snapshot || {};
    var items = collectMemoryItems(snapshot);
    var graph = ensureGraph(snapshot);
    var runs = safeArray(snapshot.pipeline && snapshot.pipeline.runs);
    var messages = safeArray(snapshot.messages);

    refs.memoryCount.textContent = String(items.length);

    var graphStore = (app.bootstrap && app.bootstrap.graphStore) || 'InMemoryGraphStore（演示）';
    var storageDesc = app.bootstrap
      ? '长期记忆：本机 SQLite；图记忆：' + graphStore
      : (app.bootstrapError ? '未连接后端，无法读取记忆存储状态' : '正在连接后端…');

    refs.storageCard.innerHTML =
      '<div class="storage-icon">' +
        '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" ' +
        'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
          '<ellipse cx="12" cy="5" rx="7" ry="3"></ellipse>' +
          '<path d="M5 5v6c0 1.7 3.1 3 7 3s7-1.3 7-3V5"></path>' +
          '<path d="M5 11v6c0 1.7 3.1 3 7 3s7-1.3 7-3v-6"></path>' +
        '</svg>' +
      '</div>' +
      '<div class="storage-copy"><b>记忆存储</b><span title="' + esc(storageDesc) + '">' + esc(storageDesc) + '</span></div>' +
      '<div class="storage-stats">' +
        '<span class="stat"><b>' + messages.length + '</b>条消息</span>' +
        '<span class="stat"><b>' + items.length + '</b>条记忆</span>' +
        '<span class="stat"><b>' + (graph.entities.length + graph.relations.length + graph.episodes.length) + '</b>个图对象</span>' +
      '</div>';

    var tabs = [
      { id: 'longterm', label: '长期记忆', count: items.length },
      { id: 'graph', label: '图记忆', count: graph.entities.length },
      { id: 'pipeline', label: '加工流程', count: runs.length }
    ];
    refs.memoryTabs.innerHTML = tabs.map(function (tab) {
      return '<button class="memory-tab' + (app.ui.tab === tab.id ? ' is-active' : '') + '"' +
        ' type="button" role="tab" aria-selected="' + (app.ui.tab === tab.id ? 'true' : 'false') + '"' +
        ' data-act="tab" data-tab="' + tab.id + '">' +
        esc(tab.label) + '<b>' + tab.count + '</b></button>';
    }).join('');

    var previousScroll = 0;
    var previousScroller = refs.tabContent.querySelector('.memory-scroll');
    if (previousScroller) previousScroll = previousScroller.scrollTop;

    var content = '';
    if (app.ui.tab === 'graph') content = renderGraphTab();
    else if (app.ui.tab === 'pipeline') content = renderPipelineTab();
    else content = renderLongTermTab(items);

    refs.tabContent.innerHTML = content;
    var nextScroller = refs.tabContent.querySelector('.memory-scroll');
    if (nextScroller) nextScroller.scrollTop = previousScroll;
  }

  function renderTabFooter(activity, linkLabel) {
    return '<footer class="memory-foot">' +
      '<span class="activity">' + esc(activity) + '</span>' +
      '<button class="link-btn" type="button" data-act="open-settings">' + esc(linkLabel || '模型设置') + '</button>' +
      '</footer>';
  }

  function activityText() {
    if (app.sending) return '正在发送客户消息…';
    var pipeline = app.snapshot && app.snapshot.pipeline;
    if (pipeline && pipeline.running) return '记忆加工中 · 完成后自动刷新';

    var runs = safeArray(pipeline && pipeline.runs);
    if (runs.length) {
      var stats = runs[0].stats || {};
      var parts = [];
      if (num(stats.memoriesAdded) > 0) parts.push('新增 ' + num(stats.memoriesAdded) + ' 条记忆');
      if (num(stats.memoriesUpdated) > 0) parts.push('更新 ' + num(stats.memoriesUpdated) + ' 条');
      if (num(stats.memoriesDeleted) > 0) parts.push('删除 ' + num(stats.memoriesDeleted) + ' 条');
      var graphAdded = num(stats.graphEntitiesAdded) + num(stats.graphRelationsAdded) + num(stats.graphEpisodesAdded);
      if (graphAdded > 0) parts.push('图对象 +' + graphAdded);
      if (parts.length) return '最近一次加工：' + parts.join(' · ');
      return '最近一次加工：无新增记忆';
    }
    if (!app.snapshot) return app.bootstrapError ? '未连接后端' : '正在加载…';
    return '对话后自动提取记忆';
  }

  /* ---------- 长期记忆 tab ---------- */

  function renderLongTermTab(items) {
    var html = '<div class="memory-tab-content">' +
      '<div class="memory-scroll longterm-scroll">' +
      renderRecallCard(app.snapshot && app.snapshot.recall) +
      renderMemoryGroups(items) +
      '</div>' +
      renderTabFooter(activityText()) +
      '</div>';
    return html;
  }

  function renderRecallCard(recall) {
    if (!recall || typeof recall !== 'object') return '';
    var channels = recall.channels || {};
    var entries = safeArray(recall.entries);
    var vectorCount = num(channels.vector);
    var kvCount = num(channels.kv);
    var graphCount = num(channels.graph);
    var total = entries.length || (vectorCount + kvCount + graphCount);

    var channelChips =
      '<span class="channel-chip channel-vector"><i></i>向量 ' + vectorCount + '</span>' +
      '<span class="channel-chip channel-kv"><i></i>KV ' + kvCount + '</span>' +
      '<span class="channel-chip channel-graph"><i></i>图记忆 ' + graphCount + '</span>';

    var listHtml;
    if (entries.length) {
      listHtml = '<div class="multi-list">' + entries.map(function (entry) {
        entry = entry || {};
        var key = sourceKey(entry.source);
        return '<div class="multi-entry">' +
          '<span class="source-tag source-' + key + '">' + esc(entry.source == null ? '其他' : entry.source) + '</span>' +
          '<span class="multi-label" title="' + esc(entry.label) + '">' + esc(formatRecallLabel(entry.source, entry.label)) + '</span>' +
          '</div>';
      }).join('') + '</div>';
    } else {
      listHtml = '<div class="recall-empty">本轮没有召回结果（首次对话时正常）。</div>';
    }

    return '<section class="recall-card multi-recall-card">' +
      '<div class="recall-head">' +
        '<span class="recall-title">本轮召回 · 多路融合</span>' +
        '<span class="recall-count">' + total + ' 条</span>' +
        '<span class="recall-sub">向量 / KV / 图记忆三路结果，由应用层融合</span>' +
      '</div>' +
      (recall.query ? '<div class="recall-query"><b>检索：</b>' + esc(recall.query) + '</div>' : '') +
      '<div class="recall-channels">' + channelChips + '</div>' +
      listHtml +
      '</section>';
  }

  function renderMemoryGroups(items) {
    var groups = {};
    items.forEach(function (item) {
      if (!groups[item.layer]) groups[item.layer] = [];
      groups[item.layer].push(item);
    });

    var html = '';
    LAYERS.forEach(function (layer) {
      var layerItems = groups[layer.id];
      if (!layerItems || !layerItems.length) return;
      var ordered = layerItems.slice().reverse();

      html += '<section class="memory-group">' +
        '<div class="group-head">' +
          '<span class="group-dot" style="background:' + layer.color + '"></span>' +
          '<span class="group-name">' + esc(layer.name) + '</span>' +
          '<span class="group-count">' + ordered.length + '</span>' +
          '<span class="group-storage"><span class="storage-pin"></span>存于 ' + esc(layer.storage) + '</span>' +
        '</div>' +
        '<div class="group-hint">' + esc(layer.hint) + '</div>' +
        '<div class="group-items">' + ordered.map(function (item) {
          return renderMemoryItem(item, layer);
        }).join('') + '</div>' +
        '</section>';
    });

    if (!html) {
      if (app.bootstrapError) {
        return '<div class="memory-empty">' +
          '<div class="memory-empty-icon">!</div>' +
          '<div class="memory-empty-title">无法读取记忆</div>' +
          '<div class="memory-empty-text">' + esc(app.bootstrapError.message) + '</div>' +
          '<button class="primary-btn" type="button" data-act="retry-bootstrap">重新连接</button>' +
          '</div>';
      }
      if (!app.snapshot) {
        return '<div class="memory-empty">' +
          '<div class="memory-empty-icon">◌</div>' +
          '<div class="memory-empty-title">正在加载记忆…</div>' +
          '<div class="memory-empty-text">从后端读取长期记忆、图记忆与加工流程状态。</div>' +
          '</div>';
      }
      return '<div class="memory-empty">' +
        '<div class="memory-empty-icon">✦</div>' +
        '<div class="memory-empty-title">对话开始后，记忆会出现在这里</div>' +
        '<div class="memory-empty-text">智能体会把客户说的重要信息按类型记住：用户画像、语义记忆、情景记忆、摘要记忆和变量记忆。</div>' +
        '</div>';
    }
    return html;
  }

  function renderMemoryItem(item, layer) {
    var isNew = !!app.flash.new[item.id];
    var isUpdated = !!app.flash.updated[item.id] || item.version > 1;
    var recalled = isRecalledMemory(item.content);
    var oldValue = item.oldValue || app.memPrev[item.id] || '';

    var badges = [];
    if (isUpdated) {
      badges.push('<span class="badge badge-update">' + (app.flash.updated[item.id] ? '刚刚更新' : '已更新') + '</span>');
    } else if (isNew) {
      badges.push('<span class="badge badge-new">' + (layer.id === 'summary' ? '刚刚生成' : '刚刚记住') + '</span>');
    }
    if (recalled) badges.push('<span class="badge badge-recall">本轮用到</span>');

    var classes = 'mem-card' +
      (isNew || app.flash.updated[item.id] ? ' is-new' : '') +
      (recalled ? ' is-recalled' : '');

    return '<div class="' + classes + '">' +
      '<div class="mem-card-main">' +
        '<div class="mem-text">' + esc(item.content) + '</div>' +
        (badges.length ? '<div class="mem-badges">' + badges.join('') + '</div>' : '') +
      '</div>' +
      (oldValue ? '<div class="mem-old">原：' + esc(oldValue) + '</div>' : '') +
      '</div>';
  }

  /* ---------- 图记忆 tab ---------- */

  function graphTypeOf(type) {
    var raw = String(type == null ? '' : type).trim();
    if (!raw) return { key: 'unknown', name: '实体', color: '#64748b' };
    var key = raw.toLowerCase();
    if (!GRAPH_TYPES[key]) {
      key = GRAPH_TYPE_ALIASES[raw] || key;
    }
    if (GRAPH_TYPES[key]) {
      return { key: key, name: GRAPH_TYPES[key].name, color: GRAPH_TYPES[key].color };
    }
    return {
      key: key,
      name: raw,
      color: GRAPH_COLORS[hashCode(key) % GRAPH_COLORS.length]
    };
  }

  function remainingGraphLabel(type) {
    return graphTypeOf(type).name;
  }

  function graphRecallSets() {
    var hits = safeArray(app.snapshot && app.snapshot.graphRecall && app.snapshot.graphRecall.hits);
    var entityIds = {};
    var entityLabels = {};
    var relationIds = {};
    var relationLabels = {};
    hits.forEach(function (hit) {
      hit = hit || {};
      var kind = String(hit.kind == null ? '' : hit.kind).toLowerCase();
      var isRelation = kind === 'relation' || kind === '关系';
      var id = hit.id != null ? String(hit.id) : '';
      var label = hit.label != null ? String(hit.label) : '';
      if (isRelation) {
        if (id) relationIds[id] = true;
        if (label) relationLabels[label] = true;
      } else {
        if (id) entityIds[id] = true;
        if (label) entityLabels[label] = true;
      }
    });
    return {
      entityIds: entityIds,
      entityLabels: entityLabels,
      relationIds: relationIds,
      relationLabels: relationLabels
    };
  }

  function layoutGraph(graph, shownEntities) {
    var positions = {};
    if (!shownEntities.length) return positions;

    var degree = {};
    shownEntities.forEach(function (entity) { degree[String(entity.id)] = 0; });
    graph.relations.forEach(function (relation) {
      var source = String(relation.source);
      var target = String(relation.target);
      if (degree[source] != null) degree[source] += 1;
      if (degree[target] != null) degree[target] += 1;
    });

    var center = null;
    for (var i = 0; i < shownEntities.length; i++) {
      if (graphTypeOf(shownEntities[i].type).key === 'person') { center = shownEntities[i]; break; }
    }
    if (!center) {
      shownEntities.forEach(function (entity) {
        if (!center || degree[String(entity.id)] > degree[String(center.id)]) center = entity;
      });
    }
    center = center || shownEntities[0];
    positions[String(center.id)] = { x: 240, y: 150, center: true };

    var priority = { product: 0, preference: 1, plan: 2, event: 3, company: 4, organization: 4, location: 5 };
    var others = shownEntities.filter(function (entity) {
      return String(entity.id) !== String(center.id);
    }).slice();
    others.sort(function (a, b) {
      var pa = priority[graphTypeOf(a.type).key];
      var pb = priority[graphTypeOf(b.type).key];
      pa = pa == null ? 9 : pa;
      pb = pb == null ? 9 : pb;
      if (pa !== pb) return pa - pb;
      return degree[String(b.id)] - degree[String(a.id)];
    });

    if (others.length <= 8) {
      others.forEach(function (entity, index) {
        var angle = -Math.PI / 2 + index * (2 * Math.PI / Math.max(others.length, 1));
        positions[String(entity.id)] = { x: 240 + Math.cos(angle) * 104, y: 150 + Math.sin(angle) * 104, center: false };
      });
    } else {
      var innerCount = 6;
      var outerCount = others.length - innerCount;
      others.forEach(function (entity, index) {
        var isInner = index < innerCount;
        var idx = isInner ? index : index - innerCount;
        var count = isInner ? innerCount : outerCount;
        var radius = isInner ? 96 : 134;
        var offset = isInner ? -Math.PI / 2 : -Math.PI / 2 + Math.PI / Math.max(count, 1);
        var angle = offset + idx * (2 * Math.PI / Math.max(count, 1));
        positions[String(entity.id)] = {
          x: 240 + Math.cos(angle) * radius,
          y: 150 + Math.sin(angle) * radius,
          center: false
        };
      });
    }
    return positions;
  }

  function renderGraphSvg(graph, shownEntities) {
    var positions = layoutGraph(graph, shownEntities);
    var recall = graphRecallSets();

    var edgesHtml = graph.relations.map(function (relation) {
      var relationId = relation.id != null ? String(relation.id) : '';
      var from = positions[String(relation.source)];
      var to = positions[String(relation.target)];
      if (!from || !to) return '';

      var isRecalledEdge = !!recall.relationIds[relationId] ||
        !!recall.relationLabels[String(relation.label || '')] ||
        !!recall.entityIds[String(relation.source)] || !!recall.entityLabels[findEntityLabel(graph, relation.source)] ||
        !!recall.entityIds[String(relation.target)] || !!recall.entityLabels[findEntityLabel(graph, relation.target)];

      var midX = (from.x + to.x) / 2;
      var midY = (from.y + to.y) / 2;
      var dx = to.x - from.x;
      var dy = to.y - from.y;
      var length = Math.sqrt(dx * dx + dy * dy) || 1;
      var offsetX = (-dy / length) * 7;
      var offsetY = (dx / length) * 7;

      return '<g class="g-edge' + (isRecalledEdge ? ' is-recalled' : '') + '">' +
        '<line x1="' + from.x.toFixed(1) + '" y1="' + from.y.toFixed(1) + '" x2="' + to.x.toFixed(1) +
          '" y2="' + to.y.toFixed(1) + '" marker-end="url(#graph-arrow)"></line>' +
        '<text class="g-edge-label" x="' + (midX + offsetX).toFixed(1) + '" y="' + (midY + offsetY).toFixed(1) + '">' +
          esc(truncate(relation.label || '', 8)) + '</text>' +
        '</g>';
    }).join('');

    var nodesHtml = shownEntities.map(function (entity) {
      var position = positions[String(entity.id)];
      if (!position) return '';
      var type = graphTypeOf(entity.type);
      var selected = app.ui.graphSelected != null && String(app.ui.graphSelected) === String(entity.id);
      var isRecalledNode = !!recall.entityIds[String(entity.id)] || !!recall.entityLabels[String(entity.label)];
      var label = truncate(entity.label, position.center ? 6 : 5);
      var width = position.center ? 86 : Math.max(50, Math.min(label.length, 5) * 12 + 18);
      var height = position.center ? 34 : 27;

      return '<g class="g-node' + (selected ? ' is-selected' : '') + (isRecalledNode ? ' is-recalled' : '') + '"' +
        ' data-act="graph-node" data-id="' + esc(entity.id) + '" transform="translate(' +
        position.x.toFixed(1) + ',' + position.y.toFixed(1) + ')" role="button" tabindex="0">' +
        '<rect class="g-node-bg" x="' + (-width / 2) + '" y="' + (-height / 2) + '" width="' + width +
          '" height="' + height + '" rx="9" fill="' + type.color + '"></rect>' +
        '<text class="g-node-text" x="0" y="4" text-anchor="middle">' + esc(label) + '</text>' +
        '<title>' + esc(entity.label) + ' · ' + esc(type.name) + '</title>' +
        '</g>';
    }).join('');

    return '<svg class="graph-svg" viewBox="0 0 480 300" role="img" aria-label="图记忆实体关系图">' +
      '<defs><marker id="graph-arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="5" markerHeight="5" ' +
      'orient="auto-start-reverse"><path d="M0 0 L10 5 L0 10 z" fill="#94a3b8"></path></marker></defs>' +
      edgesHtml + nodesHtml +
      '</svg>';
  }

  function findEntityLabel(graph, id) {
    var key = String(id);
    for (var i = 0; i < graph.entities.length; i++) {
      if (String(graph.entities[i].id) === key) return String(graph.entities[i].label || '');
    }
    return '';
  }

  function findEntity(graph, id) {
    var key = String(id);
    for (var i = 0; i < graph.entities.length; i++) {
      if (String(graph.entities[i].id) === key) return graph.entities[i];
    }
    return null;
  }

  function renderGraphLegend(graph, shownCount, totalCount) {
    var usedTypes = {};
    graph.entities.forEach(function (entity) {
      var type = graphTypeOf(entity.type);
      usedTypes[type.key] = type;
    });
    var items = Object.keys(usedTypes).map(function (key) {
      var type = usedTypes[key];
      return '<span class="legend-item"><i style="background:' + type.color + '"></i>' + esc(type.name) + '</span>';
    }).join('');
    var hint = '点击实体查看关联关系';
    if (totalCount > shownCount) {
      hint = '仅展示前 ' + shownCount + ' 个实体（共 ' + totalCount + ' 个）· ' + hint;
    }
    return '<div class="graph-legend">' + items + '<span class="legend-hint">' + esc(hint) + '</span></div>';
  }

  function renderGraphDetail(graph) {
    if (app.ui.graphSelected == null) return '';
    var entity = findEntity(graph, app.ui.graphSelected);
    if (!entity) return '';
    var type = graphTypeOf(entity.type);

    var relationChips = graph.relations.map(function (relation) {
      var isSource = String(relation.source) === String(entity.id);
      var isTarget = String(relation.target) === String(entity.id);
      if (!isSource && !isTarget) return '';
      var otherId = isSource ? relation.target : relation.source;
      var other = findEntity(graph, otherId);
      return '<span class="relation-chip">' + (isSource ? '→ ' : '← ') + esc(relation.label || '关联') + ' ' +
        esc(other ? other.label : otherId) + '</span>';
    }).join('');

    return '<div class="graph-detail">' +
      '<span class="detail-type" style="background:' + type.color + '1a;color:' + type.color + '">' +
        esc(type.name) + '</span>' +
      '<b>' + esc(entity.label) + '</b>' +
      (relationChips ? '<div class="relation-chips">' + relationChips + '</div>'
        : '<div class="relation-chips"><span class="relation-chip">暂无关联关系</span></div>') +
      '</div>';
  }

  function renderGraphRecallCard() {
    var graphRecall = app.snapshot && app.snapshot.graphRecall;
    if (!graphRecall || typeof graphRecall !== 'object') return '';
    var hits = safeArray(graphRecall.hits);

    var body;
    if (hits.length) {
      body = '<div class="graph-hits">' + hits.map(function (hit) {
        hit = hit || {};
        var kindRaw = String(hit.kind == null ? '' : hit.kind).toLowerCase();
        var kindName = '实体';
        if (kindRaw === 'relation' || kindRaw === '关系') kindName = '关系';
        else if (kindRaw === 'episode' || kindRaw === '情节') kindName = '情节';
        return '<span class="graph-hit">' +
          '<span class="hit-kind">' + esc(kindName) + '</span>' +
          '<span class="hit-label" title="' + esc(hit.label) + '">' + esc(hit.label) + '</span>' +
          (hit.type ? '<span class="hit-type">' + esc(remainingGraphLabel(hit.type)) + '</span>' : '') +
          '</span>';
      }).join('') + '</div>';
    } else {
      body = '<div class="recall-empty">本轮没有图召回结果（首次对话时正常）。</div>';
    }

    return '<section class="graph-recall-card">' +
      '<div class="recall-head">' +
        '<span class="recall-title">本轮图召回</span>' +
        '<span class="recall-count">' + hits.length + ' 个</span>' +
        '<span class="recall-sub">来自图记忆（进程内）</span>' +
      '</div>' + body + '</section>';
  }

  function renderEpisodes(episodes) {
    if (!episodes.length) return '';
    return '<section class="episodes-card">' +
      '<div class="mini-title">情节（Episodes）</div>' +
      episodes.map(function (episode) {
        return '<div class="episode-row"><span class="episode-dot"></span>' +
          esc(episode.label != null ? episode.label : episode.id) + '</div>';
      }).join('') +
      '</section>';
  }

  function renderGraphTab() {
    var snapshot = app.snapshot;
    var graph = ensureGraph(snapshot);
    var graphStore = (app.bootstrap && app.bootstrap.graphStore) || 'InMemoryGraphStore（演示，重启后重建）';

    var content;
    if (!snapshot && app.bootstrapError) {
      content = '<div class="memory-empty">' +
        '<div class="memory-empty-icon">!</div>' +
        '<div class="memory-empty-title">无法读取图记忆</div>' +
        '<div class="memory-empty-text">' + esc(app.bootstrapError.message) + '</div>' +
        '<button class="primary-btn" type="button" data-act="retry-bootstrap">重新连接</button>' +
        '</div>';
    } else if (!snapshot) {
      content = '<div class="memory-empty">' +
        '<div class="memory-empty-icon">◌</div>' +
        '<div class="memory-empty-title">正在加载图记忆…</div>' +
        '<div class="memory-empty-text">图记忆使用 InMemoryGraphStore（演示），由后端返回实体、关系与情节。</div>' +
        '</div>';
    } else if (!graph.entities.length) {
      content = '<div class="memory-empty">' +
        '<div class="memory-empty-icon">◌</div>' +
        '<div class="memory-empty-title">还没有图记忆</div>' +
        '<div class="memory-empty-text">完成一轮咨询后，实体与关系会写入图存储，并在这里可视化。</div>' +
        '</div>';
    } else {
      var shownEntities = graph.entities.slice(0, MAX_GRAPH_NODES);
      var selected = app.ui.graphSelected != null ? findEntity(graph, app.ui.graphSelected) : null;

      content = renderGraphRecallCard() +
        '<section class="graph-card">' +
          '<div class="graph-card-head">' +
            '<span>实体关系图</span>' +
            '<span class="graph-store"><i></i>图记忆 · InMemory（演示）</span>' +
          '</div>' +
          renderGraphSvg(graph, shownEntities) +
          renderGraphLegend(graph, shownEntities.length, graph.entities.length) +
          (selected ? renderGraphDetail(graph) : '') +
        '</section>' +
        renderEpisodes(graph.episodes);
    }

    return '<div class="memory-tab-content">' +
      '<div class="memory-scroll graph-scroll">' + content + '</div>' +
      renderTabFooter('图存储：' + graphStore + ' · 进程内（Milvus 待 0.1.17）') +
      '</div>';
  }

  /* ---------- 加工流程 tab ---------- */

  function runsOfPipeline() {
    return safeArray(app.snapshot && app.snapshot.pipeline && app.snapshot.pipeline.runs);
  }

  function stageDetail(stageKey, run, isRunning) {
    var stats = run.stats || {};
    if (stageKey === 'ingest') {
      return (run.trigger || '对话后异步触发') + (run.at ? ' · ' + run.at : '');
    }
    if (stageKey === 'analyze') {
      return 'LLM 调用 ' + num(stats.llmCalls) + ' 次';
    }
    if (stageKey === 'extract') {
      return '长期记忆 +' + num(stats.memoriesAdded) +
        ' · 图实体 +' + num(stats.graphEntitiesAdded) +
        ' / 关系 +' + num(stats.graphRelationsAdded) +
        ' / 情节 +' + num(stats.graphEpisodesAdded);
    }
    if (stageKey === 'merge') {
      return '更新 ' + num(stats.memoriesUpdated) + ' · 删除 ' + num(stats.memoriesDeleted) + ' · 合并冲突';
    }
    if (stageKey === 'write') {
      var targets = safeArray(stats.writeTargets);
      return targets.length ? targets.join(' + ') : '本机 SQLite / 向量索引 / InMemory 图存储';
    }
    return isRunning ? '完成后可被下一轮召回' : '已可被下一轮召回';
  }

  /* 后端在 run.stages 中给出每一步的实时状态（pending/running/done/error）与 detail；
   * 没有该字段时退回固定的六阶段视图。 */
  function stagesOfRun(run) {
    var stages = safeArray(run.stages);
    if (stages.length) return stages;
    return STAGES.map(function (stage) {
      return { key: stage.key, label: stage.label, detail: '', status: '' };
    });
  }

  function renderPipelineStages(run, running) {
    var stages = stagesOfRun(run);
    var runFailed = String(run.status || '').toLowerCase() === 'error' ||
      String(run.status || '').toLowerCase() === 'failed';

    return stages.map(function (stage, index) {
      var status = String(stage.status == null ? '' : stage.status).toLowerCase();
      var isError = status === 'error' || (!status && runFailed);
      var isRunning = status === 'running' || (running && !status && index === 0);
      var isDone = status === 'done' || (!status && !running && !isError && !runFailed);
      var stageKey = stage.key || (STAGES[index] && STAGES[index].key) || '';
      var label = stage.label || (STAGES[index] && STAGES[index].label) || stageKey;
      var detail = stage.detail || stageDetail(stageKey, run, isRunning);

      var stateClass = isError ? ' is-error' : isRunning ? ' is-running' : isDone ? ' is-done' : ' is-pending';
      var mark;
      if (isError) mark = '<span class="stage-check is-error">!</span>';
      else if (isRunning) mark = '<span class="stage-check is-running">…</span>';
      else if (isDone) mark = '<span class="stage-check">✓</span>';
      else mark = '<span class="stage-check is-pending">·</span>';

      return '<div class="pipeline-stage">' +
        '<span class="stage-index' + stateClass + '">' + (index + 1) + '</span>' +
        '<div class="stage-copy"><b>' + esc(label) + '</b>' +
          '<span title="' + esc(detail) + '">' + esc(detail) + '</span></div>' +
        mark +
        '</div>';
    }).join('');
  }

  function renderPipelineRunCard(run, running) {
    var stats = run.stats || {};
    var status = String(run.status || '').toLowerCase();
    var isRunning = running || status === 'running';
    var isFailed = status === 'error' || status === 'failed';
    var graphObjects = num(stats.graphEntitiesAdded) + num(stats.graphRelationsAdded) + num(stats.graphEpisodesAdded);

    var liveTag = '';
    if (isRunning) liveTag = '<span class="pipeline-live"><span class="pulse-dot"></span>加工中</span>';
    else if (isFailed) liveTag = '<span class="pipeline-live is-error">加工失败</span>';

    return '<section class="pipeline-card">' +
      '<div class="pipeline-hero">' +
        '<div><span class="pipeline-eyebrow">最近一次记忆加工</span>' +
          '<b>' + esc(run.trigger || '对话后异步触发') + '</b></div>' +
        '<span class="pipeline-time">' + esc(run.at || '') + liveTag + '</span>' +
      '</div>' +
      (run.error ? '<div class="pipeline-error">错误：' + esc(run.error) + '</div>' : '') +
      '<div class="pipeline-stats">' +
        '<span class="' + (isRunning ? 'is-live' : '') + '"><b>' + num(stats.llmCalls) + '</b>次 LLM</span>' +
        '<span class="' + (isRunning ? 'is-live' : '') + '"><b>' + num(stats.memoriesAdded) + '</b>条新记忆</span>' +
        '<span class="' + (isRunning ? 'is-live' : '') + '"><b>' + graphObjects + '</b>个图对象</span>' +
      '</div>' +
      '<div class="pipeline-stages">' + renderPipelineStages(run, isRunning) + '</div>' +
      '</section>';
  }

  function renderProcessingSample(run, running) {
    var sample = run.sample || {};
    var input = safeArray(sample.input);
    var outputs = sample.outputs || {};
    var memories = safeArray(outputs.memories);
    var graphItems = safeArray(outputs.graph);

    if (!input.length && !memories.length && !graphItems.length) {
      if (!running) return '';
      return '<section class="pipeline-sample is-live">' +
        '<div class="mini-title">这次加工：会话上下文 → 长期记忆</div>' +
        '<div class="sample-empty">加工中，完成后展示输入上下文与产出。</div>' +
        '</section>';
    }

    var contextHtml = input.map(function (line) {
      return '<div class="sample-context-line">' + esc(line) + '</div>';
    }).join('');

    var memoryChips = memories.map(function (text) {
      return '<span class="sample-chip memory" title="' + esc(text) + '">' + esc(text) + '</span>';
    }).join('');
    var graphChips = graphItems.map(function (text) {
      return '<span class="sample-chip graph" title="' + esc(text) + '">' + esc(text) + '</span>';
    }).join('');
    var removedChips = safeArray(outputs.removed).map(function (text) {
      return '<span class="sample-chip removed" title="' + esc(text) + '">移除：' + esc(text) + '</span>';
    }).join('');
    var outputsHtml = memoryChips + graphChips + removedChips;
    if (!outputsHtml) {
      outputsHtml = '<span class="sample-empty">本轮无新增长期记忆</span>';
    }

    return '<section class="pipeline-sample' + (running ? ' is-live' : '') + '">' +
      '<div class="mini-title">这次加工：会话上下文 → 长期记忆</div>' +
      '<div class="sample-label">输入 · 最近会话上下文（节选）</div>' +
      (contextHtml ? '<div class="sample-context">' + contextHtml + '</div>'
        : '<div class="sample-empty">暂无上下文</div>') +
      '<div class="sample-arrow">LLM 总结提炼 ↓</div>' +
      '<div class="sample-label">产出 · 长期记忆 / 图对象</div>' +
      '<div class="sample-chips">' + outputsHtml + '</div>' +
      '</section>';
  }

  function renderPipelineHistory(runs) {
    if (runs.length <= 1) return '';
    return '<section class="pipeline-history">' +
      '<div class="mini-title">加工记录</div>' +
      runs.slice(0, 6).map(function (run) {
        var stats = run.stats || {};
        var status = String(run.status || '').toLowerCase() === 'running' ? 'running'
          : String(run.status || '').toLowerCase() === 'failed' ? 'failed' : 'done';
        var statusText = status === 'running' ? '运行中' : (status === 'failed' ? '失败' : '完成');
        var graphObjects = num(stats.graphEntitiesAdded) + num(stats.graphRelationsAdded) + num(stats.graphEpisodesAdded);
        return '<div class="pipeline-run">' +
          '<span class="run-dot' + (status === 'running' ? ' is-running' : '') + '"></span>' +
          '<div class="run-main"><b>' + esc(run.trigger || '记忆加工') + '</b>' +
            '<span>' + esc(run.at || '') + ' · 记忆 +' + num(stats.memoriesAdded) +
            ' · 图对象 +' + graphObjects + '</span></div>' +
          '<span class="run-status is-' + status + '">' + statusText + '</span>' +
          '</div>';
      }).join('') +
      '</section>';
  }

  function renderModelSummary() {
    var b = app.bootstrap || {};
    var chat = b.chat || {};
    var memory = b.memory || {};
    var graphStore = b.graphStore || 'InMemoryGraphStore（演示）';

    return '<section class="model-summary">' +
      '<div class="mini-title">模型与存储</div>' +
      '<div class="model-row"><span>对话模型</span><b title="' + esc(chat.model) + '">' + esc(chat.model || '未配置') + '</b></div>' +
      '<div class="model-row"><span>记忆模型</span><b title="' + esc(memory.model) + '">' + esc(memory.model || '未配置') + '</b></div>' +
      '<div class="model-row"><span>Embedding</span><b title="' + esc(memory.embeddingModel) + '">' + esc(memory.embeddingModel || '未配置') + '</b></div>' +
      '<div class="model-row"><span>图存储</span><b title="' + esc(graphStore) + '">' + esc(graphStore) + '</b></div>' +
      '<button class="ghost-btn" type="button" data-act="open-settings">修改模型设置</button>' +
      '</section>';
  }

  function renderPipelineTab() {
    var runs = runsOfPipeline();
    var running = isPipelineRunning();
    var latest = runs.length ? runs[0] : null;
    var content = '';

    if (!app.snapshot) {
      if (app.bootstrapError) {
        content = '<div class="memory-empty">' +
          '<div class="memory-empty-icon">!</div>' +
          '<div class="memory-empty-title">无法读取加工流程</div>' +
          '<div class="memory-empty-text">' + esc(app.bootstrapError.message) + '</div>' +
          '<button class="primary-btn" type="button" data-act="retry-bootstrap">重新连接</button>' +
          '</div>';
      } else {
        content = '<div class="memory-empty">' +
          '<div class="memory-empty-icon">◌</div>' +
          '<div class="memory-empty-title">正在加载加工流程…</div>' +
          '<div class="memory-empty-text">对话后由应用侧异步触发记忆加工，过程会记录在这里。</div>' +
          '</div>';
      }
    } else if (!latest) {
      content = '<div class="memory-empty">' +
        '<div class="memory-empty-icon">◌</div>' +
        '<div class="memory-empty-title">还没有加工记录</div>' +
        '<div class="memory-empty-text">客户发来消息后，记忆加工流水线会依次记录每个阶段、产出与统计。</div>' +
        '</div>';
    } else {
      content = renderPipelineRunCard(latest, running) +
        renderProcessingSample(latest, running) +
        renderPipelineHistory(runs);
    }

    content += '<div class="pipeline-note">' +
      'jiuwen 0.1.16 的 <code>addMessages</code> / <code>addMemory</code> 是同步调用；样例在对话结束后由应用侧异步触发，' +
      '因此呈现为一次「离线」加工流程。抽取使用真实 LLM，写入本机 SQLite、向量索引与 InMemory 图存储（Milvus 待 0.1.17）。' +
      '</div>' +
      renderModelSummary();

    var footerModel = (app.bootstrap && app.bootstrap.memory && app.bootstrap.memory.model) || '未配置';
    return '<div class="memory-tab-content">' +
      '<div class="memory-scroll pipeline-scroll">' + content + '</div>' +
      renderTabFooter('记忆模型：' + footerModel) +
      '</div>';
  }

  /* ================= 模型设置弹层 ================= */

  function openSettings() {
    app.ui.settingsOpen = true;
    renderModal();
  }

  function closeSettings() {
    app.ui.settingsOpen = false;
    app.ui.saving = false;
    renderModal();
  }

  function fieldHtml(id, label, value, opts) {
    opts = opts || {};
    var attrs = ' id="' + id + '" type="' + (opts.type || 'text') + '"' +
      ' value="' + esc(value == null ? '' : value) + '"';
    if (opts.placeholder) attrs += ' placeholder="' + esc(opts.placeholder) + '"';
    if (opts.step != null) attrs += ' step="' + esc(opts.step) + '"';
    if (opts.min != null) attrs += ' min="' + esc(opts.min) + '"';
    if (opts.max != null) attrs += ' max="' + esc(opts.max) + '"';
    if (opts.autocomplete) attrs += ' autocomplete="' + esc(opts.autocomplete) + '"';
    if (opts.disabled) attrs += ' disabled';
    return '<label class="form-field">' +
      '<span>' + esc(label) + '</span>' +
      '<input' + attrs + '>' +
      (opts.help ? '<em>' + esc(opts.help) + '</em>' : '') +
      '</label>';
  }

  function renderModal() {
    if (!app.ui.settingsOpen) {
      refs.modalRoot.innerHTML = '';
      return;
    }
    var b = app.bootstrap || {};
    var chat = b.chat || {};
    var memory = b.memory || {};
    var graphStore = b.graphStore || 'InMemoryGraphStore（演示，进程内）';
    var saving = app.ui.saving;

    var html = '<div class="modal-mask" data-act="close-settings">' +
      '<div class="modal-card" role="dialog" aria-modal="true" aria-label="模型设置">' +
        '<div class="modal-head">' +
          '<div><b>模型设置</b><span>对话模型与记忆模型分开配置；保存后写入本机 java/config/model.local.json（gitignore）</span></div>' +
          '<button class="icon-btn" type="button" data-act="close-settings" title="关闭">×</button>' +
        '</div>' +
        '<div class="modal-body">' +
          (app.bootstrap ? '' :
            '<div class="modal-note" style="margin:0 0 12px;">当前未连接后端，无法读取已保存配置；保存会把表单内容提交到后端。</div>') +

          '<section class="settings-group">' +
            '<div class="settings-group-head"><b>对话模型</b><span>生成智能体回复</span></div>' +
            '<div class="form-grid">' +
              fieldHtml('set-chat-provider', 'Provider', chat.provider, { placeholder: 'OpenAI 兼容' }) +
              fieldHtml('set-chat-api-base', 'API Base URL', chat.apiBase, { placeholder: 'http://127.0.0.1:1234/v1' }) +
              fieldHtml('set-chat-api-key', 'API Key', '', { type: 'password', placeholder: '留空沿用已保存的 Key', autocomplete: 'off', help: '后端不回显 API Key' }) +
              fieldHtml('set-chat-model', '对话模型', chat.model, { placeholder: 'google/gemma-4-e4b' }) +
              fieldHtml('set-chat-temperature', '温度', chat.temperature != null ? chat.temperature : 0.2, { type: 'number', step: '0.1', min: '0', max: '2' }) +
            '</div>' +
          '</section>' +

          '<section class="settings-group">' +
            '<div class="settings-group-head"><b>记忆模型</b><span>记忆抽取 LLM + 向量化 Embedding</span></div>' +
            '<div class="form-grid">' +
              fieldHtml('set-mem-provider', 'Provider', memory.provider || chat.provider, { placeholder: 'OpenAI 兼容' }) +
              fieldHtml('set-mem-api-base', 'API Base URL', memory.apiBase, { placeholder: 'http://127.0.0.1:1234/v1' }) +
              fieldHtml('set-mem-api-key', 'API Key', '', { type: 'password', placeholder: '留空沿用已保存的 Key', autocomplete: 'off' }) +
              fieldHtml('set-mem-model', '记忆抽取模型', memory.model, { placeholder: 'google/gemma-4-e4b' }) +
              fieldHtml('set-mem-embedding-api-base', 'Embedding API Base', memory.embeddingApiBase, { placeholder: 'http://127.0.0.1:1234/v1/embeddings' }) +
              fieldHtml('set-mem-embedding-api-key', 'Embedding API Key', '', { type: 'password', placeholder: '留空沿用已保存的 Key', autocomplete: 'off' }) +
              fieldHtml('set-mem-embedding-model', 'Embedding 模型', memory.embeddingModel, { placeholder: 'text-embedding-nomic-embed-text-v1.5' }) +
            '</div>' +
          '</section>' +

          '<section class="settings-group">' +
            '<div class="settings-group-head"><b>图存储</b><span>当前版本固定，不提供切换</span></div>' +
            '<div class="graph-store-row">' +
              '<b>' + esc(graphStore) + '</b>' +
              '<span>进程内、重启后重建。Milvus 待 memory 0.1.17 发布后作为升级目标（Docker Compose 已备好，不在本页切换）。</span>' +
            '</div>' +
          '</section>' +

          '<div class="modal-note">' +
            'API Key 不会从后端回显；留空表示沿用本机已保存的值。API Key 只写入本机配置，不进入代码库。' +
          '</div>' +
          '<div id="settings-error" class="settings-error" hidden></div>' +
        '</div>' +
        '<div class="modal-foot">' +
          '<span id="settings-status" class="test-status"></span>' +
          '<button class="quiet-btn" type="button" data-act="close-settings"' + (saving ? ' disabled' : '') + '>取消</button>' +
          '<button class="primary-btn" type="button" data-act="save-settings"' + (saving ? ' disabled' : '') + '>' +
            (saving ? '保存中…' : '保存') + '</button>' +
        '</div>' +
      '</div>' +
      '</div>';

    refs.modalRoot.innerHTML = html;
  }

  function readField(id) {
    var node = el(id);
    return node ? String(node.value == null ? '' : node.value).trim() : '';
  }

  function collectSettings() {
    var temperature = Number(readField('set-chat-temperature'));
    var payload = {
      chat: {
        provider: readField('set-chat-provider'),
        apiBase: readField('set-chat-api-base'),
        model: readField('set-chat-model'),
        temperature: isFinite(temperature) ? temperature : 0.2
      },
      memory: {
        provider: readField('set-mem-provider'),
        apiBase: readField('set-mem-api-base'),
        model: readField('set-mem-model'),
        embeddingApiBase: readField('set-mem-embedding-api-base'),
        embeddingModel: readField('set-mem-embedding-model')
      },
      graphStore: 'in_memory'
    };

    // API Key：后端不回显，留空时不提交该字段，避免用空串覆盖本机已保存的 Key。
    var chatKey = readField('set-chat-api-key');
    if (chatKey) payload.chat.apiKey = chatKey;
    var memoryKey = readField('set-mem-api-key');
    if (memoryKey) payload.memory.apiKey = memoryKey;
    var embeddingKey = readField('set-mem-embedding-api-key');
    if (embeddingKey) payload.memory.embeddingApiKey = embeddingKey;

    return payload;
  }

  function showSettingsError(message) {
    var box = el('settings-error');
    if (!box) return;
    box.hidden = false;
    box.textContent = message;
  }

  function saveSettings() {
    if (app.ui.saving) return;

    var payload = collectSettings();
    var problem = '';
    if (!payload.chat.apiBase) problem = '请填写对话模型的 API Base URL。';
    else if (!payload.chat.model) problem = '请填写对话模型名称。';
    else if (!payload.memory.apiBase) problem = '请填写记忆模型的 API Base URL。';
    else if (!payload.memory.model) problem = '请填写记忆抽取模型名称。';
    if (problem) {
      showSettingsError(problem);
      return;
    }

    var errorBox = el('settings-error');
    if (errorBox) errorBox.hidden = true;
    app.ui.saving = true;

    var saveBtn = document.querySelector('[data-act="save-settings"]');
    if (saveBtn) { saveBtn.disabled = true; saveBtn.textContent = '保存中…'; }
    var status = el('settings-status');
    if (status) { status.className = 'test-status'; status.textContent = '保存中…'; }

    post(API.settings, payload, 25000).then(function (res) {
      app.ui.saving = false;
      if (res && res.ok === false) {
        throw new ApiError(res.error || res.message || '后端返回保存失败');
      }
      if (!app.bootstrap) app.bootstrap = {};
      if (res && res.chat) app.bootstrap.chat = res.chat;
      if (res && res.memory) app.bootstrap.memory = res.memory;
      if (res && typeof res.configured === 'boolean') app.bootstrap.configured = res.configured;
      app.ui.settingsOpen = false;
      renderModal();
      renderHeaderChip();
      showBanner('success', '模型配置已保存到本机 config/model.local.json，后续对话与记忆抽取将使用新配置。');
      loadBootstrap(true).catch(function () {});
    }).catch(function (err) {
      app.ui.saving = false;
      var statusEl = el('settings-status');
      if (statusEl) { statusEl.className = 'test-status is-error'; statusEl.textContent = '保存失败'; }
      var btn = document.querySelector('[data-act="save-settings"]');
      if (btn) { btn.disabled = false; btn.textContent = '保存'; }
      showSettingsError('保存失败：' + err.message);
    });
  }

  /* ================= 交互动作 ================= */

  function toggleSidebar() {
    app.ui.sidebarCollapsed = !app.ui.sidebarCollapsed;
    renderSessions();
  }

  function selectSession(id) {
    if (!id) return;
    if (String(id) === String(app.sessionId) && !app.loadingSession) {
      renderSessions();
      return;
    }
    stopPolling();
    loadSession(String(id), { scrollBottom: true });
  }

  function setTab(tab) {
    if (['longterm', 'graph', 'pipeline'].indexOf(tab) < 0) return;
    app.ui.tab = tab;
    renderMemory();
  }

  function selectGraphNode(id) {
    if (id == null) return;
    app.ui.graphSelected = String(app.ui.graphSelected) === String(id) ? null : String(id);
    renderMemory();
    if (app.ui.graphSelected != null) {
      var detail = refs.tabContent.querySelector('.graph-detail');
      if (detail && detail.scrollIntoView) {
        detail.scrollIntoView({ block: 'nearest' });
      }
    }
  }

  function createSession() {
    if (app.creating || app.sending) return;
    app.creating = true;
    post(API.sessions, {}, 20000).then(function (res) {
      app.creating = false;
      var id = res && res.sessionId ? String(res.sessionId) : '';
      if (!id) throw new ApiError('新建会话失败：响应缺少 sessionId');
      var title = res.title || '新的咨询';
      if (!findSession(id)) {
        app.sessions.unshift({ id: id, title: title, createdAt: '', messageCount: 0, preview: '' });
      }
      app.activeSessionId = id;
      app.locallyCreatedSessionId = id;
      app.sessionId = null;
      app.snapshot = null;
      app.seenSessionId = null;
      app.processing = false;
      clearErrorBanner();
      renderSessions();
      loadSession(id, { scrollBottom: true });
      loadBootstrap(true).catch(function () {});
    }).catch(function (err) {
      app.creating = false;
      showBanner('error', '新建会话失败：' + err.message, createSession);
    });
  }

  function sendMessage() {
    var input = refs.input;
    var text = input.value.trim();
    if (!text || app.sending || app.viewingHistory || !app.sessionId) return;
    if (isPipelineRunning()) {
      showBanner('warn', '上一轮记忆还在加工中，请稍候再发送（右侧加工流程完成后会自动刷新）。');
      return;
    }

    var sessionId = app.sessionId;
    app.sending = true;
    app.flash = { new: {}, updated: {} };
    app.pendingCustomer = { text: text, ts: nowTime() };
    app.pendingReply = null;
    input.value = '';
    clearErrorBanner();
    renderChat({ scrollBottom: true });
    updateComposer();

    post(API.chat, { sessionId: sessionId, message: text }, CHAT_TIMEOUT).then(function (res) {
      if (String(app.sessionId) !== String(sessionId)) { return; }
      app.sending = false;

      var recall = res && res.recall ? res.recall : null;
      var channels = recall && recall.channels ? recall.channels : {};
      var cites = num(channels.vector) + num(channels.kv);
      var graphCites = num(channels.graph);

      app.pendingReply = {
        text: res && res.reply != null && res.reply !== '' ? String(res.reply) : '（智能体未返回内容）',
        ts: nowTime(),
        cites: cites,
        graphCites: graphCites
      };

      if (recall) {
        app.snapshot = app.snapshot || {};
        app.snapshot = Object.assign({}, app.snapshot, { recall: recall });
      }
      renderChat({ scrollBottom: true });
      renderMemory();
      updateComposer();

      var processing = res && res.processing;
      app.processing = processing !== false;
      if (processing === false) {
        loadSession(sessionId, { scrollBottom: true });
        loadBootstrap(true).catch(function () {});
      } else {
        startPolling(sessionId);
        updateComposer();
      }
    }).catch(function (err) {
      app.sending = false;
      app.processing = false;
      app.pendingCustomer = null;
      app.pendingReply = null;
      input.value = text;
      updateComposer();
      showBanner('error', '发送失败：' + err.message, function () {
        if (refs.input.value.trim()) sendMessage();
      });
      renderChat();
      loadSession(sessionId, { scrollBottom: true });
    });
  }

  /* ================= 轮询 /api/sessions/{id}/state ================= */

  function startPolling(sessionId) {
    stopPolling();
    if (!sessionId) return;
    app.poll.sessionId = String(sessionId);
    app.poll.deadline = Date.now() + POLL_MAX_MS;
    app.poll.failures = 0;
    schedulePoll(700);
  }

  function schedulePoll(delay) {
    if (!app.poll.sessionId) return;
    app.poll.timer = setTimeout(pollOnce, delay);
  }

  function stopPolling() {
    if (app.poll.timer) { clearTimeout(app.poll.timer); }
    app.poll.timer = null;
    app.poll.sessionId = null;
    app.poll.failures = 0;
  }

  function pollOnce() {
    var sessionId = app.poll.sessionId;
    if (!sessionId) return;

    if (Date.now() > app.poll.deadline) {
      stopPolling();
      updateComposer();
      showBanner('warn', '记忆加工耗时已超过 10 分钟，已停止自动刷新；可稍后手动切换会话刷新。', function () {
        loadSession(app.sessionId, { scrollBottom: true });
      });
      return;
    }

    var token = app.loadToken;
    get(API.state(sessionId), 12000).then(function (data) {
      if (app.poll.sessionId !== sessionId || token !== app.loadToken) return;
      app.poll.failures = 0;
      applySnapshot(data, { scrollBottom: false });
      if (isPipelineRunning()) {
        updateComposer();
        schedulePoll(POLL_INTERVAL);
      } else {
        stopPolling();
        app.processing = false;
        updateComposer();
        clearErrorBanner();
        loadBootstrap(true).catch(function () {});
      }
    }).catch(function (err) {
      if (app.poll.sessionId !== sessionId) return;
      app.poll.failures += 1;
      if (app.poll.failures >= 3) {
        stopPolling();
        updateComposer();
        showBanner('error', '刷新记忆加工状态失败：' + err.message, function () {
          loadSession(sessionId, { scrollBottom: true });
        });
      } else {
        schedulePoll(1200);
      }
    });
  }

  /* ================= 启动 ================= */

  function cacheRefs() {
    refs.errorBar = el('error-bar');
    refs.errorText = el('error-text');
    refs.errorRetry = el('btn-error-retry');
    refs.errorClose = el('btn-error-close');
    refs.modelChip = el('model-chip');
    refs.modelChipText = el('model-chip-text');
    refs.sidebar = el('sidebar');
    refs.toggleSidebar = el('btn-toggle-sidebar');
    refs.sessionList = el('session-list');
    refs.chatSub = el('chat-sub');
    refs.sessionPill = el('session-pill');
    refs.historyTag = el('history-tag');
    refs.historyNote = el('history-note');
    refs.chatScroll = el('chat-scroll');
    refs.composer = el('composer');
    refs.composerStatus = el('composer-status');
    refs.composerStatusText = el('composer-status-text');
    refs.input = el('composer-input');
    refs.sendBtn = el('btn-send');
    refs.memoryCount = el('memory-count');
    refs.storageCard = el('storage-card');
    refs.memoryTabs = el('memory-tabs');
    refs.tabContent = el('tab-content');
    refs.modalRoot = el('modal-root');
  }

  function readDeepLink() {
    var params = new URLSearchParams(window.location.search);
    var tab = params.get('tab');
    if (['longterm', 'graph', 'pipeline'].indexOf(tab) >= 0) app.ui.tab = tab;
    app.deepLink.tab = tab;
    app.deepLink.session = params.get('session');
    app.deepLink.settings = params.get('settings') === '1';
  }

  function init() {
    cacheRefs();
    readDeepLink();
    bindEvents();
    renderAll();
    initialLoad();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
