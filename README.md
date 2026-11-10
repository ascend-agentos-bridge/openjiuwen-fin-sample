# openjiuwen-sample：Agent 业务开发样例（银行信贷场景）

做业务系统的人想给信贷、风控、客服这类系统加 Agent 功能（自动查档、跑审批、生成意见、多轮对话）时，可以照这个仓库的样例写。Python 样例使用开源 openjiuwen Agent Core 官方 API（01–19 适配 `openjiuwen==0.1.16`；25 Harness RSI 适配 `openjiuwen==0.1.20`；新结构场景样例 `26_consumer_loan_precheck_ask_user` 的 Python 版适配 `openjiuwen==0.1.20`、Java 版适配 `com.openjiuwen:agent-core-java:0.1.16`）；另有 5 个老的 Java 样例，演示三层子智能体委派、完整调用链日志、Langfuse 对接、自定义工具 Rail 和 KV Cache 亲和开关，适配 `com.openjiuwen:agent-core-java:0.1.16`。一个特性一个文件夹，配套 README 写清楚运行前提、代码讲解、验收方式和实测输出。完整索引见 [FEATURES.md](FEATURES.md)。

样例只 import 官方包，仓库里没有自封装的第二套 API。觉得框架接口不好用，按 [AGENTS.md](AGENTS.md) 的约定去 agent-core 仓库提 Issue / PR，把改进做进框架本体。

| SDK 版本 | 样例范围 | 依赖与入口 | 开发指南 |
|---|---|---|---|
| Python 版 Agent Core | 01–19、25、26_consumer_loan_precheck_ask_user | 01–19 用 `openjiuwen==0.1.16`；25 用 `0.1.20`、`main.py` + `test.py`；新样例按场景目录，`python/` 用 `0.1.20` | 本文下方的 Python 说明 |
| Java 版 Agent Core | 20–24、26_consumer_loan_precheck_ask_user | 老样例 `com.openjiuwen:agent-core-java:0.1.16`，Java `main` / Maven；新样例的 `java/` 同一版本 | [Java 版 README](README-JAVA.md) |
| Java AgentHint | 28_credit_review_agent_hint | `com.openjiuwen:agent-core-java:0.1.17`，`java/` / Maven；Python 待实现 | [样例28](samples/28_credit_review_agent_hint/README.md) |

两套 SDK 的项目结构和 API 分别以对应语言的官方示例为准。Java 开发者从 [Java 指南](README-JAVA.md) 的样例 23 开始，无需安装 Python 版 `openjiuwen`。

Java 性能验证见 [算力亲和 benchmark](benchmarks/java_ascend_affinity/README.md)，运行后自动生成带结论的测试报告。客户端指标与服务端指标分别说明；无昇腾环境可先跑普通模型。

## Python 版快速开始：先跑通 01

前提就两个：Python 3.11+（在 3.13 实测过），一个 OpenAI 兼容的模型服务。样例默认连本机 LM Studio（`http://127.0.0.1:1234/v1`），最快路径：

```bash
pip install -U openjiuwen
cd samples/01_hello_react_agent
python main.py
```

`main.py` 末尾自带断言，跑通时最后一行打印 `SUCCESS: ...`，退出码 0。01 的完整预期输出见该目录 README 的“实测输出”一节。

用的是云端网关，就先设四个环境变量，再跑同一份代码：

```bash
export API_BASE="https://你的网关/v1"
export API_KEY="sk-xxx"
export MODEL_NAME="你选的模型"
python main.py
```

以下环境变量默认值适用于 Python 样例；Java 样例的模型默认值和专有配置见 [Java 指南](README-JAVA.md)：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `API_BASE` | `http://127.0.0.1:1234/v1` | OpenAI 兼容网关地址 |
| `API_KEY` | `lm-studio` | 网关密钥 |
| `MODEL_NAME` | `google/gemma-4-e2b` | 模型名 |
| `MODEL_PROVIDER` | `openai` | 客户端类型，网关不兼容 OpenAI 格式时才改 |
| `EMBED_MODEL` | `text-embedding-nomic-embed-text-v1.5` | 只影响 09/11，网关需提供对应的 embedding 服务 |

