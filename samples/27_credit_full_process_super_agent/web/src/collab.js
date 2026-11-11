/* 团队协作实时信息（右栏）· 多智能体群聊
 *
 * 契约：docs/superpowers/specs/2026-11-11-credit-collab-rail.md
 * 接口：createCollabStream({streamEl, statusEl, roles}) →
 *   {beginRun(question), handle(event), endRun(status, message), reset()}
 *
 * 设计要点（对应 spec 前端实现要点）：
 * - 只消费 type === 'collab' 的 NDJSON 事件，其余类型（agent / reply / answer / a2ui）原样忽略；
 * - 群聊身份：leader（credit_master）气泡靠右红色系，专员靠左，review_master 汇总琥珀高亮；
 * - 事件按到达顺序 append；同一智能体的连续事件复用同一个气泡节点，只增量 append，不做全量重绘；
 * - 所有事件文本一律 textContent / createTextNode 渲染，不拼 innerHTML，避免注入；
 * - 自动滚动：贴底时跟随新事件，用户上翻后不抢滚动，并显示「↓ 回到最新」；
 * - 不依赖第三方库、不写 localStorage、没有 setInterval / setTimeout 形式的动画（脉冲用 CSS）。
 */
import './collab.css';

const STATUS_TEXT = {idle: '待命', running: '进行中', done: '已完成', error: '失败'};
/* 参与「协作事件」计数的 ev：run_start / run_end 只负责生命周期，不计入气泡事件数 */
const COUNTED_EVENTS = new Set(['agent_start', 'tool_start', 'tool_end', 'agent_end', 'operator', 'error']);
/* submit_plan 的 steps：本轮前 3 个阶段已启动（蓝），后 3 个待启动（灰） */
const PLAN_ACTIVE_STEPS = 3;
const UNKNOWN_COLOR = '#64748b';

/* 短名兜底：main.js 的 ROLES 提供 id/parentId/title/subtitle/icon/color，
 * 短名用于窄栏里的气泡与 @提及（避免「信贷审查团队」这类长标题挤爆一行）。 */
const SHORT_NAMES = {
  credit_master: '超级智能体',
  review_master: '审查主管',
  intake_agent: '材料受理专员',
  risk_agent: '征信专员',
  pricing_agent: '定价专员',
  execution_master: '签约放款主管',
  contract_agent: '合同专员',
  disbursement_agent: '放款专员',
  service_agent: '信贷客服',
  postloan_agent: '贷后专员'
};

/* 角色的防御性默认值：组件单独挂载（不经过 main.js 的 ROLES）时也能正常显示 */
const FALLBACK_ROLES = {
  credit_master: {id: 'credit_master', parentId: null, title: '信贷超级智能体', subtitle: '全局规划 · 任务调度', icon: '◈', color: '#c7000b'},
  review_master: {id: 'review_master', parentId: 'credit_master', title: '信贷审查团队', subtitle: 'MASTER · 贷前审查', icon: '▦', color: '#9a5b13'},
  intake_agent: {id: 'intake_agent', parentId: 'review_master', title: '材料受理', subtitle: '资料完整性与合规', icon: '▤', color: '#3568a2'},
  risk_agent: {id: 'risk_agent', parentId: 'review_master', title: '征信反欺诈', subtitle: '风险核验与还款能力', icon: '◇', color: '#7a4fa3'},
  pricing_agent: {id: 'pricing_agent', parentId: 'review_master', title: '额度定价', subtitle: '额度 · 利率 · 月供', icon: '¥', color: '#177d5b'},
  execution_master: {id: 'execution_master', parentId: 'credit_master', title: '签约放款团队', subtitle: 'MASTER · 交易执行', icon: '▦', color: '#9a5b13'},
  contract_agent: {id: 'contract_agent', parentId: 'execution_master', title: '合同准备', subtitle: '合同条款与签署', icon: '▧', color: '#3568a2'},
  disbursement_agent: {id: 'disbursement_agent', parentId: 'execution_master', title: '放款执行', subtitle: '授权核验与放款', icon: '↗', color: '#177d5b'},
  service_agent: {id: 'service_agent', parentId: 'credit_master', title: '信贷客服', subtitle: '产品咨询 · 进度查询', icon: '◎', color: '#3568a2'},
  postloan_agent: {id: 'postloan_agent', parentId: 'credit_master', title: '贷后管理', subtitle: '还款安排与风险跟踪', icon: '◷', color: '#7a4fa3'}
};

