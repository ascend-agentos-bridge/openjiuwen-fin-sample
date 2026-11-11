import './style.css';
import {OrgChart} from 'd3-org-chart';
import {marked} from 'marked';
import DOMPurify from 'dompurify';
import {MessageProcessor} from '@a2ui/web_core/v0_9';
import {A2uiSurface, basicCatalog} from '@a2ui/lit/v0_9';
import {createCollabStream} from './collab.js';

/* =========================================================================
 * 1. 常量：品牌 / 角色 / 文案
 * ========================================================================= */

document.documentElement.classList.add('a2ui-light');
if (!customElements.get('a2ui-surface')) {
  customElements.define('a2ui-surface', A2uiSurface);
}

const $ = (selector) => document.querySelector(selector);

const STATUS_LABELS = {idle: '待命', working: '工作中', completed: '已完成', error: '失败'};
const STATUS_VALUES = ['idle', 'working', 'completed', 'error'];

/**
 * 10 个智能体，右侧 d3 架构图与「团队协作实时信息」共用同一份角色表。
 * id/parentId/title/subtitle/icon 决定架构图外观，color 供协作实时信息气泡使用。
 */
const ROLES = [
  {id: 'credit_master', parentId: null, title: '信贷超级智能体', subtitle: '全局规划 · 任务调度', icon: '◈', color: '#c7000b'},
  {id: 'review_master', parentId: 'credit_master', title: '信贷审查团队', subtitle: 'MASTER · 贷前审查', icon: '▦', color: '#9a5b13'},
  {id: 'intake_agent', parentId: 'review_master', title: '材料受理', subtitle: '资料完整性与合规', icon: '▤', color: '#3568a2'},
  {id: 'risk_agent', parentId: 'review_master', title: '征信反欺诈', subtitle: '风险核验与还款能力', icon: '◇', color: '#7a4fa3'},
  {id: 'pricing_agent', parentId: 'review_master', title: '额度定价', subtitle: '额度 · 利率 · 月供', icon: '¥', color: '#177d5b'},
  {id: 'execution_master', parentId: 'credit_master', title: '签约放款团队', subtitle: 'MASTER · 交易执行', icon: '▦', color: '#9a5b13'},
  {id: 'contract_agent', parentId: 'execution_master', title: '合同准备', subtitle: '合同条款与签署', icon: '▧', color: '#3568a2'},
  {id: 'disbursement_agent', parentId: 'execution_master', title: '放款执行', subtitle: '授权核验与放款', icon: '↗', color: '#177d5b'},
  {id: 'service_agent', parentId: 'credit_master', title: '信贷客服', subtitle: '产品咨询 · 进度查询', icon: '◎', color: '#3568a2'},
  {id: 'postloan_agent', parentId: 'credit_master', title: '贷后管理', subtitle: '还款安排与风险跟踪', icon: '◷', color: '#7a4fa3'}
];

/** 事件流无响应超时：超过该时长没有任何新事件就放弃本轮（模型正常一轮约 20-60s）。 */
const REQUEST_TIMEOUT_MS = 120000;

/** 运行者确认按钮 -> X-Decision 动作。 */
const DECISION_ACTIONS = ['approve', 'reject', 'sign', 'supplement'];
const DECISION_LABELS = {
  approve: '审批批准',
  reject: '审批拒绝',
  sign: '确认签署及放款授权',
  supplement: '确认演示材料已补齐'
};

const WELCOME_HTML = `
  <div class="welcome">
    <span class="welcome-icon">◈</span>
    <h2>您好，今天有什么融资需求？</h2>
    <p>我会先了解您的目标，为您规划办理步骤，<br>并把关键数据与办理进度清楚呈现。</p>
    <button type="button" class="config-hint" data-config-hint data-open-config hidden>
      <span class="config-hint-dot" aria-hidden="true"></span>
      尚未配置模型服务，请先完成模型设置
      <span class="config-hint-arrow" aria-hidden="true">→</span>
    </button>
    <div class="suggestions">
      <button data-q="我想买设备，手头资金有点紧，看看能不能帮忙。">↗ 我想购置设备，了解融资方案</button>
      <button data-q="经营贷的产品利率是多少？">↗ 了解经营贷产品与利率</button>
      <button data-q="客户C1001申请80万元经营贷，36个月，设备采购，请提交计划并完成申请审查，不要准备合同或放款。">↗ 为 C1001 发起贷款审查</button>
    </div>
  </div>`;