样例 25 必须显式设置 `API_BASE`、`API_KEY`、`MODEL_NAME`，并使用 `openjiuwen==0.1.20`；运行与验收步骤见其 README。

样例 02、06 内部起本地 mock 服务，会自己关掉 SSRF 保护，不用额外设变量。

## 样例一览：按业务场景找

Python 开发从 01 开始，Java 开发从 23 开始，再按对应语言和业务场景选择。

### Python SDK：01–19、25（老结构）+ 26_consumer_loan_precheck_ask_user（新结构）

| 样例 | 业务场景 | 对应能力 |
|---|---|---|
| [01 hello_react_agent](samples/01_hello_react_agent/) | 客户经理问一句，Agent 查客户档案后给续贷结论 | ReAct：推理、调工具、作答 |
| [02 function_tool](samples/02_function_tool/) | 月供计算、授信这类业务接口，注册成 Agent 能调的工具 | 本地函数工具 + REST API 工具 |
| [03 prompt_template](samples/03_prompt_template/) | 根据审批上下文生成五段式审批意见 | Prompt 模板与组装 |
| [04 structured_output](samples/04_structured_output/) | 从客户对话里把贷款申请信息抽成固定字段 | 结构化输出 |
| [05 model_client](samples/05_model_client/) | 直接调模型：控制超时、thinking 等参数，看用量统计 | Model 直调与请求参数 |
| [06 workflow_dag](samples/06_workflow_dag/) | 消费贷审批，多步骤串成流水线，上一步结果传给下一步 | Workflow DAG 编排 |
| [07 session_state](samples/07_session_state/) | 同一会话接着聊第二轮，Agent 记得第一轮内容 | Session 会话状态 |
| [08 streaming_events](samples/08_streaming_events/) | 答案逐字流出，前端可以打字机式渲染 | 流式事件 |
| [09 memory](samples/09_memory/) | 客户提过的还款偏好，新会话里还能用上 | 长期记忆 + 语义检索 |
| [10 context_engine](samples/10_context_engine/) | 长对话的上下文窗口管理，别让对话超出模型长度 | Context 窗口管理 |
| [11 rag_retrieval](samples/11_rag_retrieval/) | 信贷政策写进知识库，Agent 先检索再回答 | RAG：真实 embedding + 向量检索 |
| [12 multi_agent_team](samples/12_multi_agent_team/) | 客服 Agent 判断问题性质，把客户转给专职 Agent | 多 Agent handoff 协作 |
| [13 interrupt_checkpoint](samples/13_interrupt_checkpoint/) | 大额放款走到人工复核节点就停下，确认后才继续 | 中断与恢复 |
| [14 security_guardrail](samples/14_security_guardrail/) | 高危工具进黑名单，Agent 调用直接被拦 | 安全护栏 |
| [15 sandbox_sysops](samples/15_sandbox_sysops/) | 报表脚本放进受控沙箱，越界操作被挡住 | 受控执行环境（文件/Shell/代码） |
| [16 a2a_interop](samples/16_a2a_interop/) | 本行 Agent 直接咨询征信中心那套系统的 Agent | A2A 跨机构互操作 |
| [17 evaluation_optimizer](samples/17_evaluation_optimizer/) | 让 LLM 当裁判，按规则给 Agent 的回答打分 | LLM-as-Judge 评测 |
| [18 mcp_tools](samples/18_mcp_tools/) | 信贷工具封装成标准 MCP 服务，其他进程也能调 | MCP 工具协议 |
| [19 skill_use](samples/19_skill_use/) | 贷前审查规范写成 SKILL.md，Agent 按需读取技能并遵循执行 | Agent Skill：注册技能 + 提示注入 + 按需装载 |
| [25 harness_rsi](samples/25_harness_rsi/) | 持续演进信贷 Agent 的 Prompt 与 Skill | 官方 Single Harness RSI；业界标准 Skill 目录（SKILL.md + references/ + scripts/）；6+4 冻结评测集；业务入口与独立 UT 验收 |
| [26 consumer_loan_precheck_ask_user](samples/26_consumer_loan_precheck_ask_user/) | 客户自己输入贷款问题，Agent 追问金额/期限、运行者自己填答案，再给出预审意见 | 人机交互：`ask_user` 追问 + 规则预审（Python + Java 双语言） |

