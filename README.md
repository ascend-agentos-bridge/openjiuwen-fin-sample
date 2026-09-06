# OpenJiuwen Agent Core · 开发者样例工程（银行信贷场景）

面向开发者的特性样例集：**全部使用开源 openjiuwen Agent Core 真实 API**（无任何本地封装），每个特性一个文件夹、一个可运行样例、一份带**验收方式**的 README。场景统一为银行信贷（客户查询、贷款审批、放款、征信、风控）。

> **版本依赖：`openjiuwen==0.1.16`**（运行前确认：`python -c "import openjiuwen; print(openjiuwen.__version__)"`）
> 约束详见 [AGENTS.md](AGENTS.md)：样例仓库禁止自封装接口；认为需要封装时向 agent-core 社区提 PR。

## 环境要求

| 项 | 要求 |
|---|---|
| Python | 3.11+（在 3.13 实测） |
| openjiuwen | `pip install -U openjiuwen`（0.1.16） |
| LLM | OpenAI 兼容网关；本地 LM Studio（`http://127.0.0.1:1234`，默认 `google/gemma-4-e2b`）或任意云端网关 |
| Embedding | 网关提供 `text-embedding-nomic-embed-text-v1.5`（样例 09/11 使用） |
| MCP | mcp SDK（`pip install mcp`，自带 FastMCP）；A2A 需 `openjiuwen[a2a]` extras |
| chromadb | `pip install chromadb`（样例 09/11 向量库） |

环境变量（与官方 examples 约定一致）：`API_BASE` / `API_KEY` / `MODEL_NAME` / `MODEL_PROVIDER`，另有 `EMBED_MODEL`（09/11）、`SSRF_PROTECT_ENABLED=false`（02/06 访问本地 mock 服务）。

## 快速开始

```bash
cd samples/01_hello_react_agent
python main.py            # 每个 main.py 自带验收 assert，退出码 0 即通过
```

## 特性总览 × 样例地图

| # | 样例 | 特性域 | 场景 | 真实 API 锚点 |
|---|---|---|---|---|
| 01 | hello_react_agent | §2.1 ReAct Agent | 客户档案查询续贷判断 | `ReActAgent.configure(ReActAgentConfig)` + `Runner.run_agent` |
| 02 | function_tool | §6.1-6.4 Tool | 月供计算/授信 API | `@tool`→`LocalFunction.invoke` + `RestfulApi(RestfulApiCard)` |
| 03 | prompt_template | §5.1/5.2 Prompt | 审批意见五段式 | `PromptTemplate.format/to_messages` + `PromptAssembler` |
| 04 | structured_output | §4.2 Structured Output | 申请单抽取入库 | `Model.invoke` + `JsonOutputParser.parse` |
| 05 | model_client | §4.1 Model Client | 参数工程/thinking 坑 | `ModelRequestConfig` 全字段 + `usage_metadata` |
| 06 | workflow_dag | §3/§14 Workflow | 贷款审批四组件流水线 | `Workflow(WorkflowCard)` + `${ref.field}` 数据流 |
| 07 | session_state | §12.1/12.2 Session | 两轮咨询跨轮记忆 | 同 `conversation_id` 的 checkpointer 恢复 |
| 08 | streaming_events | §12.3 Stream | token 级实时渲染 | `run_agent_streaming`（llm_reasoning/output/usage） |
| 09 | memory | §9.2/9.3 Memory | 跨会话客户偏好 | `LongTermMemory` + LLM 摘要 + 语义检索 |
| 10 | context_engine | §10 Context | 长对话窗口管理 | `ContextEngineConfig` 滑动窗口 |
| 11 | rag_retrieval | §8 RAG | 政策知识库语义检索 | `APIEmbedding` + chroma `VectorStore` |
| 12 | multi_agent_team | §11 Multi-Agent | 客服 Handoff 路由 | `HandoffTeam` + `HandoffRoute` |
| 13 | interrupt_checkpoint | §12.4/12.5 Interrupt | 大额放款人工确认 | `QuestionerComponent` + `InteractiveInput` 恢复 |
| 14 | security_guardrail | §15 Guardrail | 高危工具黑名单 | `BaseSecurityRail.run_security_check` reject |
| 15 | sandbox_sysops | §16 SysOps | 报表受控执行环境 | `SysOperation(fs/shell/code)` + 沙箱边界实测 |
| 16 | a2a_interop | §19 A2A | 征信中心跨进程互操作 | `A2AServer` + `A2AClient`（a2a SDK card） |
| 17 | evaluation_optimizer | §17 Evaluation | LLM-as-Judge 打分 | `DefaultEvaluator.evaluate(case, predict)` |
| 18 | mcp_tools | §6.5 MCP | 信贷工具跨进程服务化 | `add_mcp_server(FastMCP stdio)` + `get_mcp_tool` |

## 工程结构

```text
openjiuwen-sample/
├── AGENTS.md            ← 工程约束（禁止自封装/只用最新 release/真实验收）
├── README.md            ← 本文件
├── run_all_samples.py   ← 一键回归
├── docs/checklist-review.md  ← 特性清单逐条校验（真/伪/需补充）
└── samples/             ← 18 个样例，一特性一文件夹（main.py + README.md）
```

## 验收约定（写给使用者与维护者）

1. 每个 `main.py` 末尾都有程序化 assert + `SUCCESS:` 打印，**退出码非 0 即失败**；
2. README 的"实测输出"全部来自真实运行（本地网关，实测分布：qwen3-4b-thinking / qwen3.5-9b / gemma-4-e2b——各样例 README 标注其验收时所用模型），非手写；
3. 模型/框架升级后必须全量回归，受影响样例的实测输出同步更新；
4. 每份 README 的"开发者注意"记录的是迁移过程中**真实踩到的坑**（返回值形态、默认超时、async 注册、字段命名契约等），不是泛泛之谈。

## 已知边界（诚实清单）

- LLM 输出有随机性：验收断言锚定**结构性事实**（工具是否被调用/状态机/分数区间），不断言具体措辞；
- `run_all_samples.py` 全量一次约 25-40 分钟（本地 CPU 推理慢）；生产/CI 建议接云端网关；
- 部分 harness 级能力（DeepAgent、RL 训练）超出样例范畴，见 `docs/checklist-review.md` 的清单校验。
