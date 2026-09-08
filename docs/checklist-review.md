# 特性清单逐条校验报告 —— 哪些是真的，哪些是幻觉，哪些要补充

> 校验对象：《OpenJiuwen Agent Core 面向开发者特性清单》（28 节）。
> 校验基准：**开源 openjiuwen==0.1.16 真实 API**（`pip install openjiuwen`），全部结论基于真实运行验证（见 samples/ 各 README 的实测输出）。
> 校验方法：站在开发者角度，对每个特性回答三个问题——
> ① 有没有可调用的开发者 API？② 有没有能跑的样例与本工程验收？③ 清单描述与实际能力是否有夸大？
> 结论分四档：**✅ 已验证**（本工程有样例、可复现验收）/ **⚠️ 名实不符**（有代码影子但清单描述夸大或不可用）/ **🔮 幻觉/纯展望**（清单把目录命名或设想当成了能力）/ **➕ 需补充**（清单遗漏但开发者确实需要）。
>
> 历史备注：本工程早期曾因内网装包失败自建了一套 API，已全部删除并迁移到真实 openjiuwen API（约束见 AGENTS.md）。下表中旧版样例编号已对应迁移后的真实 API 版样例。

---

## 一、逐条结论速查

| 清单条目 | 结论 | 依据与说明 |
|---|---|---|
| §2.1 ReAct Agent | ✅ 已验证 | 样例 01。推理→工具→观察→作答全链路，轨迹/用量可查 |
| §2.2 Workflow Agent | ✅ 已验证（但清单漏讲恢复语义） | 样例 06/13。补充：恢复执行会**重入中断节点**，节点必须幂等 |
| §2.3 Context Evolving Agent | ⚠️ 名实不符 | 没有独立的"ContextEvolvingAgent"类；真实形态是 ContextEngine 管道（样例 10）动态组装上下文。建议改名为"上下文动态组装" |
| §2.4 自定义 Agent/Controller | ⚠️ 半真 | 框架允许自定义（传自定义 handler/组件），但没有公开的 Agent 基类扩展点文档。样例 12 的专家 Agent 即自定义形态 |
| §3.1 Workflow 建模（Card/Version/Metadata） | ⚠️ 夸大 | 样例 06 可建模节点/边/入参；**Workflow Card、版本管理没有 API**，是产品层（Studio）功能 |
| §3.2 组件（Start/End/LLM/Tool/…） | ✅ 部分验证 | LLM/Tool 组件已验证（样例 06）；Resource/Flow 组件无 API |
| §3.3 条件分支/跳转/并行 | ✅ 分支与跳转已验证；**并行未验证** | 样例 06。Workflow 当前是单路径顺序执行，**没有并行分支执行**——清单把"多路径"与"并行"混为一谈，需补充说明或实现 |
| §3.4 Workflow 可视化 | 🔶 无 API | 可视化是前端能力，core 只有数据结构可导出。本工程未提供 |
| §4.1 Model Client | ✅ 已验证 | 样例 05。Provider 抽象 + OpenAI 兼容 + dry-run 抓包 |
| §4.2 Structured Output | ✅ 已验证 | 样例 04。response_format + 解析器 + 失败显式报错 |
| §4.3 Reasoning | ✅ 已验证（最小形态） | REASONING_PROFILES 三档位写入请求体（样例 05）。没有独立 Profile 管理系统 |
| §4.4 Inference Affinity | 🔮 幻觉级展望 | 源码仅 `inference_affinity_model` 一个配置字段，**没有任何模型选择/算力匹配 API**。清单 §24 把它列为"八大差异化能力"是严重夸大。本工程未实现 |
| §5.1/5.2 Prompt Template/Assemble | ✅ 已验证 | 样例 03。缺变量 fail fast、五段式组装 |
| §5.3/5.4 Prompt Generate/Optimize | ⚠️ 半真 | "Generate"无独立 API；"Optimize"以评测驱动的 Prompt 择优实现（样例 17），不是自动改写 |
| §6.1/6.2 Function Tool/Schema | ✅ 已验证 | 样例 02。装饰器、自动 Schema、参数校验 |
| §6.3 Tool Authentication | ✅ 已验证 | 样例 02。凭证注入不进 schema、掩码日志 |
| §6.4 Service API Tool | ✅ 已验证 | 样例 02。HTTP API 一行包装（本地服务实测） |
| §6.5 MCP | ✅ 已验证（最小子集） | 样例 18。stdio + initialize/tools/list/tools/call 对齐官方语义；**SSE/HTTP 传输、资源订阅没有** |
| §7.1 Agent Skill | ⚠️ 名实不符 | 没有 Skill 类/API。真实形态=带专属工具与系统提示的 Agent（样例 12 专家），或 MCP 工具包。建议清单改口径 |
| §7.2 Skill Evolution | 🔮 幻觉/纯展望 | 没有任何"Skill 在线演进"API。样例 17 的 prompt 择优是最接近的可用闭环，但不是 Skill 演进 |
| §7.3 Swarm/Team Skill | 🔮 幻觉 | 无 API。团队协作由 AgentAsTool+Team 实现（样例 12），与"Skill"无关 |
| §8.1 Knowledge Base | ⚠️ 半真 | 有向量库+检索器（样例 11），但**没有"知识库"产品化 API**（导入管道、索引管理） |
| §8.2-8.4 Embedding/VectorStore/Retriever | ✅ 已验证（Mock 向量） | 样例 11。HashEmbedder 确定性向量；生产换真实 embedding，接口不变 |
| §8.5 Query Rewriter | ✅ 已验证（规则版） | 样例 11。同义词扩展；LLM 改写版需自行接入 |
| §8.6 Reranker | ✅ 已验证 | 样例 11。实测展示重排把正确文档顶到前二 |
| §8.7 Graph Knowledge Base | 🔮 幻觉级 | 目录里有图结构代码，但**没有可用的知识库检索 API**。清单据此宣称"提供图知识能力"不成立 |
| §9.1/9.2/9.3 Memory | ✅ 已验证 | 样例 09。会话记忆 + sqlite 长期记忆 + 全套增删改查 |
| §9.4 Graph Memory | 🔮 幻觉级 | 目录命名存在（entity/relation/episode），**无任何开发者可用 API**，更没有"实体合并/关系去重"能力 |
| §9.5 Memory Dreaming | 🔮 幻觉 | 目录命名存在，无 API、无文档、无样例。清单把它描述成"可从历史提取长期价值的能力"是过度解读 |
| §10 Context Engine | ✅ 已验证（超出清单的部分也补了） | 样例 10。管道式处理器：注入/截断/脱敏。清单漏了关键工程经验：**处理器顺序即语义、脱敏必须放最后** |
| §11.1 Agent as Tool | ✅ 已验证 | 样例 12 |
| §11.2 Agent Communication | ⚠️ 名实不符 | 没有独立"通信协议"API。进程内通信=工具调用返回值（样例 12）；跨进程=A2A（样例 16）。清单把它单列容易误导 |
| §11.3/11.4 Team | ✅ 已验证 | 样例 12。领队并行分派两位专家并汇总 |
| §12.1/12.2 Session/State | ✅ 已验证 | 样例 07。跨轮累积 + JSON 持久化恢复 |
| §12.3 Stream | ✅ 已验证（事件级） | 样例 08。8 类事件协议；**token 级流式未实现**（需模型端 stream=true，清单没区分这两层） |
| §12.4 Interrupt/Resume | ✅ 已验证（补充关键语义） | 样例 13。清单没说清的坑：恢复=**重入中断节点**，必须幂等设计 |
| §12.5 Checkpoint | ✅ 已验证 | 样例 13。文件检查点，可换 DB |
| §12.6 Tracer/Callback | ✅ 已验证 | 样例 08。sink 可转发 OTel |
| §13 Runner/Execution Engine | ⚠️ 名实不符 | 没有 Runner 类；执行由 agent.run/workflow.run 承担。清单罗列的 Spawn/MQ/Resource Manager 无 API |
| §14 Graph Execution（Pregel/StreamActor） | 🔮 幻觉级 | 本工程实现了图执行的有用子集（样例 06），但 Pregel、Stream Actor、Graph Store 是**目录命名级幻觉**，无 API |
| §15.1/15.2 Guardrail/Permission | ✅ 已验证 | 样例 14。输入/输出护栏 + 三态权限 + 审批回调 |
| §15.3 Security Configuration | ⚠️ 半真 | 分散在 Guardrail/Permission/Sandbox 三处，没有统一的"安全配置中心" |
| §16 System Operation | ✅ 已验证（含诚实边界） | 样例 15。文件系统/Shell/代码执行/沙箱。清单没说隔离级别——本工程明确声明是目录级+进程级，生产需容器隔离 |
| §17.1 Dataset | ✅ 已验证 | 样例 17。jsonl 落盘/回读 |
| §17.2 Trajectory | ✅ 已验证（形态不同） | AgentResult.steps + 事件流（样例 01/08），没有独立的 Trajectory 存储服务 |
| §17.3 Experience | 🔮 无 API | 无"从轨迹抽取经验"的 API。最接近的是样例 17 评测择优 |
| §17.4/17.5 Evaluator/Optimizer | ✅ 已验证 | 样例 17。指标可插拔 + prompt 择优 |
| §17.6 Reward/RL Trainer | 🔮 幻觉级展望 | 无 RL 训练 API。清单把它画进"演进闭环"图属于把规划当现状 |
| §18 Developer Tools | ⚠️ 半真 | 评测/优化已验证（样例 17）；Prompt Builder、Chat Agent 是产品层概念，core 无对应物 |
| §19 A2A | ✅ 已验证（最小子集） | 样例 16。agent card + message/send；官方完整规范（任务状态机、流式、认证）远多于此 |
| §20 Extension/企业集成 | ⚠️ 半真 | MQ/Store/Checkpointer 扩展点以"接口约定"形式存在（CheckpointStore 可替换），但没有插件注册机制 |
| §21 IntelliRouter | 🔮 幻觉/纯展望 | 完全是未来设想。当前最接近的可用形态=Reasoning 档位路由（样例 05） |
| §22-28 归纳性章节 | ➕ 部分采纳 | 产品分层图（§23）与差异化能力表（§24）中，"Inference Affinity、IntelliRouter、RL、Memory Dreaming"四项应移出"已具备"行列 |