const DETAIL_PLACEHOLDER_HTML = `
  <span class="detail-icon">◈</span>
  <div>
    <strong>选择一个智能体，查看职责与当前状态</strong>
    <p>节点状态由真实执行事件更新，未被调用的智能体保持待命。</p>
  </div>`;

/** 页面外壳：左客户对话（40%）+ 右智能体协同网络（60%）。 */
const APP_SHELL = `
  <header class="app-header">
    <a class="brand">
      <span class="brandmark">◈</span>
      <span>信贷智能工作台<small>CREDIT INTELLIGENCE WORKSPACE</small></span>
    </a>
    <div class="header-right">
      <span class="online">● DeepSeek Flash</span>
      <span class="env">演示环境</span>
      <div class="header-actions">
        <button id="model-config" type="button" title="模型设置">
          <span class="model-config-gear" aria-hidden="true">⚙</span>
          <span class="model-config-label">模型设置</span>
          <span class="model-config-dot" aria-hidden="true"></span>
        </button>
        <button id="new">＋ 新建会话</button>
      </div>
    </div>
  </header>

  <main>
    <section class="chat-panel">
      <div class="panel-heading">
        <div>
          <span class="eyebrow">YOUR AI CREDIT PARTNER</span>
          <h1>从一个想法，到融资方案</h1>
          <p>描述您的需求，专业团队为您协同办理。</p>
        </div>
        <span class="title-icon">✦</span>
      </div>

      <div id="messages" aria-live="polite"></div>

      <form id="form">
        <div class="composer">
          <textarea id="query" placeholder="描述您的融资需求，或继续询问办理进展…" rows="3" required></textarea>
          <div class="composer-foot">
            <span id="status">Enter 发送 · Shift + Enter 换行</span>
            <button id="send" aria-label="发送问题">发送 <span>↑</span></button>
          </div>
        </div>
        <div class="disclaimer">虚构客户与业务规则 · 审批、签署及放款均为演示操作</div>
      </form>
    </section>

    <section class="team-panel">
      <div class="team-header">
        <div>
          <span class="eyebrow">AGENT ORGANIZATION</span>
          <h2>智能体协同网络 <span class="count">10</span></h2>
          <p>节点随真实执行事件点亮，团队协作过程在下方实时展示。</p>
        </div>
        <div class="chart-controls">
          <button id="zoom-out" title="缩小">−</button>
          <button id="fit" title="适应画布">⤢</button>
          <button id="zoom-in" title="放大">＋</button>
        </div>
      </div>

      <div class="legend">
        <span><i class="idle"></i>待命</span>
        <span><i class="working"></i>工作中</span>
        <span><i class="completed"></i>已完成</span>
        <span><i class="error"></i>失败</span>
        <strong id="active-summary">等待客户需求</strong>
      </div>

      <div id="orgchart"></div>

      <div class="agent-detail" id="detail"></div>

      <section class="collab-panel" aria-label="团队协作实时信息">
        <div class="collab-header">
          <div class="collab-title">
            <h3>团队协作实时信息</h3>
            <span class="collab-tag">内部视图 · 客户不可见</span>
          </div>
          <span class="collab-status" id="collab-status">待命</span>
        </div>
        <div class="collab-stream" id="collab-stream"></div>
      </section>
    </section>
  </main>

  <div class="toast" id="toast" role="status" aria-live="polite"></div>

  <div class="modal-mask" id="model-modal" hidden>
    <section class="modal-card" role="dialog" aria-modal="true" aria-labelledby="model-modal-title">
      <header class="modal-head">
        <div>
          <span class="eyebrow">MODEL CONNECTION</span>
          <h2 id="model-modal-title">模型设置</h2>
          <p>保存后对下一次问答生效；配置由后端保存，页面不会回显原始 API Key。</p>
        </div>
        <button class="modal-close" id="model-close" type="button" aria-label="关闭模型设置">×</button>
      </header>

      <form class="modal-body" id="model-form">
        <label class="field">
          <span class="field-label">模型服务地址</span>
          <input id="model-api-base" name="apiBase" type="url" autocomplete="off" placeholder="https://api.deepseek.com" required>
        </label>

        <div class="field-row">
          <label class="field">
            <span class="field-label">模型名称</span>
            <input id="model-model-name" name="modelName" type="text" autocomplete="off" placeholder="deepseek-flash" required>
          </label>
          <label class="field">
            <span class="field-label">Provider</span>
            <input id="model-provider" name="provider" type="text" autocomplete="off" placeholder="openai" required>
          </label>
        </div>

        <label class="field">
          <span class="field-label">API Key</span>
          <input id="model-api-key" name="apiKey" type="password" autocomplete="new-password" placeholder="sk-...">
          <span class="field-hint">保存后不会在页面上回显；留空表示沿用已保存的 Key。</span>
        </label>

        <label class="field-check">
          <input id="model-clear-key" type="checkbox">
          <span>清除已保存的 API Key</span>
        </label>

        <div class="modal-status" id="model-status"></div>
        <p class="modal-error" id="model-error" hidden></p>

        <footer class="modal-actions">
          <button type="button" id="model-cancel">取消</button>
          <button type="submit" id="model-save">保存配置</button>
        </footer>
      </form>
    </section>
  </div>`;