### Java SDK：20–24

| 样例 | 业务场景 | 对应能力 |
|---|---|---|
| [20 java_nested_agents](samples/20_java_nested_agents/) | 审批主管委派风控专员，风控专员再委派征信核验员，逐级返回 | Java HierarchicalToolsTeam：三层子智能体委派 |
| [21 java_call_trace](samples/21_java_call_trace/) | 查询客户征信，输出模型消息、工具参数与结果、用量、错误和调用轨迹 | Java OtelRail + TRACE 事件：完整日志与 JSONL |
| [22 java_langfuse](samples/22_java_langfuse/) | 将征信核验的 Agent、模型和工具轨迹导出到 Langfuse | Java 官方 OTel handler + OTLP/HTTP，含本地导出验收 |
| [23 java_tool_rails](samples/23_java_tool_rails/) | 放款金额自定义校验、大额审批暂停恢复、银行接口临时错误重试 | Java AgentRail + BaseInterruptRail；main 简洁，验收写在 JUnit |
| [24 java_ascend_affinity](samples/24_java_ascend_affinity/) | 多轮信贷复核、旧工具结果压缩 | 官方亲和开关；普通调用已验收，昇腾效果待服务端验证 |

每个样例目录的 README 都含：场景说明、代码讲解、运行与验收方式、实测输出、注意点。其中“注意点”记的是真实踩过的坑（返回值形态、默认超时这类），换模型、升版本前值得先看。

新样例规范见 [AGENTS.md](AGENTS.md) 硬约束 7–9：老的编号样例不再改造；新增样例按「编号 + 场景 + 技术」命名，场景目录下分 `python/` 与 `java/`；`main.py` / Java `main` 只写业务流程，客户问题与澄清答案由运行样例的人输入；验收一律放 UT（`python/tests/` 或 `java/src/test/java/`）。

Java 样例需要 **JDK 17+、Maven 3.9+**。原生入口、Maven 依赖、模型配置、JUnit 验收和 Langfuse 额外前提统一见 [Java 版 README](README-JAVA.md)。

## 全量回归

```bash
python run_all_samples.py                         # 跑全部
python run_all_samples.py 01 06                   # 只跑指定编号（老样例）
python run_all_samples.py 26_consumer_loan_precheck_ask_user  # 按编号/场景名过滤（新样例）
```

脚本按编号/场景名顺序执行：老纯 Maven 样例直接运行 JUnit；老 Python 样例优先运行 `test.py`，否则运行 `main.py`；新结构样例跑 `python/tests/` 的 unittest（以及 `java/` 的 JUnit，如果该目录有 `pom.xml`）。逐条打印 `[PASS]/[FAIL]` 和耗时，最后汇总通过数，退出码非 0 即失败。

跑全部样例前，把剩余依赖装齐：`pip install chromadb mcp "openjiuwen[a2a]"`，分别供 09/11 的向量库、18 的 MCP 服务端、16 的 A2A 使用。

这些 pip 依赖属于 Python 样例。包含 20–24 的全量回归还需要 JDK / Maven，以及 [Java 指南](README-JAVA.md) 列出的额外验收前提。Java 业务入口和 JUnit 可直接通过 Maven 运行，仓库级 Python 调度脚本是可选入口。

本地 CPU 推理下全量一次约 25~40 分钟，生产/CI 里建议接云端网关。

## 验收口径

- Java 样例 20–22 的 main 只展示业务用法，验收断言位于独立测试目录；23–24 使用纯 Maven 入口运行 JUnit。回归脚本按测试退出码判断，非 0 即失败；
- 新结构样例的 `main.py` / Java `main` 只写生产风格业务流程（不出现断言 / `SUCCESS`），客户问题与澄清答案由运行样例的人输入；验收在 `python/tests/` 的 unittest 或 `java/src/test/java/` 的 JUnit，回归脚本对新样例跑 UT；
- 各 README 的“实测输出”来自真实运行，不是手写效果图（本地网关实测过 qwen3-4b-thinking、qwen3.5-9b、gemma-4-e2b、gemma-4-e4b，各样例 README 标注自己用的模型）；
- LLM 输出有随机性，断言只锚结构性事实：工具是否被调用、状态机走向、分数区间，不断言具体措辞；
- 升级 openjiuwen 或换模型后跑一遍回归，把受影响样例的实测输出同步更新。

