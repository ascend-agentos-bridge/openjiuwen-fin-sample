/* PROTOTYPE · 演示数据（一次性产品原型，不是生产代码）
 *
 * 产品问题：Agent Memory 服务接入后，「带着记忆回答」和「不带记忆回答」到底差在哪里？
 * 这份数据把一个客户（周砚清 · 经营贷咨询）的 4 步对话脚本拆成：
 *   - 客户原话           → 每步一条
 *   - 记忆单元 unit      → id / tier / lifecycle / version / supersedes / provenance / tags / score
 *   - 记忆操作 op        → extract → update·merge → associate → evolve → forget
 *   - 本轮召回 recall    → query / 改写 / 关键词·向量·图通道 / 命中分数 / 是否注入
 *   - 双回答 answers     → 有记忆（MemoryRail 注入） vs 无记忆（基线）
 *
 * 所有内容均为虚构演示数据，不是真实运行输出，也不连接任何后端。
 * 真实服务（openJiuwen agent-memory，HTTP 127.0.0.1:8137）由 Java 样例接入。
 */
window.ProtoData = {
  meta: {
    product: '信贷智能体',
    subtitle: '客户记忆服务 · 双回答对照',
    prototypeLabel: '演示原型 · 虚构数据',
    customerScene: '经营贷咨询',
    memoryService: 'Agent Memory',
    railName: 'MemoryRail',
    boundary: '页面内的客户、记忆、召回分数与回答均为演示数据，不连接真实 Agent Memory 服务，也不是真实模型输出。'
  },

  /* 左侧服务卡 + 右侧「服务」tab 用同一份服务描述（演示态） */
  service: {
    name: 'Agent Memory',
    status: 'running',
    statusText: '运行中',
    version: '0.2.0',
    deployment: '本地部署 · dev auth',
    api: 'http://127.0.0.1:8137',
    health: 'GET /healthz',
    portal: 'http://127.0.0.1:8137/portal/',
    portalLaunch: 'POST /portal/api/launch',
    portalHint: '带一次性 access token 的完整 Portal 地址，由样例启动脚本 / Java 样例启动时打印。',
    storage: 'SQLite 真源（memory.db） · 向量 / 全文 / 图索引进程内',
    scope: { org: 'local', user: 'C1001', agent: 'credit-agent' },
    uptime: '2 小时 14 分',
    config: [
      { label: '抽取 / 演进 LLM', value: 'deepseek-chat（OpenAI 兼容）' },
      { label: 'Embedding', value: 'nomic-embed-text · 768 维' },
      { label: '检索通道', value: '关键词 BM25 + 向量 + 图索引' },
      { label: '融合 / 重排', value: 'RRF 融合 · 重排关闭' },
      { label: '构建算子', value: 'extractor / abstractor / associator / evolver' }
    ],
    apiCapabilities: [
      { name: 'add', desc: '写入记忆，infer=true 触发抽取' },
      { name: 'search', desc: '混合检索，可返回 trajectory' },
      { name: 'list', desc: '按 scope / 状态 / tier 列出' },
      { name: 'update', desc: '纠正记忆，默认 SUPERSEDE' },
      { name: 'evolve', desc: '后台演进：抽象、关联、升华' },
      { name: 'delete', desc: 'forget / archive 非破坏式治理' }
    ],
    rawDataSteps: [
      'Portal「我的记忆」：按状态（有效 / 纠正历史 / 已遗忘）筛出 unit，打开详情。',
      '详情里可看到 unit_id、tier、lifecycle、provenance、supersedes、来源会话。',
      '需要程序化核对时：GET /v1/list、POST /v1/search（with_trajectory=true）、/v1/inspect。'
    ]
  },

  customer: {
    id: 'C1001',
    name: '周砚清',
    avatar: '周',
    profile: '杭州 · 精密制造企业主',
    topic: '经营贷咨询'
  },

  /* 历史会话；第 1 步发生在会话 1，第 2 步起进入会话 2（跨会话召回） */
  sessions: [
    { id: 1, title: '首次咨询', time: '10:02', memoryDelta: '+6 条', preview: '我是周砚清，在杭州经营精密制造公司…' },
    { id: 2, title: '追问与计划变更', time: '10:07', memoryDelta: '+5 条', preview: '我们上次聊到哪了？我的还款方式是什么？' }
  ],

  tiers: {
    working: { label: 'working', zh: '工作记忆', color: '#64748b', desc: '会话内短时记忆' },
    core: { label: 'core', zh: '核心', color: '#c7000b', desc: '画像 / 长期偏好' },
    semantic: { label: 'semantic', zh: '语义', color: '#1d4ed8', desc: '事实 / 计划' },
    episodic: { label: 'episodic', zh: '情景', color: '#0f766e', desc: '带时间的事件' },
    procedural: { label: 'procedural', zh: '程序性', color: '#b45309', desc: '做法 / 流程' },
    archival: { label: 'archival', zh: '归档', color: '#7c3aed', desc: '冷数据' }
  },

  lifecycle: {
    active: { label: 'active', zh: '有效', color: '#047857', tint: '#ecfdf5' },
    superseded: { label: 'superseded', zh: '已取代', color: '#b45309', tint: '#fffbeb' },
    forgotten: { label: 'forgotten', zh: '已遗忘', color: '#64748b', tint: '#f1f5f9' },
    archived: { label: 'archived', zh: '已归档', color: '#7c3aed', tint: '#f5f3ff' }
  },

  ops: {
    extract: { label: '抽取', zh: 'extract', color: '#1d4ed8', tint: '#eff6ff', icon: '纺' },
    merge: { label: '合并', zh: 'merge', color: '#7c3aed', tint: '#f5f3ff', icon: '合' },
    update: { label: '更新', zh: 'update · supersede', color: '#b45309', tint: '#fffbeb', icon: '替' },
    associate: { label: '关联', zh: 'associate', color: '#0f766e', tint: '#f0fdfa', icon: '联' },
    evolve: { label: '演进', zh: 'evolve', color: '#c7000b', tint: '#fef2f2', icon: '升' },
    forget: { label: '遗忘', zh: 'forget', color: '#64748b', tint: '#f1f5f9', icon: '忘' }
  },

  graphTypes: {
    person: { name: '客户', color: '#c7000b' },
    company: { name: '公司', color: '#0f766e' },
    location: { name: '地区', color: '#64748b' },
    product: { name: '产品', color: '#1d4ed8' },
    preference: { name: '偏好', color: '#b45309' },
    plan: { name: '计划', color: '#0891b2' },
    event: { name: '事件', color: '#be123c' }
  },

  /* ============================================================
   * 演示脚本：4 步。每一步 = 一条客户原话 + 记忆变化 + 召回 + 双回答
   * ============================================================ */

  steps: [
    {
      n: 1,
      id: 'S1',
      title: '首次咨询 · 建立记忆',
      short: '首次咨询',
      session: 1,
      at: '10:02',
      createsSession: false,
      customerText: '你好，我是周砚清，在杭州经营一家精密制造公司。想申请 80 万经营贷、分 36 期，' +
        '最好等额本息；2023 年有一次信用卡逾期，不过已经结清了。',
      systemText: 'Agent Memory 已连接 · 会话开始',
      units: [
        {
          id: 'mu_1101', tier: 'core', memoryClass: 'user_profile',
          content: '客户周砚清，在杭州经营精密制造公司（自述）',
          lifecycle: 'active', version: 1, supersedes: null,
          provenance: [{ label: '对话 #1 · 客户原话', kind: 'raw' }],
          tags: ['身份', '客户画像'],
          entities: ['e_zhou', 'e_hz', 'e_company'],
          session: 'S-20261112-01', tIngest: '2026-11-12 10:02', tEvent: null,
          source: 'add(infer=true) · extractor'
        },
        {
          id: 'mu_1102', tier: 'semantic', memoryClass: 'long_term_memory',
          content: '经营贷申请：80 万元、36 期',
          lifecycle: 'active', version: 1, supersedes: null,
          provenance: [{ label: '对话 #1 · 客户原话', kind: 'raw' }],
          tags: ['贷款需求', '金额', '期限'],
          entities: ['e_zhou', 'e_loan'],
          session: 'S-20261112-01', tIngest: '2026-11-12 10:02', tEvent: null,
          source: 'add(infer=true) · extractor'
        },
        {
          id: 'mu_1103', tier: 'semantic', memoryClass: 'long_term_memory',
          content: '提前结清计划：5 年内',
          lifecycle: 'active', version: 1, supersedes: null,
          provenance: [{ label: '对话 #1 · 客户原话', kind: 'raw' }],
          tags: ['计划', '结清'],
          entities: ['e_zhou', 'e_plan'],
          session: 'S-20261112-01', tIngest: '2026-11-12 10:02', tEvent: null,
          source: 'add(infer=true) · extractor'
        },
        {
          id: 'mu_1104', tier: 'episodic', memoryClass: 'daily_memory',
          content: '2023 年有一次信用卡逾期，客户称已结清（敏感信息）',
          lifecycle: 'active', version: 1, supersedes: null,
          provenance: [{ label: '对话 #1 · 客户原话', kind: 'raw' }],
          tags: ['敏感信息', '逾期', '风险'],
          entities: ['e_zhou', 'e_overdue'],
          session: 'S-20261112-01', tIngest: '2026-11-12 10:02', tEvent: '2023',
          source: 'add(infer=true) · extractor'
        },
        {
          id: 'mu_1105', tier: 'core', memoryClass: 'user_profile',
          content: '还款偏好：等额本息、月供敏感（关注每月还款压力）',
          lifecycle: 'active', version: 1, supersedes: null,
          provenance: [
            { label: '抽取片段 A · 偏好等额本息', kind: 'merge' },
            { label: '抽取片段 B · 关注月供压力', kind: 'merge' }
          ],
          tags: ['偏好', '还款方式'],
          entities: ['e_zhou', 'e_equal', 'e_monthly'],
          session: 'S-20261112-01', tIngest: '2026-11-12 10:02', tEvent: null,
          source: 'merge(dedup) · associator'
        },
        {
          id: 'mu_1107', tier: 'episodic', memoryClass: 'daily_memory',
          content: '2026-11-12 首次咨询经营贷（对话 #1）',
          lifecycle: 'active', version: 1, supersedes: null,
          provenance: [{ label: '会话 S-20261112-01', kind: 'session' }],
          tags: ['沟通记录'],
          entities: [],
          session: 'S-20261112-01', tIngest: '2026-11-12 10:03', tEvent: '2026-11-12',
          source: 'add(infer=true) · extractor'
        }
      ],
      ops: [
        {
          type: 'extract', at: '10:02',
          title: '从首次对话抽取 6 条候选记忆',
          detail: 'extractor 用 LLM 从最近 2 轮消息里抽取身份、贷款需求、结清计划、还款偏好、逾期事件，' +
            '并生成首次沟通情节；其中 2 条偏好片段进入合并。',
          unitIds: ['mu_1101', 'mu_1102', 'mu_1103', 'mu_1104', 'mu_1107']
        },
        {
          type: 'merge', at: '10:02',
          title: '合并 2 条偏好片段 → 1 条画像记忆',
          detail: 'dedup / associator 识别「偏好等额本息」和「关注月供压力」指向同一还款偏好，' +
            '合并为一条用户画像 unit，provenance 保留两个来源片段。',
          unitId: 'mu_1105',
          fromLabels: ['抽取片段 A · 偏好等额本息', '抽取片段 B · 关注月供压力']
        },
        {
          type: 'associate', at: '10:03',
          title: '建立 8 个实体、7 条关系与 1 个情节',
          detail: 'associator 把记忆单元关联到「周砚清 / 杭州 / 精密制造 / 经营贷 / 等额本息 / 月供敏感 / 5 年内结清 / 2023 逾期」8 个实体，' +
            '写入图索引；同时登记一条沟通情节。',
          unitIds: ['mu_1101', 'mu_1102', 'mu_1103', 'mu_1104', 'mu_1105', 'mu_1107'],
          edges: [
            '周砚清 —位于→ 杭州',
            '周砚清 —经营→ 精密制造',
            '周砚清 —申请→ 经营贷（80 万 / 36 期）',
            '周砚清 —偏好→ 等额本息',
            '周砚清 —关注→ 月供敏感',
            '周砚清 —计划→ 5 年内结清',
            '周砚清 —经历→ 2023 年逾期'
          ]
        }
      ],
      graph: {
        addEntities: [
          { id: 'e_zhou', label: '周砚清', type: 'person', unitIds: ['mu_1101', 'mu_1102', 'mu_1105'] },
          { id: 'e_hz', label: '杭州', type: 'location', unitIds: ['mu_1101'] },
          { id: 'e_company', label: '精密制造', type: 'company', unitIds: ['mu_1101'] },
          { id: 'e_loan', label: '经营贷', type: 'product', unitIds: ['mu_1102'] },
          { id: 'e_equal', label: '等额本息', type: 'preference', unitIds: ['mu_1105'] },
          { id: 'e_monthly', label: '月供敏感', type: 'preference', unitIds: ['mu_1105'] },
          { id: 'e_plan', label: '5 年内结清', type: 'plan', unitIds: ['mu_1103'] },
          { id: 'e_overdue', label: '2023逾期', type: 'event', unitIds: ['mu_1104'] }
        ],
        addRelations: [
          { id: 'r_loc', source: 'e_zhou', target: 'e_hz', label: '位于' },
          { id: 'r_own', source: 'e_zhou', target: 'e_company', label: '经营' },
          { id: 'r_apply', source: 'e_zhou', target: 'e_loan', label: '申请 80 万 / 36 期' },
          { id: 'r_pref', source: 'e_zhou', target: 'e_equal', label: '偏好' },
          { id: 'r_mind', source: 'e_zhou', target: 'e_monthly', label: '关注' },
          { id: 'r_plan', source: 'e_zhou', target: 'e_plan', label: '计划' },
          { id: 'r_exp', source: 'e_zhou', target: 'e_overdue', label: '经历' }
        ],
        addEpisodes: [
          { id: 'ep_1', label: '2026-11-12 首次咨询经营贷', at: '10:02' }
        ]
      },
      recall: null,
      answers: {
        memory: '周先生您好，已了解：您计划申请 80 万经营贷、分 36 期，倾向等额本息；' +
          '2023 年有一次已结清的信用卡逾期。我可以先按这个方向帮您估算月供。',
        baseline: '周先生您好，已了解：您计划申请 80 万经营贷、分 36 期，倾向等额本息；' +
          '2023 年有一次已结清的信用卡逾期。我可以先按这个方向帮您估算月供。',
        cited: [],
        injectedCount: 0,
        missing: [],
        diff: [],
        emptyReason: '首轮对话还没有可检索的记忆，本轮没有召回；回答与基线一致。',
        verdict: '首轮无差异——这轮对话结束后，Agent Memory 完成抽取、合并与关联，下一轮才会产生差异。'
      }
    },

    {
      n: 2,
      id: 'S2',
      title: '新会话追问 · 召回分叉',
      short: '追问',
      session: 2,
      at: '10:06',
      createsSession: true,
      systemText: '新会话已开始 · 上一段对话的记忆仍可被 Agent Memory 召回',
      sessionIntro: '新会话已开始。之前对话里记住的信息还在，您可以接着问。',
      customerText: '我们上次聊到哪了？我的还款方式是什么？',
      units: [
        {
          id: 'mu_1108', tier: 'episodic', memoryClass: 'daily_memory',
          content: '2026-11-12 第二次咨询：跨会话追问上次沟通与还款方式',
          lifecycle: 'active', version: 1, supersedes: null,
          provenance: [{ label: '对话 #2 · 本轮提问', kind: 'raw' }],
          tags: ['沟通记录'],
          entities: ['e_zhou'],
          session: 'S-20261112-02', tIngest: '2026-11-12 10:06', tEvent: '2026-11-12',
          source: 'add(infer=true) · extractor'
        }
      ],
      ops: [
        {
          type: 'extract', at: '10:06',
          title: '把本轮追问记为一条沟通情节',
          detail: '本轮对话结束时抽取「跨会话追问」情节，与客户实体关联；回答本身发生在抽取之前。',
          unitIds: ['mu_1108']
        },
        {
          type: 'associate', at: '10:06',
          title: '本轮对话关联到客户节点',
          detail: '新情节挂到「周砚清」实体上，下次可按客户维度回看沟通历史。',
          unitIds: ['mu_1108'],
          edges: ['周砚清 —对话→ 第二次咨询情节']
        }
      ],
      graph: {
        addEntities: [],
        addRelations: [],
        addEpisodes: [
          { id: 'ep_2', label: '2026-11-12 第二次咨询：追问上次沟通', at: '10:06' }
        ]
      },
      recall: {
        query: '我们上次聊到哪了？我的还款方式是什么？',
        rewritten: '周砚清｜经营贷 还款方式 上次沟通',
        keywords: ['还款方式', '上次沟通', '经营贷', '周砚清'],
        channels: [
          { key: 'keyword', label: '关键词 BM25', count: 3 },
          { key: 'vector', label: '向量', count: 4 },
          { key: 'graph', label: '图索引', count: 3 }
        ],
        topK: 4,
        threshold: 0.72,
        latencyMs: 18,
        hits: [
          { unitId: 'mu_1105', score: 0.94, channels: ['vector', 'keyword'], injected: true },
          { unitId: 'mu_1102', score: 0.92, channels: ['vector', 'keyword'], injected: true },
          { unitId: 'mu_1101', score: 0.88, channels: ['vector', 'graph'], injected: true },
          { unitId: 'mu_1107', score: 0.83, channels: ['keyword', 'graph'], injected: true },
          { unitId: 'mu_1103', score: 0.79, channels: ['vector'], injected: false, reason: '超出 top_k' },
          { unitId: 'mu_1104', score: 0.63, channels: ['vector'], injected: false, reason: '低于阈值 0.72' }
        ],
        graphHits: [
          { id: 'e_zhou', label: '周砚清', type: 'person', score: 0.96 },
          { id: 'e_loan', label: '经营贷', type: 'product', score: 0.91 },
          { id: 'e_equal', label: '等额本息', type: 'preference', score: 0.89 }
        ]
      },
      answers: {
        memory: '上次我们聊到：您计划申请 {{80 万经营贷、分 36 期}}，{{偏好等额本息、月供敏感}}；' +
          '还提到 2023 年有一次已结清的信用卡逾期。还款方式就按 {{等额本息}} 来，我先按这个帮您算月供。',
        baseline: '您好，为了帮您确认还款方式，[[方便告诉我贷款金额和期限]]吗？' +
          '[[您更倾向等额本息还是先息后本]]？我再帮您做测算。',
        cited: ['mu_1105', 'mu_1102', 'mu_1101', 'mu_1107'],
        injectedCount: 4,
        missing: ['金额 80 万', '期限 36 期', '还款偏好 等额本息'],
        diff: [
          { aspect: '金额与期限', memory: '80 万 / 36 期', baseline: '未提及，需客户补充' },
          { aspect: '还款方式', memory: '等额本息（月供敏感）', baseline: '反问客户偏好' },
          { aspect: '历史沟通', memory: '复述上次沟通结论', baseline: '不知道有上次沟通' }
        ],
        verdict: '记忆组比基线多 3 处客户事实；基线需要客户再补充 2 项信息才能回答。'
      }
    },

    {
      n: 3,
      id: 'S3',
      title: '计划变更 · 更新与演进',
      short: '计划变更',
      session: 2,
      at: '10:11',
      createsSession: false,
      customerText: '计划改成 3 年内提前结清，其他不变。',
      units: [
        {
          id: 'mu_1109', tier: 'semantic', memoryClass: 'long_term_memory',
          content: '提前结清计划：3 年内（v2，取代「5 年内」）',
          lifecycle: 'active', version: 2, supersedes: 'mu_1103',
          provenance: [
            { label: '更新来源 · 对话 #3 客户原话', kind: 'raw' },
            { label: '上一版本 · mu_1103', kind: 'supersedes' }
          ],
          tags: ['计划', '结清', '变更'],
          entities: ['e_zhou', 'e_plan'],
          session: 'S-20261112-02', tIngest: '2026-11-12 10:11', tEvent: '2026-11-12',
          source: 'update(patch.mode=SUPERSEDE)'
        },
        {
          id: 'mu_1110', tier: 'core', memoryClass: 'user_profile',
          content: '经营贷客户画像：杭州精密制造企业主；80 万 / 36 期；偏好等额本息、关注月供；计划 3 年内结清',
          lifecycle: 'active', version: 1, supersedes: null,
          provenance: [
            { label: 'mu_1101 · 身份', kind: 'evolve' },
            { label: 'mu_1102 · 贷款需求', kind: 'evolve' },
            { label: 'mu_1105 · 还款偏好', kind: 'evolve' },
            { label: 'mu_1109 · 结清计划', kind: 'evolve' }
          ],
          tags: ['客户画像', '演进记忆'],
          entities: ['e_zhou'],
          session: 'S-20261112-02', tIngest: '2026-11-12 10:12', tEvent: null,
          source: 'evolve · abstractor / evolver'
        },
        {
          id: 'mu_1111', tier: 'episodic', memoryClass: 'daily_memory',
          content: '2026-11-12 客户将提前结清计划由 5 年改为 3 年，其他条件不变',
          lifecycle: 'active', version: 1, supersedes: null,
          provenance: [{ label: '对话 #3 · 客户原话', kind: 'raw' }],
          tags: ['变更记录'],
          entities: ['e_zhou'],
          session: 'S-20261112-02', tIngest: '2026-11-12 10:11', tEvent: '2026-11-12',
          source: 'add(infer=true) · extractor'
        }
      ],
      ops: [
        {
          type: 'update', at: '10:11',
          title: '「5 年内结清」被新版本取代',
          detail: 'update(patch.mode=SUPERSEDE)：生成新 unit mu_1109（v2），旧 unit mu_1103 标记 superseded；' +
            '新版 supersedes 指向旧版，版本链可回溯，旧内容不物理删除。',
          oldUnitId: 'mu_1103',
          unitId: 'mu_1109'
        },
        {
          type: 'extract', at: '10:11',
          title: '抽取一条变更情节',
          detail: '把「客户调整结清计划」记为情景记忆，与计划变更实体关联。',
          unitIds: ['mu_1111']
        },
        {
          type: 'associate', at: '10:12',
          title: '更新图索引：计划节点 5 年 → 3 年',
          detail: 'associator 把计划节点改为「3 年内结清」，保留「取代 5 年计划」标记；新增一条「变更」关系。',
          unitIds: ['mu_1109', 'mu_1111'],
          edges: ['周砚清 —计划→ 3 年内结清', '周砚清 —变更→ 提前结清计划调整']
        },
        {
          type: 'evolve', at: '10:12',
          title: '升华生成 1 条客户画像（core）',
          detail: 'evolver 把身份、贷款需求、还款偏好、结清计划 4 条记忆抽象为一条高层画像 unit；' +
            'provenance 记录 4 个来源，后续可沿血缘追溯到原始对话。',
          unitId: 'mu_1110',
          provenance: ['mu_1101', 'mu_1102', 'mu_1105', 'mu_1109']
        }
      ],
      graph: {
        updateEntities: [
          { id: 'e_plan', label: '3 年内结清', type: 'plan', replacedLabel: '5 年内结清', unitIds: ['mu_1109'] }
        ],
        addEntities: [
          { id: 'e_change', label: '计划调整', type: 'event', unitIds: ['mu_1111'] }
        ],
        addRelations: [
          { id: 'r_change', source: 'e_zhou', target: 'e_change', label: '变更' }
        ],
        updateRelations: [
          { id: 'r_plan', label: '计划（已更新）' }
        ],
        addEpisodes: [
          { id: 'ep_3', label: '2026-11-12 结清计划由 5 年改为 3 年', at: '10:11' }
        ]
      },
      recall: {
        query: '计划改成 3 年内提前结清，其他不变。',
        rewritten: '周砚清｜提前结清计划 变更 3 年',
        keywords: ['提前结清', '计划', '3 年', '其他不变'],
        channels: [
          { key: 'keyword', label: '关键词 BM25', count: 4 },
          { key: 'vector', label: '向量', count: 4 },
          { key: 'graph', label: '图索引', count: 3 }
        ],
        topK: 4,
        threshold: 0.72,
        latencyMs: 16,
        hits: [
          { unitId: 'mu_1105', score: 0.95, channels: ['vector', 'keyword'], injected: true },
          { unitId: 'mu_1102', score: 0.93, channels: ['vector', 'keyword'], injected: true },
          { unitId: 'mu_1103', score: 0.90, channels: ['vector', 'graph'], injected: true },
          { unitId: 'mu_1101', score: 0.86, channels: ['vector', 'graph'], injected: true },
          { unitId: 'mu_1104', score: 0.74, channels: ['keyword'], injected: false, reason: '超出 top_k' }
        ],
        graphHits: [
          { id: 'e_zhou', label: '周砚清', type: 'person', score: 0.95 },
          { id: 'e_plan', label: '5 年内结清', type: 'plan', score: 0.92 },
          { id: 'e_loan', label: '经营贷', type: 'product', score: 0.88 }
        ]
      },
      answers: {
        memory: '明白：结清计划从 {{5 年内}} 调整为 {{3 年内}}，其他条件不变（{{80 万 / 36 期 / 等额本息}}）。' +
          '我会按 3 年内结清重新测算总利息和月供。',
        baseline: '好的，可以把提前结清计划调整成 3 年内。[[您原来的计划是几年内结清]]？' +
          '[[贷款金额和期限]]也请再确认一下，我重新帮您算。',
        cited: ['mu_1105', 'mu_1102', 'mu_1103', 'mu_1101'],
        injectedCount: 4,
        missing: ['原计划 5 年内结清', '金额 / 期限保持不变的依据'],
        diff: [
          { aspect: '原计划', memory: '5 年内结清', baseline: '未知，需要反问' },
          { aspect: '新计划', memory: '3 年内（对既有计划的修订）', baseline: '只能复述「3 年」' },
          { aspect: '其他条件', memory: '80 万 / 36 期 / 等额本息不变', baseline: '需要客户重新提供' }
        ],
        verdict: '记忆组把「变更」理解成对既有计划的修订；基线只能把它当成一条孤立的新信息。'
      }
    },

    {
      n: 4,
      id: 'S4',
      title: '遗忘敏感信息 · 生命周期治理',
      short: '遗忘',
      session: 2,
      at: '10:16',
      createsSession: false,
      customerText: '把 2023 年逾期那条忘掉吧，别算进风险里。',
      units: [
        {
          id: 'mu_1112', tier: 'core', memoryClass: 'user_profile',
          content: '经营贷客户画像（v2）：杭州精密制造企业主；80 万 / 36 期；偏好等额本息、关注月供；计划 3 年内结清',
          lifecycle: 'active', version: 2, supersedes: 'mu_1110',
          provenance: [
            { label: 'mu_1101 · 身份', kind: 'evolve' },
            { label: 'mu_1102 · 贷款需求', kind: 'evolve' },
            { label: 'mu_1105 · 还款偏好', kind: 'evolve' },
            { label: 'mu_1109 · 结清计划', kind: 'evolve' },
            { label: 'mu_1104 · 已遗忘，不再引用', kind: 'forgotten' }
          ],
          tags: ['客户画像', '演进记忆', '已剔除敏感信息'],
          entities: ['e_zhou'],
          session: 'S-20261112-02', tIngest: '2026-11-12 10:16', tEvent: null,
          source: 'evolve · 遗忘后重新演进'
        }
      ],
      ops: [
        {
          type: 'forget', at: '10:16',
          title: '遗忘「2023 年逾期」记忆',
          detail: 'delete(mode=forget)：unit 标记 lifecycle=forgotten，默认检索与回答不再使用；' +
            '记录不物理删除，Portal「已遗忘」仍可审计。',
          unitId: 'mu_1104',
          beforeLifecycle: 'active'
        },
        {
          type: 'evolve', at: '10:16',
          title: '画像记忆重新演进为 v2',
          detail: 'evolver 剔除已遗忘的逾期引用，生成 mu_1112（v2）取代 mu_1110；' +
            'provenance 保留「mu_1104 · 已遗忘」以说明这次演进的原因。',
          unitId: 'mu_1112',
          oldUnitId: 'mu_1110',
          provenance: ['mu_1101', 'mu_1102', 'mu_1105', 'mu_1109', 'mu_1104']
        },
        {
          type: 'associate', at: '10:16',
          title: '图索引移除逾期实体与「经历」关系',
          detail: '关联图不再把客户与「2023 年逾期」相连；节点以灰色虚线保留为「已遗忘」标记，' +
            '便于对照，但不参与召回。',
          unitIds: ['mu_1104', 'mu_1112'],
          edges: ['移除：周砚清 —经历→ 2023 年逾期']
        }
      ],
      graph: {
        forgetEntities: ['e_overdue'],
        removeRelations: ['r_exp'],
        addEpisodes: [
          { id: 'ep_4', label: '2026-11-12 客户要求遗忘 2023 年逾期记录', at: '10:16' }
        ]
      },
      recall: {
        query: '把 2023 年逾期那条忘掉吧，别算进风险里。',
        rewritten: '周砚清｜2023 逾期 遗忘 风险判断',
        keywords: ['2023', '逾期', '遗忘', '风险'],
        channels: [
          { key: 'keyword', label: '关键词 BM25', count: 2 },
          { key: 'vector', label: '向量', count: 2 },
          { key: 'graph', label: '图索引', count: 1 }
        ],
        topK: 4,
        threshold: 0.72,
        latencyMs: 15,
        hits: [
          { unitId: 'mu_1104', score: 0.93, channels: ['keyword', 'vector'], injected: true },
          { unitId: 'mu_1105', score: 0.78, channels: ['vector'], injected: true },
          { unitId: 'mu_1101', score: 0.70, channels: ['graph'], injected: false, reason: '低于阈值' }
        ],
        graphHits: [
          { id: 'e_overdue', label: '2023 年逾期', type: 'event', score: 0.93 }
        ]
      },
      answers: {
        memory: '已把「2023 年信用卡逾期」这条记忆标记为{{已遗忘（lifecycle=forgotten）}}，' +
          '后续检索和回答都不会再使用它；这次风险判断只依据当前有效的申请信息与还款偏好。',
        baseline: '可以，后续回答里可以不提逾期。[[不过这是客户自述，方便确认是哪家机构的逾期、当前是否已经结清]]吗？' +
          '我需要这些信息才能判断风险。',
        cited: ['mu_1104', 'mu_1105'],
        injectedCount: 2,
        missing: ['客户已自述的逾期记录', '「已结清」这一状态'],
        diff: [
          { aspect: '遗忘动作', memory: '定位到具体 unit，标记 forgotten', baseline: '无法定位，只能泛泛承诺' },
          { aspect: '后续回答', memory: '明确不再使用该记忆', baseline: '需要客户重新提供细节' },
          { aspect: '风险判断', memory: '基于当前有效记忆重新演进画像', baseline: '缺少客户历史，无法解释依据' }
        ],
        verdict: '记忆组能定位到「哪一条记忆」并完成非破坏式遗忘；基线没有记忆，只能承诺「不再提」。'
      }
    }
  ],

  /* 演示抽屉里给演示者看的说明（与 README 保持一致） */
  demoScript: [
    {
      step: 1,
      title: '首次咨询 · 建立记忆',
      input: '你好，我是周砚清，在杭州经营一家精密制造公司。想申请 80 万经营贷、分 36 期，最好等额本息；2023 年有一次信用卡逾期，不过已经结清了。',
      memory: [
        '抽取 6 条候选：身份、贷款需求、结清计划、逾期事件、首次沟通情节',
        '合并 2 条偏好片段 → 1 条画像记忆（provenance 2 个来源）',
        '关联 8 个实体 / 7 条关系 / 1 个情节'
      ],
      recall: '本轮没有可召回记忆；双回答一致（差异 0 处）。',
      diff: '这轮对话结束后完成首次记忆抽取，下一次对话才会出现差异。'
    },
    {
      step: 2,
      title: '新会话追问 · 召回分叉',
      input: '我们上次聊到哪了？我的还款方式是什么？',
      memory: [
        '新增一条「第二次咨询」沟通情节，挂到客户实体',
        '无新增事实；检索到第 1 步沉淀的 4 条记忆'
      ],
      recall: '三通道命中：关键词 3 / 向量 4 / 图 3，top 4 注入；召回 18ms。',
      diff: '记忆组复述 80 万 / 36 期 / 等额本息 / 上次沟通；基线反问客户补充信息（差异 3 处）。'
    },
    {
      step: 3,
      title: '计划变更 · 更新与演进',
      input: '计划改成 3 年内提前结清，其他不变。',
      memory: [
        '更新（SUPERSEDE）：「5 年内结清」标记 superseded，新版本 supersedes 旧版本',
        '演进（evolve）：4 条记忆升华成 1 条 core 客户画像，provenance 4 个来源',
        '关联：图索引把计划节点 5 年 → 3 年，新增「变更」事件'
      ],
      recall: '三通道命中 5 条 → 注入 4 条；召回 16ms。',
      diff: '记忆组把变更理解为「对既有计划的修订」，其他条件保持不变；基线不知道原计划（差异 3 处）。'
    },
    {
      step: 4,
      title: '遗忘敏感信息 · 生命周期治理',
      input: '把 2023 年逾期那条忘掉吧，别算进风险里。',
      memory: [
        '遗忘（forget）：mu_1104 标记 lifecycle=forgotten，不物理删除',
        '重新演进：画像 v2 不再引用逾期记录，provenance 标注「已遗忘」',
        '关联：图索引移除「经历」关系，节点以虚线保留'
      ],
      recall: '命中 3 条 → 注入 2 条（逾期记录用于定位遗忘对象）；召回 15ms。',
      diff: '记忆组定位到具体记忆并非破坏式遗忘，风险判断只依据有效记忆；基线只能承诺不再提（差异 3 处）。'
    }
  ]
};
