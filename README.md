# openjiuwen-sample：Agent 业务开发样例（银行信贷场景）

做业务系统的人想给信贷、风控、客服这类系统加 Agent 功能（自动查档、跑审批、生成意见、多轮对话）时，可以照这个仓库的样例写。18 个样例都用开源 openjiuwen Agent Core 的官方 API 写成，当前适配 `openjiuwen==0.1.16`。一个特性一个文件夹，`main.py` 直接能跑，配套 README 写清楚运行前提、代码讲解、验收方式和实测输出。

样例只 import 官方包，仓库里没有自封装的第二套 API。觉得框架接口不好用，按 [AGENTS.md](AGENTS.md) 的约定去 agent-core 仓库提 Issue / PR，把改进做进框架本体。

## 快速开始：先跑通 01

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

环境变量清单（与官方 examples 约定一致，也是所有样例的通用配置入口）：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `API_BASE` | `http://127.0.0.1:1234/v1` | OpenAI 兼容网关地址 |
| `API_KEY` | `lm-studio` | 网关密钥 |
| `MODEL_NAME` | `google/gemma-4-e2b` | 模型名 |
| `MODEL_PROVIDER` | `openai` | 客户端类型，网关不兼容 OpenAI 格式时才改 |
| `EMBED_MODEL` | `text-embedding-nomic-embed-text-v1.5` | 只影响 09/11，网关需提供对应的 embedding 服务 |

样例 02、06 内部起本地 mock 服务，会自己关掉 SSRF 保护，不用额外设变量。

## 样例一览：按业务场景找

第一次接触不用挑，从 01 顺着往后跑一遍。之后按场景直接查：

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

每个样例目录的 README 都含：场景说明、代码讲解、运行与验收方式、实测输出、注意点。其中“注意点”记的是真实踩过的坑（返回值形态、默认超时这类），换模型、升版本前值得先看。

## 全量回归

```bash
python run_all_samples.py          # 跑全部
python run_all_samples.py 01 06    # 只跑指定编号
```

脚本按编号顺序执行，逐条打印 `[PASS]/[FAIL]` 和耗时，最后汇总通过数，退出码非 0 即失败。

跑全部样例前，把剩余依赖装齐：`pip install chromadb mcp "openjiuwen[a2a]"`，分别供 09/11 的向量库、18 的 MCP 服务端、16 的 A2A 使用。

本地 CPU 推理下全量一次约 25~40 分钟，生产/CI 里建议接云端网关。

## 验收口径

- 每个 `main.py` 末尾有断言和 `SUCCESS:` 打印，退出码非 0 即失败；
- 各 README 的“实测输出”来自真实运行，不是手写效果图（本地网关实测过 qwen3-4b-thinking、qwen3.5-9b、gemma-4-e2b，各样例 README 标注自己用的模型）；
- LLM 输出有随机性，断言只锚结构性事实：工具是否被调用、状态机走向、分数区间，不断言具体措辞；
- 升级 openjiuwen 或换模型后跑一遍回归，把受影响样例的实测输出同步更新。

## 没覆盖的部分

harness 级的 DeepAgent、RL 训练这类能力没有放进样例。openjiuwen 特性清单的逐条核验记录在 [docs/checklist-review.md](docs/checklist-review.md)。

## 仓库结构

```text
openjiuwen-sample/
├── samples/
│   └── NN_name/            18 个样例，一特性一文件夹
│       ├── main.py         可运行，自带断言
│       └── README.md       场景 / 代码讲解 / 运行 / 验收 / 实测输出 / 注意点
├── run_all_samples.py      一键回归
├── docs/checklist-review.md
└── AGENTS.md               仓库约束：只用官方 API、不本地封装、锁最新 release
```