## 没覆盖的部分

样例 25 使用官方 Single Harness RSI 演进 Prompt 与 Skill；独立 UT 的真实验收状态见其 README。RL 训练尚未覆盖。Java 三层委派样例使用 HierarchicalToolsTeam，README 另有 DeepAgent 递归配置及层数限制的源码核实说明。Python 特性清单的历史逐条核验记录在 [docs/checklist-review.md](docs/checklist-review.md)。

## 仓库结构

公共文件：

```text
openjiuwen-sample/
├── README.md               仓库总览与 Python 版说明
├── README-JAVA.md          Java 版 SDK、运行、验收与目录说明
├── samples/               老样例 01–25（单语言结构）；新样例为「编号_场景_技术」目录（python/ + java/）
├── benchmarks/            Java 亲和 benchmark、逐请求统计与结论报告
├── FEATURES.md            两套 SDK 的样例索引
├── run_all_samples.py      可选的跨语言回归调度脚本
├── docs/checklist-review.md
└── AGENTS.md               仓库约束：只用官方 API、不本地封装、锁最新 release
```

样例结构：

老样例（编号 01–25，保持原结构；以 Python 的 01 为例）：

```text
samples/01_hello_react_agent/
├── main.py                 Python 业务入口
└── README.md               场景、代码讲解、运行、验收与实测输出
```

新样例（按「编号 + 场景 + 技术」命名；以本次新增的 `26_consumer_loan_precheck_ask_user` 为例）：

```text
samples/26_consumer_loan_precheck_ask_user/
├── README.md               场景、目录结构、交互步骤、代码讲解、运行与验收
├── python/
│   ├── main.py             业务入口（交互式：运行者输入客户问题与澄清答案）
│   └── tests/              UT 验收（规则单测 + 真实模型集成测试）
└── java/
    ├── README.md           Java 版运行前提、交互说明、实测输出
    ├── pom.xml
    └── src/main/java/、src/test/java/   业务流程 + JUnit（规则单测 + 真实模型集成测试）
```

老 Java 样例结构（以 23 为例）：

```text
samples/23_java_tool_rails/
├── pom.xml                 官方 Java SDK 依赖、入口类和测试配置
├── README.md
├── src/main/java/          Java main 与业务代码
├── src/main/resources/     日志等运行资源
├── src/test/java/          JUnit 单元测试与真实模型验收
├── run_output.txt          演示实测输出
└── test_output.txt         验收实测输出
```

老 Java 样例不套用 Python 的 `main.py` 目录约定。20–22 保留的历史辅助脚本及 22 的本地 OTLP 测试接收器，见 [Java 指南](README-JAVA.md)。新样例的 Java 版统一放在场景目录的 `java/` 下（`pom.xml` + `src/main/java/` + `src/test/java/`）。

- [27 信贷全流程超级智能体与对话树](samples/27_credit_full_process_super_agent/README.md)：Java Core 0.1.17；模糊需求澄清、团队/单Agent委派、运行者审批与签署、虚构放款与贷后；实时树显示实际激活节点。

## AgentHint：信贷复核与 Linux benchmark

[样例 28](samples/28_credit_review_agent_hint/README.md) 使用 `com.openjiuwen:agent-core-java:0.1.17`，展示征信查询、多轮会话及缓存暂停、恢复、释放；Python 版待实现。[独立 benchmark 与 Linux 一键脚本](benchmarks/java_agent_hint/README.md) 支持配置模型地址、模型 ID、并发与重复次数，终端输出报告绝对路径和确定性代码生成的结论。DeepSeek 官方服务可用于真实业务基线验证；AgentHint off/on 性能及服务端执行效果需兼容推理环境，当前不得据普通服务基线宣称已验证。具体运行命令、指标边界与实际验收状态见各自 README。