/* =========================================================================
 * 2. 运行时状态
 * ========================================================================= */

/** d3 架构图数据：角色表 + 实时状态（每轮开始重置为 idle）。 */
const nodes = ROLES.map((role) => ({...role, status: 'idle', task: ''}));

let chart = null;
let busy = false;
let conversationId = crypto.randomUUID();
let activeAssistant = null;
let processor = null;
let collab = null;
/** /config 返回的模型配置视图（只有掩码/状态，永远不含原始 Key）。 */
let modelConfigView = null;
let savingModelConfig = false;
let toastTimer = null;

/* =========================================================================
 * 3. 通用小工具
 * ========================================================================= */

const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g, (character) => ({
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;'
}[character]));

const formatTime = () => new Date().toLocaleTimeString('zh-CN', {hour: '2-digit', minute: '2-digit'});

const messagesEl = () => $('#messages');

function scrollMessages() {
  const container = messagesEl();
  container.scrollTop = container.scrollHeight;
}

/** 新建一条助手消息壳（头像 + AI 徽标 + 时间 + 等待态 + 分区容器）。 */
function createAssistantMessage() {
  const article = document.createElement('article');
  article.className = 'message assistant';
  article.innerHTML = `
    <div class="message-avatar">◈</div>
    <div class="message-body">
      <div class="message-label">
        <span class="message-author">AI 信贷顾问</span>
        <span class="ai-badge">AI</span>
        <time>${formatTime()}</time>
      </div>
      <div class="message-waiting">
        <span class="typing-dots"><i></i><i></i><i></i></span>
        <span>正在为您核实…</span>
      </div>
      <div class="message-zones"></div>
    </div>`;

  messagesEl().append(article);
  scrollMessages();

  const waiting = article.querySelector('.message-waiting');
  const zones = article.querySelector('.message-zones');
  let resultStarted = false;

  return {
    element: article,
    /** 收到首个结果事件后移除等待态。 */
    startResult() {
      if (resultStarted) return;
      resultStarted = true;
      waiting.remove();
    },
    /** 按到达顺序追加一个分区（结论卡 / 详细说明 / A2UI 卡片 / 错误卡）。 */
    appendZone(zone) {
      zones.append(zone);
      scrollMessages();
    }
  };
}

function createAssistantIfNeeded() {
  if (!activeAssistant) activeAssistant = createAssistantMessage();
  return activeAssistant;
}

function appendUserMessage(text) {
  const article = document.createElement('article');
  article.className = 'message user';
  article.innerHTML = `
    <div class="message-avatar user-avatar">我</div>
    <div class="message-content">${escapeHtml(text).replace(/\n/g, '<br>')}</div>`;
  messagesEl().append(article);
  scrollMessages();
}