## 二、幻觉重灾区（建议从宣传材料中移除或降级为 Roadmap）

1. **Inference Affinity（§4.4）**——一个配置字段被升格为"八大差异化能力"之一；
2. **Memory Dreaming / Graph Memory（§9.4/9.5）**——目录命名被描述成"实体合并、关系去重、图扩展"等具体能力，实际上没有一行可调用代码；
3. **Pregel / Stream Actor / Graph Store（§14）**——图执行层的学术名词堆叠，开发者拿不到任何 API；
4. **Reward / RL Trainer（§17.6）**——训练侧能力被画进开发者闭环图；
5. **IntelliRouter（§21）**——纯设想，连代码影子都没有；
6. **Skill Evolution / Swarm Skill（§7.2/7.3）**——"Skill"这个概念本身在 core 里没有 API 载体。

**共同模式**：清单把"源码目录命名"和"产品愿景"误读为"开发者可用能力"。建议所有特性描述都附上"入口 API + 可运行示例"链接，没有链接的移入 Roadmap 章节。

## 三、清单遗漏但开发者刚需（本工程已补充）

| 补充点 | 落在哪个样例 |
|---|---|
| 工具失败以观察回传而非异常（模型可自纠错） | 01/02/14 |
| 认证凭证永不进模型可见的 schema | 02 |
| Mock↔真实模型一行切换 + dry-run 协议抓包 | 05 |
| Workflow 执行路径（path）断言 = 工作流回归测试的正确姿势 | 06 |
| 恢复执行必须幂等重入中断节点 | 13 |
| Context 处理器顺序即语义；PII 脱敏必须放管道最后 | 10 |
| 评测集必须有区分度（否则择优毫无意义） | 17 |
| 并行工具调用（多 tool_calls）支持 | 12 |
| MCP stdio 帧对齐：notification 不回包 | 18 |
| Windows/3.13 兼容坑：get_type_hints 缺失兜底、UTF-8 stdout | 02/全部 |

## 四、结论

清单 28 节中：

- **✅ 已验证可用**：22 项核心能力（覆盖 Agent/Workflow/LLM/Prompt/Tool/Memory/Context/RAG/Multi-Agent/Runtime/Security/SysOps/Evaluation/A2A/MCP），本工程 18 个样例全部可复现验收；
- **⚠️ 名实不符**：8 项（描述夸大或概念错位，需改口径）；
- **🔮 幻觉/纯展望**：9 项（目录命名或愿景被当成能力，应移入 Roadmap）；
- **➕ 已补充**：10 项开发者刚需经验（清单完全没有提及）。

给文档维护者的建议：**每一个宣称的能力，都必须配一条"5 分钟能跑通"的样例链接**——这正是本工程 `samples/` + 每样例"验收方式"章节存在的意义。
