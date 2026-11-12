const el = id => document.getElementById(id);
const state = { bootstrap:null, sessionId:null, messages:[], memories:[], lastRecall:[], tab:'memory' };

const SCENARIOS = [
  {
    id: 'cross-session', title: '① 跨会话召回（保留）',
    desc: '先留下一条客户陈述，再开新会话追问；对比有记忆与无记忆基线。',
    steps: [
      {title:'第 1 步 · 首轮陈述', text:'我是林嘉禾，在杭州经营一家跨境电商公司，是财务负责人。想申请120万元经营贷，期限24期，只接受等额本息还款，不接受先息后本；计划2年内提前结清。', action:'fill'},
      {title:'第 2 步 · 等加工完成后，开新会话', text:'点击后自动新建会话；稍等 3–5 秒让 Agent Memory 抽取完成。', action:'new-session'},
      {title:'第 3 步 · 跨会话追问', text:'我上次说的额度、期限和还款方式是什么？', action:'fill'}
    ]
  },
  {
    id: 'preference-recall', title: '② 偏好召回 · 个性化推荐',
    demo: {
      seed: '我更在意月供压力，只接受等额本息，不接受前期只还利息、到期一次性还本的先息后本。',
      question: '结合我的情况，给我推荐一个合适的还款方案，并说明为什么适合我。'
    },
    desc: '先写入长期偏好，再问方案推荐；对比有记忆的个性化回答与无记忆基线的泛化回答。',
    steps: [
      {title:'第 1 步 · 背景记忆（自动写入）', text:'我更在意月供压力，只接受等额本息，不接受先息后本。'},
      {title:'第 2 步 · 客户追问', text:'结合我的情况，推荐一个合适的还款方案，并说明为什么适合我。'},
      {title:'第 3 步 · 一键演示召回', text:'自动写入背景 → 真实双回答 → 看有记忆回答是否体现客户偏好。', action:'auto-recall'}
    ]
  },
  {
    id: 'risk-recall', title: '③ 风险召回 · 主动提示',
    demo: {
      seed: '我2023年有过一次信用卡逾期，金额不大，当时已经结清了。',
      question: '以我的情况现在申请经营贷，审批时有什么需要特别注意的？'
    },
    desc: '先写入历史风险事件，再问审批注意事项；有记忆应主动提到“逾期已结清”，基线只能泛化追问。',
    steps: [
      {title:'第 1 步 · 背景记忆（自动写入）', text:'我2023年有过一次信用卡逾期，金额不大，当时已经结清了。'},
      {title:'第 2 步 · 客户追问', text:'以我的情况现在申请经营贷，审批时有什么需要特别注意的？'},
      {title:'第 3 步 · 一键演示召回', text:'自动写入背景 → 真实双回答 → 看有记忆回答是否主动复用逾期信息。', action:'auto-recall'}
    ]
  },
  {
    id: 'copartner-recall', title: '④ 关系召回 · 免重复询问',
    demo: {
      seed: '我太太林岚是共同借款人，她也在杭州工作，申请时我们一起提供材料。',
      question: '申请经营贷需要准备哪些共同借款人的材料？'
    },
    desc: '先写入共同借款人关系，再问材料清单；有记忆应直接称呼林岚/复用关系，基线则需要重新询问。',
    steps: [
      {title:'第 1 步 · 背景记忆（自动写入）', text:'我太太林岚是共同借款人，她也在杭州工作。'},
      {title:'第 2 步 · 客户追问', text:'申请经营贷需要准备哪些共同借款人的材料？'},
      {title:'第 3 步 · 一键演示召回', text:'自动写入背景 → 真实双回答 → 看有记忆回答是否复用共同借款人关系。', action:'auto-recall'}
    ]
  }


];

const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const get = async (url, options) => {
  const res = await fetch(url, options);
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
  return data;
};
function showError(message){ el('error').hidden = !message; el('error').textContent = message || ''; }
function setBusy(busy){ el('typing').hidden = !busy; el('send').disabled = busy; el('input').disabled = busy; }
function portal(url){ return url || (state.bootstrap?.agentMemory?.baseUrl || 'http://127.0.0.1:8137') + '/portal/'; }