/* =========================================================================
 * 4. 客户对话富 UI：结论卡 / Markdown / A2UI / 错误卡
 * ========================================================================= */

/** reply.data.cases -> 结构化结论卡（每个 case 一张）。 */
function showReplyCases(data) {
  const cases = Array.isArray(data?.cases) ? data.cases : [];
  const assistant = createAssistantIfNeeded();
  assistant.startResult();
  cases.forEach((item) => assistant.appendZone(createCaseCard(item)));
}

function createCaseCard(item) {
  const tone = ['ok', 'warn', 'danger', 'info'].includes(item.tone) ? item.tone : 'info';
  const facts = Array.isArray(item.facts) ? item.facts : [];
  const factsHtml = facts.map((fact) => `
    <div class="fact-chip">
      <span class="fact-label">${escapeHtml(fact.label)}</span>
      <strong class="fact-value">${escapeHtml(fact.value)}</strong>
    </div>`).join('');
  const nextStepHtml = item.next_step
    ? `<p class="next-step"><span class="next-step-label">下一步：</span>${escapeHtml(item.next_step)}</p>`
    : '';

  const card = document.createElement('section');
  card.className = 'result-card';
  card.innerHTML = `
    <header class="result-head">
      <span class="result-customer"><span class="result-customer-icon">◈</span>${escapeHtml(item.customer_id || '客户')}</span>
      <span class="status-chip tone-${tone}"><i></i>${escapeHtml(item.status || '')}</span>
    </header>
    ${factsHtml ? `<div class="fact-chips">${factsHtml}</div>` : ''}
    ${nextStepHtml}`;
  return card;
}

/** answer.text -> Markdown 详细说明（marked + DOMPurify）。 */
function showAnswer(text) {
  const assistant = createAssistantIfNeeded();
  assistant.startResult();

  const zone = document.createElement('section');
  zone.className = 'answer-zone';
  zone.innerHTML = '<div class="zone-label">详细说明</div><div class="markdown"></div>';
  zone.querySelector('.markdown').innerHTML = DOMPurify.sanitize(marked.parse(text || ''));
  assistant.appendZone(zone);
}

/** a2ui.messages -> 业务办理卡片（挂在当前助手消息内，旧卡片失效）。 */
function showA2ui(messages) {
  markOldSurfacesStale();
  const assistant = createAssistantIfNeeded();
  assistant.startResult();
  processor.processMessages(normalizeA2uiMessages(messages));
  scrollMessages();
}

function normalizeA2uiMessages(messages) {
  return (Array.isArray(messages) ? messages : []).map((message) => {
    if (!message || !message.createSurface) return message;
    const createSurface = message.createSurface;
    return {
      ...message,
      createSurface: {
        ...createSurface,
        theme: {...createSurface.theme, primaryColor: '#c7000b'}
      }
    };
  });
}

function markOldSurfacesStale() {
  document.querySelectorAll('a2ui-surface').forEach((surface) => {
    surface.dataset.stale = 'true';
    surface.inert = true;
  });
}

function createErrorCard(message) {
  const card = document.createElement('section');
  card.className = 'error-card';
  card.innerHTML = `
    <span class="error-icon">!</span>
    <div class="error-text">
      <strong>处理未完成</strong>
      <p>${escapeHtml(message || '请稍后重试，或新建会话重新发起。')}</p>
    </div>`;
  return card;
}

/** 错误提示卡：流内 error 事件与网络异常共用。 */
function showStreamError(message) {
  const assistant = createAssistantIfNeeded();
  assistant.startResult();
  assistant.appendZone(createErrorCard(message));
  nodes.forEach((node) => {
    if (node.status === 'working') node.status = 'error';
  });
  refreshChart();
}

/** 与当前轮无关的错误（例如确认信息缺失），单独起一条助手消息。 */
function showStandaloneError(message) {
  const assistant = createAssistantMessage();
  assistant.startResult();
  assistant.appendZone(createErrorCard(message));
}

/* =========================================================================
 * 5. A2UI 业务卡片（Lit 0.15 surface + 审批按钮动作）
 * ========================================================================= */

