/* PROTOTYPE · 信贷智能体：客户对话与记忆演示数据
 *
 * 产品视角：左侧是客户与智能体的真实对话，右侧是智能体从对话中沉淀下来的记忆，
 * 以及每类记忆存在哪里（本机 SQLite 数据库，无需外部服务）。
 *
 * 说明：客户与业务数据均为虚构；记忆分类与存储映射来自 Java Agent Core 记忆引擎调研，
 *      技术细节（SDK 类名 / SQLite 表名 / 版本约束）只放在 research/ 文档与 README 中，
 *      不进入这个面向业务演示的界面。
 */
window.ProtoData = {
  meta: {
    title: '信贷智能体',
    subtitle: '客户对话中的长期记忆',
    prototypeLabel: '演示原型',
    disclaimer: '页面内客户与业务数据均为虚构，不连接真实系统。',
    storageTitle: '记忆存储',
    storageDesc: '长期记忆在本机 SQLite；图记忆当前为进程内 InMemory（Milvus 待 0.1.17）。'
  },

  customer: {
    id: 'C1001',
    name: '周砚清',
    avatar: '周',
    profile: '杭州 · 精密制造企业主',
    topic: '经营贷咨询'
  },

  sessionIds: { 1: 'S-20261111-01', 2: 'S-20261111-02' },

  /* 左侧会话列表里展示的名称 */
  sessionMeta: {
    1: { title: '经营贷咨询', subtitle: '首次沟通' },
    2: { title: '还款方式确认', subtitle: '继续咨询' }
  },

  /* 右侧记忆库的分类：名称、颜色、存储位置（业务语言） */
  categories: [
    {
      id: 'profile',
      name: '用户画像',
      hint: '客户身份与偏好',
      storage: '本机数据库 · 用户画像',
      color: '#c7000b',
      tint: '#fef2f2'
    },
    {
      id: 'semantic',
      name: '语义记忆',
      hint: '事实、概念与计划（无明确时间）',
      storage: '本机数据库 · 语义记忆',
      color: '#0f766e',
      tint: '#f0fdfa'
    },
    {
      id: 'episodic',
      name: '情景记忆',
      hint: '带时间点的事件与沟通记录',
      storage: '本机数据库 · 情景记忆',
      color: '#1d4ed8',
      tint: '#eff6ff'
    },
    {
      id: 'summary',
      name: '摘要记忆',
      hint: '一轮对话结束后的压缩摘要',
      storage: '本机数据库 · 摘要记忆',
      color: '#b45309',
      tint: '#fffbeb'
    },
    {
      id: 'variable',
      name: '变量记忆',
      hint: '金额、期数等结构化字段',
      storage: '本机数据库 · 变量记忆',
      color: '#15803d',
      tint: '#f0fdf4'
    }
  ],

  variableLabels: {
    customerId: '客户号',
    product: '产品',
    amount: '金额',
    term: '期数',
    plannedYears: '结清计划'
  },

  /* 图记忆：实体类型、默认模型服务配置、记忆加工阶段 */
  graphTypes: {
    person: { name: '人物', color: '#c7000b' },
    company: { name: '企业', color: '#0f766e' },
    location: { name: '所在地', color: '#64748b' },
    product: { name: '产品', color: '#1d4ed8' },
    preference: { name: '偏好', color: '#b45309' },
    plan: { name: '计划', color: '#0891b2' },
    event: { name: '事件', color: '#be123c' }
  },

  defaultModelConfig: {
    provider: 'OpenAI 兼容',
    apiBase: 'https://api.deepseek.com',
    apiKey: 'sk-demo-local',
    chatModel: 'deepseek-chat',
    embeddingModel: 'text-embedding-3-small',
    rerankerModel: '',
    temperature: 0.2,
    milvusUri: 'http://localhost:19530',
    configured: true
  },

  pipelineStages: [
    { key: 'ingest', label: '对话进入加工队列', desc: '对话结束后由应用侧异步触发' },
    { key: 'analyze', label: 'LLM 记忆分析', desc: '识别变量、摘要意图与关键信息' },
    { key: 'extract', label: '结构化抽取', desc: '抽取用户画像/语义/情景 + 图实体/关系' },
    { key: 'merge', label: '去重与合并', desc: '与已有记忆、图对象比对并合并冲突' },
    { key: 'write', label: '写入存储', desc: 'SQLite KV / 向量索引 / Milvus 图存储' },
    { key: 'ready', label: '可召回', desc: '完成，下一轮对话可被检索' }
  ],

  /* 客户与智能体的对话剧本；每一步对应右侧一次记忆变化 */
  turns: [
    {
      id: 'T1', session: 1, role: 'customer',
      text: '你好，我是周砚清，在杭州做精密制造。想申请 80 万经营贷，分 36 期。',
      writes: [
        { memId: 'm-prof-identity', layer: 'profile',
          content: '周砚清，杭州精密制造企业主',
          memType: 'USER_PROFILE' },
        { memId: 'm-sem-loan', layer: 'semantic',
          content: '想申请 80 万经营贷，分 36 期',
          memType: 'SEMANTIC_MEMORY' },
        { memId: 'm-epi-first-contact', layer: 'episodic',
          content: '2026-11-11 第一次咨询经营贷',
          memType: 'EPISODIC_MEMORY' }
      ],
      vars: [
        { name: 'customerId', value: 'C1001' },
        { name: 'product', value: '经营贷' },
        { name: 'amount', value: '80 万' },
        { name: 'term', value: '36 期' }
      ],
      graph: {
        entities: [
          { id: 'e-zhouyanqing', label: '周砚清', type: 'person' },
          { id: 'e-hangzhou', label: '杭州', type: 'location' },
          { id: 'e-precision', label: '精密制造', type: 'company' },
          { id: 'e-business-loan', label: '经营贷', type: 'product' }
        ],
        relations: [
          { id: 'r-based-in', source: 'e-zhouyanqing', target: 'e-hangzhou', label: '位于' },
          { id: 'r-runs', source: 'e-zhouyanqing', target: 'e-precision', label: '经营' },
          { id: 'r-applies', source: 'e-zhouyanqing', target: 'e-business-loan', label: '申请 80 万 / 36 期' }
        ],
        episodes: [
          { id: 'ep-first-contact', label: '2026-11-11 首次咨询经营贷' }
        ]
      },
      assistant: {
        text: '周先生您好，已记下您的申请：80 万经营贷、36 期。您更在意月供压力，还是总利息？'
      }
    },
    {
      id: 'T2', session: 1, role: 'customer',
      text: '我更在意月供压力，最好等额本息；计划 5 年内提前还清。',
      writes: [
        { memId: 'm-prof-repay', layer: 'profile',
          content: '还款偏好：等额本息，月供敏感',
          memType: 'USER_PROFILE' },
        { memId: 'm-sem-plan5y', layer: 'semantic',
          content: '计划 5 年内提前结清',
          memType: 'SEMANTIC_MEMORY' }
      ],
      vars: [{ name: 'plannedYears', value: '5 年' }],
      graph: {
        entities: [
          { id: 'e-equal-principal', label: '等额本息', type: 'preference' },
          { id: 'e-plan-5y', label: '5 年内结清', type: 'plan' }
        ],
        relations: [
          { id: 'r-prefers', source: 'e-zhouyanqing', target: 'e-equal-principal', label: '偏好' },
          { id: 'r-plan', source: 'e-zhouyanqing', target: 'e-plan-5y', label: '计划' }
        ],
        episodes: []
      },
      assistant: {
        text: '好的，记住了：等额本息、5 年内提前结清。后续我会按这个偏好帮您分析。'
      }
    },
    {
      id: 'T3', session: 1, role: 'customer',
      text: '另外，我 2023 年有一次信用卡逾期，不过已经结清了；先息后本我不考虑。',
      writes: [
        { memId: 'm-sem-no-interest-first', layer: 'semantic',
          content: '不接受先息后本',
          memType: 'SEMANTIC_MEMORY' },
        { memId: 'm-epi-overdue', layer: 'episodic',
          content: '2023 年信用卡逾期 1 次，已结清（客户自述）',
          memType: 'EPISODIC_MEMORY' }
      ],
      graph: {
        entities: [
          { id: 'e-overdue-2023', label: '2023 逾期', type: 'event' },
          { id: 'e-no-interest-first', label: '不接受先息后本', type: 'preference' }
        ],
        relations: [
          { id: 'r-experienced', source: 'e-zhouyanqing', target: 'e-overdue-2023', label: '经历' },
          { id: 'r-refuses', source: 'e-zhouyanqing', target: 'e-no-interest-first', label: '拒绝' }
        ],
        episodes: [
          { id: 'ep-overdue', label: '2023 年信用卡逾期 1 次，已结清' }
        ]
      },
      assistant: {
        text: '已记录。逾期属于敏感信息，您随时可以让我删除。'
      }
    },
    {
      id: 'T4', session: 2, role: 'customer',
      text: '我们上次聊到哪了？再帮我确认一下还款方式。',
      recall: { query: '周砚清 还款方式 上次沟通' },
      assistant: {
        text: '记得的：您申请 80 万经营贷、36 期；偏好等额本息、月供敏感；计划 5 年内提前结清，' +
              '不接受先息后本。您 2023 年有一次已结清的信用卡逾期。上次沟通到确认还款偏好这一步。'
      }
    },
    {
      id: 'T5', session: 2, role: 'customer',
      text: '我想改成 3 年内结清。',
      updates: [
        { memId: 'm-sem-plan5y', content: '计划 3 年内提前结清' }
      ],
      writes: [
        { memId: 'm-epi-change-plan', layer: 'episodic',
          content: '2026-11-11 客户把提前结清计划从 5 年改为 3 年',
          memType: 'EPISODIC_MEMORY' }
      ],
      vars: [{ name: 'plannedYears', value: '3 年' }],
      graph: {
        entities: [
          { id: 'e-plan-3y', label: '3 年内结清', type: 'plan' },
          { id: 'e-change-event', label: '计划变更', type: 'event' }
        ],
        relations: [
          { id: 'r-change', source: 'e-zhouyanqing', target: 'e-change-event', label: '变更' }
        ],
        updates: {
          relations: [
            { id: 'r-plan', target: 'e-plan-3y', label: '计划' }
          ]
        },
        removeEntities: ['e-plan-5y'],
        episodes: [
          { id: 'ep-change-plan', label: '2026-11-11 提前结清计划从 5 年改为 3 年' }
        ]
      },
      assistant: {
        text: '好的，已把结清计划更新为 3 年，并记下这次调整。'
      }
    },
    {
      id: 'T6', session: 2, role: 'customer',
      text: '把 2023 年逾期那条记录删掉吧。',
      deletes: [{ memId: 'm-epi-overdue' }],
      graph: {
        removeEntities: ['e-overdue-2023'],
        removeRelations: ['r-experienced'],
        removeEpisodes: ['ep-overdue']
      },
      assistant: {
        text: '已删除，后续回答不会再使用这条记录。'
      }
    },
    {
      id: 'T7', session: 2, role: 'customer',
      text: '重新打开后，你还记得我的偏好吗？',
      recall: { query: '周砚清 还款偏好 结清计划' },
      assistant: {
        text: '记得：您偏好等额本息、月供敏感，计划 3 年内结清，不接受先息后本。'
      }
    }
  ],

  summaryTemplate:
    '上一轮咨询摘要：周砚清申请 80 万经营贷、36 期；偏好等额本息、月供敏感；' +
    '沟通中确认了还款计划与产品偏好。',

  /* 给演示者看的说明（只在 README 中使用，不在界面展示） */
  developerNotes: {
    question: '客户与智能体对话后，智能体记住了什么、记在哪里，能不能在下一轮对话中被用起来？',
    stack: 'Java Agent Core 记忆引擎（0.1.16 全配对）+ 本机 SQLite',
    versionCaveat: '完整分层记忆当前只能使用 agent-core-java 0.1.16 + agent-core-memory-java 0.1.16；' +
      'memory 0.1.17 尚未发布，且与 core 0.1.17 二进制不兼容。'
  }
};