async function bootstrap(){
  state.bootstrap = await get('/api/bootstrap');
  const am = state.bootstrap.agentMemory || {};
  el('am-chip').textContent = am.reachable ? 'Agent Memory 已连接' : 'Agent Memory 不可达';
  el('am-chip').className = 'chip ' + (am.reachable ? '' : 'off');
  el('service-dot').className = 'dot ' + (am.reachable ? 'ok' : 'bad');
  el('service-url').textContent = am.baseUrl || '';
  el('service-health').textContent = am.reachable ? '运行中' : '连接失败';
  el('svc-url').textContent = am.baseUrl || '-';
  el('svc-model').textContent = state.bootstrap.chatModel?.model || '-';
  const p = portal(am.portalUrl);
  ['portal-link','portal-link-2','portal-link-3'].forEach(id => { el(id).href = p; });
  el('svc-portal').href = p;
  renderSessions();
  const active = state.bootstrap.activeSessionId || state.bootstrap.sessions?.slice(-1)[0]?.id;
  if (active) await loadSession(active);
  await refreshMemories();
}
function renderSessions(){
  const box = el('sessions'); box.innerHTML = '';
  (state.bootstrap?.sessions || []).slice().reverse().forEach(s => {
    const div = document.createElement('div');
    div.className = 'session' + (s.id === state.sessionId ? ' active' : '');
    div.innerHTML = '<b>' + esc(s.title) + '</b><small>' + esc(s.createdAt) + ' · ' + s.messageCount + ' 条消息</small><small>' + esc(s.preview || '') + '</small>';
    div.onclick = () => loadSession(s.id);
    box.appendChild(div);
  });
}
async function loadSession(id){
  state.sessionId = id;
  const data = await get('/api/sessions/' + encodeURIComponent(id));
  state.messages = data.messages || [];
  state.memories = data.memories || [];
  el('chat-title').textContent = data.session?.title || '新的咨询';
  el('chat-meta').textContent = data.session ? ((data.session.messageCount || 0) + ' 条消息') : '';
  renderMessages(); renderMemories(); renderSessions();
}
function renderMessages(){
  const box = el('messages'); box.innerHTML = '';
  state.messages.forEach(m => {
    if (m.role === 'customer') {
      box.insertAdjacentHTML('beforeend', '<div class="msg customer"><div class="bubble">' + esc(m.text) + '</div></div>');
    } else if (m.withMemoryAnswer || m.baselineAnswer) {
      const hits = (m.recalledHits || []).map(h => '<span class="cite" title="' + esc(h.unitId) + '">' + esc(h.content) + '</span>').join('');
      box.insertAdjacentHTML('beforeend',
        '<div class="msg agent"><div class="dual">' +
        '<div class="answer mem"><div class="answer-head"><span>有记忆 · MemoryRail</span><span>' + (m.prefetchCount || 0) + ' 条召回</span></div>' +
        '<div class="answer-body">' + esc(m.withMemoryAnswer || '') + '</div>' +
        '<div class="cites">' + (hits || '<span class="empty-cite">本轮没有命中记忆</span>') + '</div></div>' +
        '<div class="answer base"><div class="answer-head"><span>无记忆 · 基线</span><span>对照</span></div>' +
        '<div class="answer-body">' + esc(m.baselineAnswer || '') + '</div></div>' +
        '</div></div>');
    } else {
      box.insertAdjacentHTML('beforeend', '<div class="msg agent"><div class="bubble">' + esc(m.text) + '</div></div>');
    }
  });
  box.scrollTop = box.scrollHeight;
}
const MEMORY_LAYERS = [
  { id:'profile', name:'用户画像', hint:'身份、家庭、企业、长期偏好', color:'#c7000b' },
  { id:'semantic', name:'语义记忆', hint:'与时间无关的事实、概念、计划', color:'#0f766e' },
  { id:'episodic', name:'情景记忆', hint:'带时间的沟通、事件、申请记录', color:'#1d4ed8' },
  { id:'summary', name:'摘要记忆', hint:'对话结束或演进后的摘要/升华', color:'#b45309' },
  { id:'variable', name:'变量记忆', hint:'金额、期限、还款方式等结构化字段', color:'#6d28d9' }
];
function memoryLayer(unit){
  const meta = unit.systemMetadata || {};
  const tier = String(meta._tier || '').toLowerCase();
  const target = String(meta.target || '').toLowerCase();
  const title = String(unit.title || '');
  const text = title + ' ' + String(unit.content || '');
  // 优先使用 Agent Memory 返回的 target/tier，内容规则只做兜底。
  if (tier === 'core' || /摘要|总结/.test(title)) return 'summary';
  if (target === 'preference') return 'profile';
  if (/\d+\s*(万|元|期|年|%)/.test(text)
      && /额度|金额|期限|申请|方案|利率|月供|还款/.test(text)
      && target !== 'event') return 'variable';
  if (target === 'event' || tier === 'episodic') return 'episodic';
  if (target === 'fact' || target === 'plan' || tier === 'semantic') return 'semantic';
  if (tier === 'procedural' || /流程|步骤|做法|承诺/.test(text)) return 'semantic';
  if (/偏好|倾向|更在意|不接受|习惯|画像/.test(text)) return 'profile';
  if (/计划|结清|需求|贷款|经营贷|额度|期限/.test(text)) return 'semantic';
  return 'semantic';
}