function createProcessor() {
  const instance = new MessageProcessor([basicCatalog]);
  instance.onSurfaceCreated(mountSurface);
  return instance;
}

function mountSurface(surface) {
  const assistant = createAssistantIfNeeded();
  const shell = document.createElement('div');
  shell.className = 'surface-card';
  shell.innerHTML = '<div class="surface-caption">业务办理卡片 <span>A2UI</span></div>';

  const surfaceElement = document.createElement('a2ui-surface');
  surfaceElement.surface = surface;
  shell.append(surfaceElement);
  assistant.appendZone(shell);

  surface.onAction.subscribe((action) => {
    const event = action.action?.event || action.event || action;
    const customerId = event.context?.customer_id || action.context?.customer_id;
    handleDecision(event.name, customerId);
  });
}

/* =========================================================================
 * 6. 模型设置：GET/POST /config + 模态框（不写 localStorage、不回显原始 Key）
 * ========================================================================= */

const MODEL_SOURCE_LABELS = {file: '本地配置文件', env: '环境变量', none: '未配置'};

/** 读取后端模型配置：只拿「是否已配置 + 掩码 + 来源」，永远拿不到原始 Key。 */
async function loadModelConfig() {
  try {
    const response = await fetch('/config', {headers: {'Accept': 'application/json'}});
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    applyModelConfig(await response.json());
  } catch (error) {
    /* 读不到时保持 null：header 显示未配置态，打开弹窗时会再试一次，避免拿空值覆盖后端配置。 */
    console.warn('读取模型配置失败，按未配置处理', error);
    applyModelConfig(null);
  }
}

/** 把 /config 视图同步到 header 按钮、首屏提示与（若已打开的）模态框。 */
function applyModelConfig(view) {
  modelConfigView = view && typeof view === 'object' ? view : null;
  const configured = Boolean(modelConfigView?.hasApiKey);

  const button = $('#model-config');
  button.classList.toggle('is-unconfigured', !configured);
  button.title = configured
    ? `模型设置：${modelConfigView.modelName || '当前模型'} · ${modelConfigView.apiKeyMasked || '已配置 Key'}`
    : '模型设置：尚未配置模型 API Key，点击完成设置';
  button.setAttribute('aria-label', button.title);

  updateConfigHint();
  if (!$('#model-modal').hidden) syncModelForm(false);
}

/** 首屏欢迎语下方的轻提示：仅在未配置 Key 时出现。 */
function updateConfigHint() {
  const hint = document.querySelector('[data-config-hint]');
  if (hint) hint.hidden = Boolean(modelConfigView?.hasApiKey);
}

function modelSourceLabel(source) {
  return MODEL_SOURCE_LABELS[source] || '未知';
}

function syncModelStatus() {
  const status = $('#model-status');
  if (!modelConfigView) {
    status.textContent = '尚未获取当前模型配置，保存后立即生效。';
    return;
  }
  const keyState = modelConfigView.hasApiKey
    ? `已配置（${modelConfigView.apiKeyMasked || '****'}）`
    : '未配置';
  status.textContent = `当前模型：${modelConfigView.modelName || '—'} · 来源：${modelSourceLabel(modelConfigView.source)} · Key：${keyState}`;
}

function hideModelError() {
  const error = $('#model-error');
  error.textContent = '';
  error.hidden = true;
}

function showModelError(message) {
  const error = $('#model-error');
  error.textContent = message || '保存失败，请检查填写内容后重试。';
  error.hidden = false;
}

/** 打开模态框时用当前配置回填表单；API Key 永远不回填（只给掩码 placeholder）。 */
function syncModelForm(resetKey = true) {
  const view = modelConfigView || {};
  $('#model-provider').value = view.provider || 'openai';
  $('#model-api-base').value = view.apiBase || '';
  $('#model-model-name').value = view.modelName || '';

  const keyInput = $('#model-api-key');
  if (resetKey) keyInput.value = '';
  keyInput.placeholder = view.hasApiKey
    ? `已保存 ${view.apiKeyMasked || '****'}，留空表示不修改`
    : 'sk-...';
  keyInput.disabled = false;

  const clearCheckbox = $('#model-clear-key');
  clearCheckbox.checked = false;
  clearCheckbox.disabled = !view.hasApiKey;

  hideModelError();
  syncModelStatus();
}