/* ---------- 通用小工具（不碰事件数据以外的 DOM） ---------- */

function createEl(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text != null) node.textContent = String(text);
  return node;
}

function hexToRgba(hex, alpha) {
  const raw = String(hex || UNKNOWN_COLOR).replace('#', '');
  const full = raw.length === 3 ? raw.split('').map((c) => c + c).join('') : raw;
  const value = parseInt(full, 16);
  if (Number.isNaN(value) || full.length !== 6) return 'rgba(100, 116, 139, ' + alpha + ')';
  return 'rgba(' + ((value >> 16) & 255) + ', ' + ((value >> 8) & 255) + ', ' + (value & 255) + ', ' + alpha + ')';
}

function timeLabel(ts) {
  const date = new Date(Number(ts) || Date.now());
  if (Number.isNaN(date.getTime())) return '';
  return date.toLocaleTimeString('zh-CN', {hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit'});
}

/* 工具入参 chip 的显示值：短标量原样，数组只说项数，对象压成 JSON（CSS 负责截断） */
function formatArgValue(value) {
  if (Array.isArray(value)) return value.length + ' 项';
  if (value && typeof value === 'object') {
    try { return JSON.stringify(value); } catch { return String(value); }
  }
  return String(value == null ? '' : value);
}

/* 工具结果：协议里是字符串（服务端已截断到 240 字符），对象也尽量兜住 */
function formatResult(value) {
  if (value == null) return '';
  if (typeof value === 'string') return value;
  try { return JSON.stringify(value); } catch { return String(value); }
}

function roleDisplayName(role) {
  return role.label || role.shortName || role.name || SHORT_NAMES[role.id] || role.title || role.id || '智能体';
}

function stateLabel(state) {
  if (state === 'working') return '工作中';
  if (state === 'done') return '已回报';
  if (state === 'error') return '失败';
  return '待命';
}

/* ---------- 组件 ---------- */

export function createCollabStream(options) {
  const {streamEl, statusEl, roles} = options || {};
  const api = {beginRun, handle, endRun, reset};
  const roleMap = buildRoleMap(roles);

  /* 视图状态全部放在闭包里，一个页面一个实例，不写 localStorage */
  const view = {
    status: 'idle',
    running: false,
    finalized: false,
    eventCount: 0,
    question: '',
    lastBubble: null,
    bubbles: [],               // 本轮所有气泡（收尾时统一落状态，避免历史气泡一直显示「工作中」）
    agentStates: new Map(),    // agentId -> working | done | error
    pendingTools: new Map(),   // "agent|tool" -> 尚未结束的工具卡栈
    stick: true,               // 用户是否停在底部
    placeholder: null,
    jumpEl: null,
    scroller: null
  };

  if (statusEl) statusEl.classList.add('collab-status');
  if (streamEl) streamEl.classList.add('collab-stream');
  setStatus('idle');

  function buildRoleMap(source) {
    const map = new Map();
    const push = (role) => {
      if (!role || !role.id) return;
      map.set(String(role.id), {...(FALLBACK_ROLES[role.id] || {}), ...role, id: String(role.id)});
    };
    if (Array.isArray(source)) {
      source.forEach(push);
    } else if (source && typeof source === 'object') {
      Object.keys(source).forEach((id) => push({id, ...(source[id] || {})}));
    }
    return map;
  }

  function roleOf(agentId) {
    const id = String(agentId || '');
    return roleMap.get(id) || {id, title: id || '智能体', subtitle: '', icon: '◆', color: UNKNOWN_COLOR, parentId: undefined};
  }

  function setStatus(kind) {
    view.status = kind;
    if (!statusEl) return;
    statusEl.textContent = STATUS_TEXT[kind] || STATUS_TEXT.idle;
    statusEl.dataset.state = kind;
  }

  /* ---------- 滚动容器 / 占位 / 回到最新 ---------- */

  /* spec 约定 streamEl 自己是滚动容器；若宿主把 overflow 放在外层，这里向上兜底找一个。 */
  function resolveScroller() {
    let node = streamEl;
    while (node && node !== document.body && node !== document.documentElement) {
      const style = window.getComputedStyle(node);
      if (/(auto|scroll)/.test(style.overflowY)) return node;
      node = node.parentElement;
    }
    return streamEl;
  }

  function ensureScroller() {
    if (!streamEl || view.scroller) {
      if (view.scroller) view.scroller.addEventListener('scroll', onScroll, {passive: true});
      return view.scroller;
    }
    view.scroller = resolveScroller();
    view.scroller.addEventListener('scroll', onScroll, {passive: true});
    return view.scroller;
  }

  function isNearBottom() {
    const scroller = view.scroller;
    if (!scroller) return true;
    return scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight <= 32;
  }

  function onScroll() {
    view.stick = isNearBottom();
    if (view.jumpEl) view.jumpEl.hidden = view.stick || !view.lastBubble;
  }

  function scrollToLatest() {
    const scroller = view.scroller;
    if (!scroller) return;
    scroller.scrollTop = scroller.scrollHeight;
  }

  /* 有新事件 append 之后调用：贴底则跟随，否则露出「回到最新」 */
  function afterAppend() {
    ensureScroller();
    if (view.stick) {
      scrollToLatest();
      if (view.jumpEl) view.jumpEl.hidden = true;
    } else if (view.jumpEl && view.lastBubble) {
      view.jumpEl.hidden = false;
    }
  }

  function createJumpButton() {
    const button = createEl('button', 'collab-jump', '↓ 回到最新');
    button.type = 'button';
    button.hidden = true;
    button.title = '回到团队协作实时信息的最新一条';
    button.addEventListener('click', () => {
      view.stick = true;
      scrollToLatest();
      button.hidden = true;
    });
    return button;
  }

  function createPlaceholder() {
    const box = createEl('div', 'collab-empty');
    box.setAttribute('role', 'note');
    return box;
  }

  /* 清空实时信息区（保留自己的占位与「回到最新」按钮骨架） */
  function clearStream() {
    if (!streamEl) return;
    streamEl.textContent = '';
    view.lastBubble = null;
    view.bubbles = [];
    view.agentStates.clear();
    view.pendingTools.clear();
    view.stick = true;
    view.jumpEl = createJumpButton();
    view.placeholder = createPlaceholder();
    streamEl.appendChild(view.placeholder);
    streamEl.appendChild(view.jumpEl);
    ensureScroller();
    if (view.scroller) view.scroller.scrollTop = 0;
  }

  /* 空态 / 等待态 / 空轮次：只改占位节点，不动已有气泡 */
  function showPlaceholder(kind) {
    if (!streamEl) return;
    if (!view.placeholder) clearStream();
    const box = view.placeholder;
    box.textContent = '';
    box.className = 'collab-empty' + (kind === 'waiting' ? ' collab-empty--waiting' : '');
    const icon = createEl('span', 'collab-empty-icon', kind === 'waiting' ? '◌' : '▤');
    let title = '发起问题后，团队协作实时信息会在这里实时展示（客户不可见）';
    if (kind === 'waiting') title = '团队协作实时信息已开始，等待首个事件…';
    if (kind === 'empty-run') title = '本轮没有可展示的协作事件';
    box.appendChild(icon);
    box.appendChild(createEl('p', 'collab-empty-title', title));
    box.appendChild(createEl('p', 'collab-empty-sub', '协作过程只在内部视图展示，客户对话不可见。'));
    box.hidden = false;
  }

  function hidePlaceholder() {
    if (view.placeholder) view.placeholder.hidden = true;
  }

  /* 往实时信息区末尾（「回到最新」按钮之前）插一个节点 */
  function mountNode(node) {
    if (!streamEl) return;
    if (!view.jumpEl) clearStream();
    hidePlaceholder();
    streamEl.insertBefore(node, view.jumpEl);
  }

  function appendSystem(text, variant) {
    if (!streamEl) return;
    const line = createEl('div', 'collab-system' + (variant ? ' collab-system--' + variant : ''));
    line.appendChild(createEl('span', 'collab-system-text', text));
    mountNode(line);
    afterAppend();
  }

  /* ---------- 气泡与内容块 ---------- */

  function isLeaderRole(role) {
    return role.id === 'credit_master' || role.parentId == null;
  }

  function createBubble(agentId) {
    const role = roleOf(agentId);
    const leader = isLeaderRole(role);
    const root = createEl('article', 'collab-msg' + (leader ? ' collab-msg--right' : '') + (role.id === 'review_master' ? ' collab-msg--accent' : ''));
    root.dataset.agent = role.id;

    const avatar = createEl('span', 'collab-msg-avatar', role.icon || '◆');
    avatar.title = role.title || role.id;
    avatar.style.setProperty('--collab-av-fg', role.color || UNKNOWN_COLOR);
    avatar.style.setProperty('--collab-av-bg', hexToRgba(role.color, 0.09));
    avatar.style.setProperty('--collab-av-line', hexToRgba(role.color, 0.24));

    const body = createEl('div', 'collab-msg-body');
    const meta = createEl('div', 'collab-msg-meta');
    const name = createEl('span', 'collab-msg-name', roleDisplayName(role));
    const subtitle = role.subtitle || role.title || '';
    const tag = createEl('span', 'collab-msg-role', subtitle);
    tag.title = subtitle;
    const stateEl = createEl('span', 'collab-msg-state');
    const timeEl = createEl('time', 'collab-msg-time');
    const bubbleEl = createEl('div', 'collab-msg-bubble');
    meta.appendChild(name);
    if (subtitle) meta.appendChild(tag);
    meta.appendChild(stateEl);
    meta.appendChild(timeEl);
    body.appendChild(meta);
    body.appendChild(bubbleEl);
    root.appendChild(avatar);
    root.appendChild(body);
    mountNode(root);

    const bubble = {agentId: role.id, role, root, bubbleEl, stateEl, timeEl, metaState: 'working'};
    view.lastBubble = bubble;
    view.bubbles.push(bubble);
    setBubbleState(bubble, view.agentStates.get(role.id) || 'working');
    return bubble;
  }

  /* 同一智能体连续事件复用气泡；换人则新起一条，保证群聊时间顺序自然 */
  function ensureBubble(agentId) {
    const id = String(agentId || 'credit_master');
    if (view.lastBubble && view.lastBubble.agentId === id) return view.lastBubble;
    return createBubble(id);
  }

  function setBubbleState(bubble, state) {
    if (!bubble) return;
    bubble.metaState = state;
    bubble.stateEl.dataset.state = state;
    bubble.stateEl.textContent = stateLabel(state);
  }

  /* 某个智能体结束/异常时，把它此前所有仍在「工作中」的气泡统一落状态 */
  function settleAgentBubbles(agentId, state) {
    view.bubbles.forEach((bubble) => {
      if (bubble.agentId === agentId && bubble.metaState === 'working') setBubbleState(bubble, state);
    });
  }

  function touch(bubble, ts) {
    if (!bubble) return;
    bubble.timeEl.textContent = timeLabel(ts);
    if (ts != null) bubble.timeEl.dateTime = new Date(Number(ts) || Date.now()).toISOString();
  }

  function finishBlock(bubble, ts) {
    touch(bubble, ts);
    afterAppend();
  }

  function createActRow() {
    return createEl('div', 'collab-act');
  }

  function createTag(text, variant) {
    return createEl('span', 'collab-act-tag collab-act-tag--' + variant, text);
  }

  function createMention(agentId) {
    return createEl('span', 'collab-mention', '@' + roleDisplayName(roleOf(agentId)));
  }

  function appendText(container, text) {
    if (!text) return;
    container.appendChild(createEl('p', 'collab-text', text));
  }

  /* leader / 父级：接收需求或分发任务 */
  function appendStartBlock(bubble, task, ts) {
    const block = createEl('div', 'collab-block collab-block--start');
    const act = createActRow();
    act.appendChild(createTag('调度', 'dispatch'));
    act.appendChild(createEl('span', 'collab-act-label', '接收需求 · 开始规划'));
    block.appendChild(act);
    appendText(block, task);
    bubble.bubbleEl.appendChild(block);
    finishBlock(bubble, ts);
  }

  /* handoff：父级把任务分发给子智能体 */
  function appendHandoffBlock(bubble, childId, task, ts) {
    const block = createEl('div', 'collab-block collab-block--handoff');
    block.dataset.child = String(childId);
    const act = createActRow();
    act.appendChild(createEl('span', 'collab-arrow', '→'));
    act.appendChild(createMention(childId));
    act.appendChild(createEl('span', 'collab-act-label', '分发任务'));
    block.appendChild(act);
    appendText(block, task);
    bubble.bubbleEl.appendChild(block);
    finishBlock(bubble, ts);
  }

  /* 回报：子智能体把结果回报给父级；leader 根节点则是本轮收尾 */
  function appendReportBlock(bubble, event, ok) {
    const parentId = event.parent ? String(event.parent) : null;
    const output = event.output ? String(event.output) : '';
    const block = createEl('div', 'collab-block collab-block--report');
    const act = createActRow();
    if (parentId) {
      act.appendChild(createTag(ok ? '回报' : '回报失败', ok ? 'report' : 'error'));
      act.appendChild(createEl('span', 'collab-arrow', '→'));
      act.appendChild(createMention(parentId));
    } else {
      act.appendChild(createTag('收尾', ok ? 'dispatch' : 'error'));
      act.appendChild(createEl('span', 'collab-act-label', ok ? '输出最终结果' : '本轮协作失败'));
    }
    block.appendChild(act);
    appendText(block, output);
    bubble.bubbleEl.appendChild(block);
  }

  /* 工具卡：工具名 chip + 入参 chips；结束后补结果摘要 / 阶段 chips */
  function createToolCard(event) {
    const tool = String(event.tool || 'tool');
    const cardEl = createEl('div', 'collab-tool');
    cardEl.dataset.tool = tool;
    cardEl.dataset.state = 'running';

    const head = createEl('div', 'collab-tool-head');
    head.appendChild(createEl('span', 'collab-tool-chip', tool + '()'));
    const stateEl = createEl('span', 'collab-tool-state');
    stateEl.dataset.state = 'running';
    const dot = createEl('i', 'collab-tool-dot');
    const stateText = createEl('span', 'collab-tool-state-text', '执行中');
    stateEl.appendChild(dot);
    stateEl.appendChild(stateText);
    head.appendChild(stateEl);
    cardEl.appendChild(head);

    const args = event.args && typeof event.args === 'object' ? event.args : null;
    if (args) {
      const row = createEl('div', 'collab-tool-args');
      Object.keys(args).forEach((key) => {
        const value = args[key];
        /* submit_plan 的阶段统一在 tool_end 里渲染成 chips，不在入参里重复 */
        if (key === 'steps' && Array.isArray(value)) return;
        const chip = createEl('span', 'collab-arg');
        chip.appendChild(createEl('b', 'collab-arg-key', key));
        chip.appendChild(createEl('span', 'collab-arg-value', '=' + formatArgValue(value)));
        row.appendChild(chip);
      });
      if (row.childElementCount) cardEl.appendChild(row);
    }
    return {el: cardEl, stateEl, stateText, tool, bubble: null};
  }

  function appendToolStart(bubble, event, ts) {
    const card = createToolCard(event);
    card.bubble = bubble;
    bubble.bubbleEl.appendChild(card.el);
    const key = bubble.agentId + '|' + card.tool;
    if (!view.pendingTools.has(key)) view.pendingTools.set(key, []);
    view.pendingTools.get(key).push(card);
    finishBlock(bubble, ts);
    return card;
  }

  /* submit_plan 的 6 阶段 chips：本轮前 3 个已启动（蓝），后 3 个待启动（灰） */
  function createStepsRow(steps) {
    const row = createEl('div', 'collab-steps');
    steps.forEach((step, index) => {
      const active = index < PLAN_ACTIVE_STEPS;
      const chip = createEl('span', 'collab-step' + (active ? ' collab-step--on' : ''));
      chip.appendChild(createEl('i', 'collab-step-num', String(index + 1)));
      chip.appendChild(document.createTextNode(String(step)));
      row.appendChild(chip);
    });
    return row;
  }

  function completeToolCard(card, event) {
    const ok = event.ok !== false;
    const state = ok ? 'done' : 'error';
    card.el.dataset.state = state;
    card.stateEl.dataset.state = state;
    card.stateText.textContent = ok ? '完成' : '失败';
    const resultText = formatResult(event.result);
    if (resultText) {
      const result = createEl('div', 'collab-tool-result');
      result.appendChild(createEl('span', 'collab-tool-mark', ok ? '✓' : '✗'));
      result.appendChild(createEl('span', 'collab-tool-result-text', resultText));
      card.el.appendChild(result);
    }
    if (card.tool === 'submit_plan' && Array.isArray(event.steps) && event.steps.length) {
      card.el.appendChild(createStepsRow(event.steps));
    }
  }

  function appendToolEnd(event, ts) {
    const agentId = String(event.agent || 'credit_master');
    const tool = String(event.tool || 'tool');
    const key = agentId + '|' + tool;
    const stack = view.pendingTools.get(key);
    let card = stack && stack.length ? stack.pop() : null;
    let bubble;
    if (card) {
      bubble = card.bubble || ensureBubble(agentId);
    } else {
      /* 没收到 tool_start（理论上不会发生）：补一张已完成工具卡 */
      bubble = ensureBubble(agentId);
      card = createToolCard(event);
      card.bubble = bubble;
      bubble.bubbleEl.appendChild(card.el);
    }
    completeToolCard(card, event);
    finishBlock(bubble, ts);
  }

  function appendOperatorBlock(bubble, text, ts) {
    const block = createEl('div', 'collab-block collab-block--operator');
    const act = createActRow();
    act.appendChild(createTag('运行者确认', 'operator'));
    block.appendChild(act);
    appendText(block, text);
    bubble.bubbleEl.appendChild(block);
    finishBlock(bubble, ts);
  }

  function appendErrorBlock(bubble, message, ts) {
    const block = createEl('div', 'collab-block collab-block--error');
    const act = createActRow();
    act.appendChild(createTag('异常', 'error'));
    block.appendChild(act);
    appendText(block, message);
    bubble.bubbleEl.appendChild(block);
    finishBlock(bubble, ts);
  }

  /* ---------- collab 事件入口 ---------- */

  function onRunStart(event) {
    if (!view.running || view.finalized) beginRun(event.question || '');
    view.running = true;
    if (event.question) view.question = String(event.question);
    setStatus('running');
  }

  function onAgentStart(event) {
    const agentId = String(event.agent || 'credit_master');
    const parentId = event.parent ? String(event.parent) : null;
    const task = event.task ? String(event.task) : '';
    view.agentStates.set(agentId, 'working');
    if (parentId) {
      /* 带 parent 的启动 = 父级把任务分发出去，气泡落在父级 */
      const parentBubble = ensureBubble(parentId);
      view.agentStates.set(parentId, 'working');
      setBubbleState(parentBubble, 'working');
      appendHandoffBlock(parentBubble, agentId, task, event.ts);
    } else {
      appendStartBlock(ensureBubble(agentId), task, event.ts);
    }
  }

  function onToolStart(event) {
    const agentId = String(event.agent || 'credit_master');
    const bubble = ensureBubble(agentId);
    setBubbleState(bubble, 'working');
    view.agentStates.set(agentId, 'working');
    appendToolStart(bubble, event, event.ts);
  }

  function onToolEnd(event) {
    appendToolEnd(event, event.ts);
  }

  function onAgentEnd(event) {
    const agentId = String(event.agent || 'credit_master');
    const ok = event.ok !== false;
    const bubble = ensureBubble(agentId);
    appendReportBlock(bubble, event, ok);
    settleAgentBubbles(agentId, ok ? 'done' : 'error');
    view.agentStates.set(agentId, ok ? 'done' : 'error');
    finishBlock(bubble, event.ts);
  }

  function onOperator(event) {
    const agentId = String(event.agent || 'credit_master');
    const bubble = ensureBubble(agentId);
    setBubbleState(bubble, 'working');
    appendOperatorBlock(bubble, event.text ? String(event.text) : '', event.ts);
  }

  function onError(event) {
    const message = event.message ? String(event.message) : '协作过程发生异常';
    const agentId = event.agent ? String(event.agent) : null;
    if (agentId) {
      const bubble = ensureBubble(agentId);
      appendErrorBlock(bubble, message, event.ts);
      settleAgentBubbles(agentId, 'error');
      view.agentStates.set(agentId, 'error');
    } else {
      appendSystem('异常 · ' + message, 'error');
    }
  }

  function onRunEnd(event) {
    const raw = String(event.status || 'completed');
    finalize(raw === 'error' ? 'error' : 'done', raw);
  }

  /* 本轮收尾：状态 chip 落定、仍显示「工作中」的气泡统一落状态、补一条结果分隔线 */
  function finalize(kind, rawStatus) {
    if (!streamEl || view.finalized) return;
    view.finalized = true;
    view.running = false;
    view.bubbles.forEach((bubble) => {
      if (bubble.metaState === 'working') setBubbleState(bubble, kind === 'error' ? 'error' : 'done');
    });
    setStatus(kind);
    let text;
    if (kind === 'error') text = '本轮协作异常中断 · ' + view.eventCount + ' 个协作事件';
    else if (rawStatus === 'cancelled') text = '本轮协作已取消 · ' + view.eventCount + ' 个协作事件';
    else text = '本轮协作完成 · ' + view.eventCount + ' 个协作事件';
    appendSystem(text, kind === 'error' ? 'error' : 'done');
    if (!view.lastBubble) showPlaceholder('empty-run');
  }

  /* ---------- 公开接口 ---------- */

  function beginRun(question) {
    view.running = true;
    view.finalized = false;
    view.status = 'running';
    view.eventCount = 0;
    view.question = question ? String(question) : '';
    clearStream();
    setStatus('running');
    showPlaceholder('waiting');
  }

  function handle(event) {
    if (!event || event.type !== 'collab') return;
    const ev = event.ev;
    if (ev === 'run_start') {
      onRunStart(event);
      return;
    }
    if (view.finalized) return;             // run_end 之后的迟到事件忽略
    if (ev === 'run_end') {
      onRunEnd(event);
      return;
    }
    if (!view.running) {                    // 宿主漏调 beginRun 时兜底
      view.running = true;
      setStatus('running');
    }
    if (COUNTED_EVENTS.has(ev)) view.eventCount += 1;
    switch (ev) {
      case 'agent_start': onAgentStart(event); break;
      case 'tool_start': onToolStart(event); break;
      case 'tool_end': onToolEnd(event); break;
      case 'agent_end': onAgentEnd(event); break;
      case 'operator': onOperator(event); break;
      case 'error': onError(event); break;
      default: break;                       // 未知 ev：忽略，不阻塞后续事件
    }
  }

  function endRun(status, message) {
    if (!streamEl) {
      setStatus(status === 'error' ? 'error' : 'done');
      view.running = false;
      view.finalized = true;
      return;
    }
    if (view.finalized) return;             // 服务端已发 run_end 时幂等
    finalize(status === 'error' ? 'error' : 'done', null);
    if (status === 'error') {
      const text = message ? String(message) : '连接中断，本轮协作未正常结束';
      if (!view.lastBubble) {
        showPlaceholder('empty-run');
      }
      appendSystem('异常 · ' + text, 'error');
    }
  }

  function reset() {
    view.running = false;
    view.finalized = false;
    view.status = 'idle';
    view.eventCount = 0;
    view.question = '';
    if (!streamEl) {
      setStatus('idle');
      return;
    }
    clearStream();
    setStatus('idle');
    showPlaceholder('idle');
  }

  if (streamEl) {
    reset();
  } else {
    setStatus('idle');
  }

  return api;
}