function renderMemories(){
  const box = el('memories');
  el('mem-count').textContent = String(state.memories.length);
  const grouped = new Map(MEMORY_LAYERS.map(layer => [layer.id, []]));
  state.memories.forEach(unit => grouped.get(memoryLayer(unit)).push(unit));
  el('memory-summary').textContent = state.memories.length + ' 条 · ' + [...grouped.values()].filter(v => v.length).length + ' 个分层有记忆';
  const recalled = new Set((state.lastRecall || []).map(h => h.unitId));
  box.innerHTML = MEMORY_LAYERS.map(layer => {
    const items = grouped.get(layer.id);
    const cards = items.map(m => {
      const active = m.lifecycle === 'active';
      const badges = [];
      if (!active) badges.push('<span class="life life-superseded">' + esc(m.lifecycle || 'history') + '</span>');
      if (m.supersedes) badges.push('<span class="life life-updated">已更新</span>');
      if (recalled.has(m.unitId)) badges.push('<span class="life life-used">本轮用到</span>');
      const tier = (m.systemMetadata && m.systemMetadata._tier) || '?';
      const target = (m.systemMetadata && m.systemMetadata.target) || '?';
      badges.push('<span class="life life-evidence" title="Agent Memory 返回的 tier / target">' + esc(tier) + ' · ' + esc(target) + '</span>');
      return '<div class="memory-item ' + (active ? '' : 'superseded') + '">' +
        '<div class="memory-top"><span class="memory-layer-title">' + esc(m.title || '记忆单元') + '</span><span class="memory-badges">' + badges.join('') + '</span></div>' +
        '<div class="memory-content">' + esc(m.content) + '</div>' +
        '<div class="memory-meta">' + esc(m.unitId) + (m.supersedes ? ' · supersedes=' + esc(m.supersedes) : '') + '</div>' +
        '<div class="memory-actions"><button class="link-btn" data-forget="' + esc(m.unitId) + '">遗忘</button></div>' +
        '</div>';
    }).join('');
    return '<section class="memory-layer"><div class="memory-layer-head">' +
      '<span class="layer-dot" style="background:' + layer.color + '"></span><b>' + layer.name + '</b>' +
      '<span class="layer-count">' + items.length + '</span><span class="layer-hint">' + layer.hint + '</span>' +
      '<span class="layer-store">Agent Memory</span></div>' +
      (cards || '<div class="layer-empty">还没有这一类记忆</div>') + '</section>';
  }).join('');
  box.querySelectorAll('[data-forget]').forEach(btn => btn.onclick = async () => {
    if (!window.confirm('确认遗忘这条记忆？')) return;
    try {
      showError('');
      await get('/api/memory/forget', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({unitId:btn.dataset.forget})});
      await refreshMemories();
    } catch (e) { showError(e.message); }
  });
}