function openModelConfig() {
  const modal = $('#model-modal');
  if (!modal.hidden) return;

  syncModelForm();
  modal.hidden = false;
  document.body.classList.add('modal-open');
  $('#model-api-base').focus();

  /* 首屏 GET 失败时，打开弹窗再补一次，避免拿着空状态去覆盖后端已有配置。 */
  if (!modelConfigView) loadModelConfig();
}

function closeModelConfig() {
  const modal = $('#model-modal');
  if (modal.hidden) return;
  modal.hidden = true;
  document.body.classList.remove('modal-open');
  hideModelError();
  $('#model-config').focus();
}

function setSavingModelConfig(saving) {
  savingModelConfig = saving;
  const saveButton = $('#model-save');
  saveButton.disabled = saving;
  saveButton.textContent = saving ? '保存中…' : '保存配置';
  $('#model-cancel').disabled = saving;
}

/** 勾选「清除已保存的 Key」时禁用 Key 输入，避免两个字段语义冲突。 */
function syncClearKeyState() {
  const clearing = $('#model-clear-key').checked;
  const keyInput = $('#model-api-key');
  keyInput.disabled = clearing;
  if (clearing) keyInput.value = '';
}

async function saveModelConfig(event) {
  event.preventDefault();
  if (savingModelConfig) return;

  hideModelError();
  setSavingModelConfig(true);

  try {
    const response = await fetch('/config', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({
        provider: $('#model-provider').value.trim(),
        apiBase: $('#model-api-base').value.trim(),
        modelName: $('#model-model-name').value.trim(),
        apiKey: $('#model-api-key').value,
        clearApiKey: $('#model-clear-key').checked
      })
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(data.error || `保存失败（HTTP ${response.status}）`);

    $('#model-api-key').value = '';         // 保存后清空 Key 输入，页面不留明文
    applyModelConfig(data);
    closeModelConfig();
    showToast('模型配置已保存，对下一次问答生效');
  } catch (error) {
    showModelError(error.message);
  } finally {
    setSavingModelConfig(false);
  }
}

function showToast(text) {
  const toast = $('#toast');
  toast.textContent = text;
  toast.classList.add('is-visible');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toast.classList.remove('is-visible'), 2800);
}

/* =========================================================================
 * 7. 右侧 d3 智能体架构图（外观 / 节点 / 连线 / 交互均保持原样）
 * ========================================================================= */

function drawChart() {
  chart = new OrgChart()
    .container('#orgchart')
    .svgHeight($('#orgchart').clientHeight)
    .svgWidth($('#orgchart').clientWidth)
    .duration(0)
    .data(nodes)
    .nodeWidth(() => 176)
    .nodeHeight(() => 88)
    .childrenMargin(() => 36)
    .siblingsMargin(() => 18)
    .compactMarginBetween(() => 22)
    .compactMarginPair(() => 36)
    .neighbourMargin(() => 24)
    .nodeContent((node) => `
      <div class="agent-node ${node.data.status}">
        <div class="node-top">
          <span class="node-icon">${node.data.icon}</span>
          <span class="node-status">${STATUS_LABELS[node.data.status]}</span>
        </div>
        <strong>${node.data.title}</strong>
        <small>${node.data.subtitle}</small>
      </div>`)
    .onNodeClick((node) => showAgentDetail(node.data))
    .linkUpdate(function (node) {
      this.setAttribute('stroke', node.data.status === 'idle' ? '#d8dee7' : node.data.status === 'error' ? '#d64747' : '#c7000b');
      this.setAttribute('stroke-width', node.data.status === 'idle' ? '1.4' : '2');
    })
    .render()
    .expandAll()
    .fit();
}

function refreshChart() {
  if (!chart) return;
  chart.data(nodes).render();

  const workingCount = nodes.filter((node) => node.status === 'working').length;
  const completedCount = nodes.filter((node) => node.status === 'completed').length;
  let summary = '等待客户需求';
  if (workingCount) summary = `${workingCount} 位智能体正在协作`;
  else if (busy) summary = '正在协调任务';
  else if (nodes.some((node) => node.status === 'error')) summary = '本轮协作失败';
  else if (completedCount) summary = '本轮协作已完成';
  $('#active-summary').textContent = summary;
}