async function refreshMemories(){
  try {
    const data = await get('/api/memories');
    state.memories = data.items || [];
    renderMemories();
  } catch (e) { showError(e.message); }
}

function renderRecall(hits){
  state.lastRecall = hits || [];
  el('recall').innerHTML = state.lastRecall.length ? state.lastRecall.map(h =>
    '<div class="recall-item"><div>' + esc(h.content) + '</div><div class="recall-score">score=' + Number(h.score).toFixed(4) + ' · unit=' + esc(h.unitId) + '</div></div>'
  ).join('') : '<div class="muted small">本轮还没有召回。</div>';
}
async function send(){
  const input = el('input');
  const message = input.value.trim();
  if (!message || !state.sessionId) return;
  showError(''); input.value = ''; setBusy(true);
  state.messages.push({role:'customer', text:message});
  renderMessages();
  try {
    const data = await get('/api/chat', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({sessionId:state.sessionId, message})});
    const answer = data.answer || {};
    state.messages.push({role:'agent', text:answer.withMemoryAnswer || '', withMemoryAnswer:answer.withMemoryAnswer, baselineAnswer:answer.baselineAnswer, prefetchCount:answer.prefetchCount, recalledHits:answer.recalledHits || []});
    state.memories = data.memories || [];
    renderMessages(); renderMemories(); renderRecall(answer.recalledHits || []);
    await bootstrapSessionsOnly();
  } catch (e) { showError(e.message); }
  finally { setBusy(false); }
}
async function bootstrapSessionsOnly(){
  try {
    const data = await get('/api/bootstrap');
    state.bootstrap = data;
    renderSessions();
  } catch (e) { /* ignore */ }
}
async function newSession(){
  try {
    const data = await get('/api/sessions', {method:'POST', headers:{'Content-Type':'application/json'}, body:'{}'});
    await bootstrapSessionsOnly();
    await loadSession(data.sessionId);
  } catch (e) { showError(e.message); }
}
el('send').onclick = send;
el('input').addEventListener('keydown', e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } });
el('new-session').onclick = newSession;
el('refresh').onclick = refreshMemories;
document.querySelectorAll('.tab').forEach(tab => tab.onclick = () => {
  document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
  tab.classList.add('active');
  document.querySelectorAll('.tab-body').forEach(b => b.classList.add('hidden'));
  el('tab-' + tab.dataset.tab).classList.remove('hidden');
});
function setEvolveStatus(text){
  const box = el('evolve-status');
  if (!box) return;
  box.hidden = !text;
  box.textContent = text || '';
}
function currentScenario(){ return SCENARIOS.find(s => s.id === el('scenario-select').value) || SCENARIOS[0]; }
function renderScenario(){
  const scenario = currentScenario();
  el('scenario-desc').textContent = scenario.desc;
  const box = el('scenario-steps'); box.innerHTML = '';
  scenario.steps.forEach(step => {
    const li = document.createElement('li');
    li.innerHTML = '<b>' + esc(step.title) + '</b><span>' + esc(step.text) + '</span>';
    li.onclick = async () => {
      if (step.action === 'fill') { el('input').value = step.text; el('input').focus(); }
      else if (step.action === 'new-session') { await newSession(); }
      else if (step.action === 'auto-recall') { await runAutoRecall(); }
      else { showError('按说明在右侧操作，或继续输入客户消息。'); setTimeout(() => showError(''), 2500); }
    };
    box.appendChild(li);
  });
}