function showAgentDetail(agent) {
  $('#detail').innerHTML = `
    <span class="detail-icon">${agent.icon}</span>
    <div>
      <strong>${agent.title} <span class="detail-badge ${agent.status}">${STATUS_LABELS[agent.status]}</span></strong>
      <p>${escapeHtml(agent.task || agent.subtitle)}</p>
    </div>`;
}

function applyAgentState(agentId, state) {
  const node = nodes.find((item) => item.id === agentId);
  if (!node) return;
  node.status = STATUS_VALUES.includes(state) ? state : 'idle';
  refreshChart();
}

/* =========================================================================
 * 8. 请求与 NDJSON 事件流（agent / collab / reply / answer / a2ui / error）
 * ========================================================================= */

/**
 * 无响应看门狗：每收到一条事件就续期，超时后 abort 本轮 fetch，
 * 避免服务端卡死时页面一直停在等待态。
 */
function armRequestWatchdog(controller) {
  let timer = null;
  const arm = () => {
    clearTimeout(timer);
    timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  };
  arm();
  return {touch: arm, stop: () => clearTimeout(timer)};
}

async function readNdjsonStream(body, onEvent) {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  for (;;) {
    const {value, done} = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, {stream: true});
    let newlineIndex;
    while ((newlineIndex = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, newlineIndex).trim();
      buffer = buffer.slice(newlineIndex + 1);
      if (line) onEvent(parseNdjsonLine(line));
    }
  }

  const rest = buffer.trim();
  if (rest) onEvent(parseNdjsonLine(rest));
}

function parseNdjsonLine(line) {
  try {
    return JSON.parse(line);
  } catch (error) {
    console.warn('忽略无法解析的事件行', line);
    return null;
  }
}

/**
 * 单条事件分发。返回 'answer' / 'error' 表示本轮收到终态事件；
 * 其余事件返回 null。collab 事件原样转发给右侧「团队协作实时信息」。
 */
function handleStreamEvent(event) {
  if (!event || typeof event !== 'object') return null;
  collab.handle(event);

  switch (event.type) {
    case 'agent':
      applyAgentState(event.agent, event.state);
      return null;
    case 'collab':
      return null;
    case 'reply':
      showReplyCases(event.data);
      return null;
    case 'answer':
      showAnswer(event.text);
      return 'answer';
    case 'a2ui':
      showA2ui(event.messages);
      return null;
    case 'error':
      showStreamError(event.message);
      return 'error';
    default:
      return null;
  }
}

async function runRequest({text, action, label}) {
  lock(true);
  activeAssistant = createAssistantMessage();
  collab.beginRun(label || text);
  refreshChart();

  let terminal = false;
  let failed = false;

  const controller = new AbortController();
  const watchdog = armRequestWatchdog(controller);

  try {
    const response = await fetch('/chat', {
      method: 'POST',
      headers: {
        'X-Conversation': conversationId,
        ...(action ? {'X-Decision': action} : {})
      },
      body: text,
      signal: controller.signal
    });
    if (!response.ok) throw new Error(`请求失败（HTTP ${response.status}）`);

    await readNdjsonStream(response.body, (event) => {
      watchdog.touch();
      const outcome = handleStreamEvent(event);
      if (outcome === 'answer') terminal = true;
      if (outcome === 'error') {
        terminal = true;
        failed = true;
      }
    });

    if (!terminal) throw new Error('连接提前中断，请稍后重试。');
  } catch (error) {
    const message = error.name === 'AbortError'
      ? '响应超时，请稍后重试或新建会话。'
      : error.message;
    showStreamError(message);
    failed = true;
  } finally {
    watchdog.stop();
    collab.endRun(failed ? 'error' : 'completed');
    lock(false);
    refreshChart();
    scrollMessages();
    $('#query').focus();
  }
}

/* =========================================================================
 * 9. 交互：发送 / 新建会话 / 运行者确认 / 图表控件
 * ========================================================================= */

function lock(isLocked) {
  busy = isLocked;
  document.querySelectorAll('#send, #new, #fit, #zoom-in, #zoom-out').forEach((button) => {
    button.disabled = isLocked;
  });
  $('#query').disabled = isLocked;
  $('#status').textContent = isLocked ? '正在为您核实…' : 'Enter 发送 · Shift + Enter 换行';
  document.querySelectorAll('a2ui-surface').forEach((surface) => {
    surface.inert = isLocked || surface.dataset.stale === 'true';
  });
}

/** 每一轮开始：清空上一轮结论、10 个节点回到 idle。 */
function startNewRound() {
  document.querySelector('.welcome')?.remove();
  nodes.forEach((node) => {
    node.status = 'idle';
    node.task = '';
  });
  activeAssistant = null;
  refreshChart();
}

function handleSubmit(event) {
  event.preventDefault();
  const question = $('#query').value.trim();
  if (!question || busy) return;

  $('#query').value = '';
  startNewRound();
  appendUserMessage(question);
  runRequest({text: question, action: null, label: question});
}

async function handleDecision(action, customerId) {
  if (busy) return;
  if (!DECISION_ACTIONS.includes(action) || !customerId) {
    showStandaloneError('确认信息不完整，请重新查询申请状态。');
    return;
  }

  const label = `${DECISION_LABELS[action] || action}（${customerId}）`;
  startNewRound();
  await runRequest({text: customerId, action, label});
}

function resetConversation() {
  if (busy) return;

  conversationId = crypto.randomUUID();
  activeAssistant = null;
  nodes.forEach((node) => {
    node.status = 'idle';
    node.task = '';
  });

  processor = createProcessor();
  messagesEl().innerHTML = WELCOME_HTML;
  updateConfigHint();
  $('#query').value = '';
  $('#detail').innerHTML = DETAIL_PLACEHOLDER_HTML;

  lock(false);
  refreshChart();
  collab.reset();
  messagesEl().scrollTop = 0;
}

/* =========================================================================
 * 10. 启动
 * ========================================================================= */

$('#app').innerHTML = APP_SHELL;
$('#messages').innerHTML = WELCOME_HTML;
$('#detail').innerHTML = DETAIL_PLACEHOLDER_HTML;

collab = createCollabStream({
  streamEl: $('#collab-stream'),
  statusEl: $('#collab-status'),
  roles: ROLES
});
processor = createProcessor();

drawChart();

$('#form').addEventListener('submit', handleSubmit);

$('#query').addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault();
    $('#form').requestSubmit();
  }
});

/* 欢迎语里的建议问题与「未配置模型」提示都可反复渲染，用事件委托绑定。 */
messagesEl().addEventListener('click', (event) => {
  if (event.target.closest('[data-open-config]')) {
    openModelConfig();
    return;
  }
  const suggestion = event.target.closest('[data-q]');
  if (!suggestion || busy) return;
  $('#query').value = suggestion.dataset.q;
  $('#form').requestSubmit();
});

$('#new').addEventListener('click', resetConversation);

/* 模型设置：打开/关闭（Esc、遮罩、取消）、保存、清除 Key 联动。 */
$('#model-config').addEventListener('click', openModelConfig);
$('#model-close').addEventListener('click', closeModelConfig);
$('#model-cancel').addEventListener('click', closeModelConfig);
$('#model-form').addEventListener('submit', saveModelConfig);
$('#model-clear-key').addEventListener('change', syncClearKeyState);
$('#model-modal').addEventListener('click', (event) => {
  if (event.target === event.currentTarget) closeModelConfig();
});
document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape' && !$('#model-modal').hidden) {
    event.preventDefault();
    closeModelConfig();
  }
});

loadModelConfig();
$('#fit').addEventListener('click', () => chart.fit());
$('#zoom-in').addEventListener('click', () => chart.zoomIn());
$('#zoom-out').addEventListener('click', () => chart.zoomOut());

window.addEventListener('resize', () => {
  if (!chart) return;
  chart
    .svgHeight($('#orgchart').clientHeight)
    .svgWidth($('#orgchart').clientWidth)
    .render()
    .fit();
});