async function runAutoRecall(){
  const scenario = currentScenario();
  const demo = scenario.demo || {};
  if (!demo.seed || !demo.question) return;
  try {
    setBusy(true);
    setEvolveStatus('正在自动写入背景记忆并生成有/无记忆双回答…');
    const data = await get('/api/demo/recall', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({seed:demo.seed, question:demo.question})});
    const answer = data.answer || {};
    state.memories = data.memories || state.memories;
    state.messages.push({role:'customer', text:demo.question});
    state.messages.push({role:'agent', withMemoryAnswer:answer.withMemoryAnswer, baselineAnswer:answer.baselineAnswer,
      prefetchCount:answer.prefetchCount, recalledHits:answer.recalledHits || []});
    renderMessages(); renderMemories(); renderRecall(answer.recalledHits || []);
    setEvolveStatus('已自动写入背景并完成双回答：有记忆召回 ' + (answer.prefetchCount || 0) + ' 条，请对照两侧回答。');
    setTimeout(() => setEvolveStatus(''), 8000);
  } catch (e) {
    setEvolveStatus('');
    showError(e.message);
  } finally {
    setBusy(false);
  }
}


el('scenario-select').innerHTML = SCENARIOS.map(s => '<option value="' + s.id + '">' + esc(s.title) + '</option>').join('');
el('scenario-select').onchange = renderScenario;
renderScenario();

function openSettings(){
  const st = state.bootstrap?.settings || {};
  const c = st.chat || {}, am = st.agentMemory || {};
  el('chat-base').value = c.apiBase || ''; el('chat-model').value = c.model || ''; el('chat-temp').value = c.temperature ?? 0.2;
  el('am-llm-base').value = am.llmBaseUrl || ''; el('am-llm-model').value = am.llmModel || '';
  el('am-embed-base').value = am.embedderBaseUrl || ''; el('am-embed-model').value = am.embedderModel || '';
  ['chat-key','am-embed-key'].forEach(id => el(id).value = '');
  el('am-embed-key').value = 'lm-studio';
  el('settings-msg').hidden = true;
  el('settings-mask').hidden = false;
}
function closeSettings(){ el('settings-mask').hidden = true; }
async function saveSettings(){
  try {
    const payload = {
      chat: {apiBase:el('chat-base').value.trim(), model:el('chat-model').value.trim(), temperature:Number(el('chat-temp').value || 0.2), apiKey:el('chat-key').value.trim()},
      agentMemory: {llmBaseUrl:el('am-llm-base').value.trim(), llmModel:el('am-llm-model').value.trim(), llmApiKey:el('chat-key').value.trim(), embedderBaseUrl:el('am-embed-base').value.trim(), embedderModel:el('am-embed-model').value.trim(), embedderApiKey:(el('am-embed-key').value.trim() || 'lm-studio')}
    };
    const res = await get('/api/settings', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(payload)});
    el('settings-msg').hidden = false; el('settings-msg').textContent = res.message || '已保存';
  } catch (e) { showError(e.message); }
}
async function resetAll(){
  if (!window.confirm('将清空当前客户在 Agent Memory 中的全部记忆，并重开会话。确定继续？')) return;
  try {
    await get('/api/reset', {method:'POST', headers:{'Content-Type':'application/json'}, body:'{}'});
    await bootstrap();
    showError('已清空记忆，可以重新开始演示。'); setTimeout(() => showError(''), 4000);
  } catch (e) { showError(e.message); }
}
el('settings-btn').onclick = openSettings;
el('settings-mask').onclick = e => { if (e.target === e.currentTarget) closeSettings(); };
document.addEventListener('keydown', e => { if (e.key === 'Escape' && !el('settings-mask').hidden) closeSettings(); });
el('settings-close').onclick = closeSettings;
el('settings-cancel').onclick = closeSettings;
el('settings-save').onclick = saveSettings;
el('reset-btn').onclick = resetAll;
bootstrap().catch(e => showError('加载失败：' + e.message + '。请确认 Agent Memory 服务与 Java 后端已启动。'));
