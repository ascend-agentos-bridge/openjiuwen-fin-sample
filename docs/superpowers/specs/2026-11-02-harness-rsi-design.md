# 信贷 Agent 单 Harness RSI 样例设计

## 目标与边界

新增 `samples/25_harness_rsi`，基于最新 release `openjiuwen==0.1.20`，演示如何将已有信贷 DeepAgent 的 Prompt 与 Skill 纳入官方 Harness RSI 闭环。使用 `SingleHarnessIterativeOptimizationOrchestrator`，覆盖基线、失败归因、候选生成、目标验证、全量回放、防回归、发布及官方加载器复装。

只优化 Prompt 与 Skill。既有工具可供 Agent 使用并提供数据契约，但不允许本轮 RSI 修改 Tool、Rail、生产信贷政策、审批权限或放款规则。样例只做本地发布包复装与复验，不自动部署生产。

不采用样例 17 自行拼优化循环，也不纳入 AutoHarness 的 Git/CI/PR 编码演进。所有模型执行、分析与评审均调用真实模型，不 mock LLM；演示客户与业务数据均为虚构固定数据，不冒充真实客户资料。

## 方案比较

1. 采用官方 Single Harness 控制面：符合真实 Harness RSI 语义和仓库禁止私建核心框架的约束，采用。
2. 用 DefaultEvaluator 自拼轻量循环：只能算评测加自选修改，缺少官方候选、版本与门禁语义，不采用。
3. AutoHarness 编码演进：面向代码、Git、CI、PR，超出本次业务 Harness 优化范围，不采用。

## 运行架构

```text
现有信贷 Agent
  → 导出初始 Harness
  → 冻结优化数据集与评分协议
  → 全量基线评测
  → 失败归因与优化假设
  → 生成 Prompt/Skill 候选
  → 目标用例验证
  → 全量回放与防回归门禁
  → 发布有效版本；无有效改进则保留基线
  → 官方加载器复装最终 Harness 并执行信贷审查
  → 基线与最终 Harness 分别在独立留出集复验
  → 人工审批后才允许企业生产发布
```

`main.py` 仅组装官方配置、调用官方 API、输出路径与摘要；结构性断言、证据审计、冒烟与留出复验写在独立 `test.py` unittest 中，不实现优化算法或第二套 Agent/API 封装。

## 目录设计

```text
samples/25_harness_rsi/
├── main.py
├── README.md
├── config/
│   ├── model.yaml                 # ${API_BASE}/${API_KEY}/${MODEL_NAME} 占位
│   └── rsi.yaml                   # 单 Harness、Prompt/Skill、预算与晋升策略
├── harness/
│   ├── harness_refs.yaml
│   └── credit_review/
│       ├── manifest.json
│       ├── prompt_sections/
│       │   └── credit_review.md
│       └── skills/pre_loan_review/
│           └── SKILL.md
├── data/
│   ├── optimization_cases.json    # 优化与全量回放用例
│   └── holdout_cases.json         # 独立留出复验，不进入候选生成
├── materials/
│   ├── case-intake.template.json
│   ├── case-intake.example.json
│   ├── judge-rubric.template.json
│   ├── judge-rubric.example.json
│   ├── failure-feedback.template.md
│   ├── failure-feedback.example.md
│   ├── tool-contract.template.yaml
│   ├── tool-contract.example.yaml
│   ├── optimization-charter.template.yaml
│   ├── optimization-charter.example.yaml
│   ├── release-approval.template.md
│   └── release-approval.example.md
└── run_output.txt                 # 本次真实运行摘要，不手写效果
```

运行产物写入样例下被忽略的 `runs/<task-id>/`，保留官方 state、report、逐案结果、轨迹、候选差异、门禁和发布包。修改任何冻结输入后使用新 task-id；只有输入与协议指纹一致时才允许 resume。

## 企业角色、物料与签收

| 企业角色 | 必须提供 | 关键字段 | 签收/输出 |
|---|---|---|---|
| 信贷业务专家 | 失败案例、正确结论、必要政策摘录 | 脱敏输入、预期行为、业务解释、禁止结论 | 案例与标准答案 |
| 风控/合规 | 红线、扣分项、不可优化范围 | 政策版本、生效期、硬约束、审批权限 | 评分规则、优化章程、发布合规意见 |
| 评测/数据工程师 | 去标识化数据集与评审协议 | case_id、input、reference、rubric、数据版本 | 冻结优化集/留出集、基线与复验报告 |
| Agent 开发者 | 初始 Harness、工具契约、模型与预算配置 | 可编辑面、工具输入输出、超时、轮次、模型角色 | 候选差异审查、最终加载与冒烟 |
| RSI 控制面 | 上述冻结输入 | 基线、issue、候选、门禁、state/report | 机器可追溯优化证据和版本包 |
| 发布审批人/运维 | 上线窗口、环境、回滚要求 | 候选版本、证据链接、审批人、回滚版本 | 人工发布审批与线上监测/回滚 |

### 六套模板及示例

1. `case-intake`：业务专家登记单个失败案例，含脱敏确认、任务输入、参考结论、必须/禁止行为、政策依据。
2. `judge-rubric`：业务与合规共同定义加权评分、硬扣分和通过阈值；评审协议一旦开始实验即冻结。
3. `failure-feedback`：运营或客户经理反馈线上问题，含实际输出、期望输出、轨迹引用、影响与初步分类。
4. `tool-contract`：开发者/系统负责人说明既有征信、客户档案等工具的字段、空值、错误和数据责任；本轮只读，不允许 RSI 修改。
5. `optimization-charter`：业务、合规、研发共同批准 Prompt/Skill 可编辑面、禁改项、预算、晋升阈值和回滚要求。
6. `release-approval`：记录基线/候选/留出集结果、差异、风险、审批、发布版本和回滚版本。

模板只提供字段骨架，示例填写完整虚构内容。README 说明谁填、何时填、交给谁、如何映射到实际 JSON/YAML 物料。

## README 泳道与阶段输出

README 使用 Mermaid 泳道展示六条角色泳道：信贷业务专家、风控/合规、评测/数据工程师、Agent 开发者、RSI 控制面、发布审批人/运维。

1. 立项：输入失败案例、红线、当前 Harness；输出冻结实验章程。
2. 基线：输入优化集、评审协议、模型配置；输出逐案结果、轨迹、得分与用量。
3. 归因：输入失败证据；输出带证据 issue 和优化假设。脏数据/评审故障不得进入候选优化。
4. 候选：输入已确认 issue；输出仅 Prompt/Skill 的版本化差异。
5. 门禁：输入候选与冻结全集；输出目标验证、全量回放、采纳/拒绝及原因。
6. 发布复验：输入最优或保留版本、留出集；输出复装冒烟、留出集报告和人工审批单。
7. 运营：输入新失败与漂移；输出下一轮数据版本，必要时回滚。

## 配置与数据契约

- `model.yaml` 使用官方 `model.model_client_config` 与 `model.model_request_config` 结构，密钥只经环境变量展开。
- `rsi.yaml` 使用 `AutoCoordinatingHarnessConfig.from_dict`，`evaluator.backend=single_harness`，限制 Prompt/Skill，开启全量评测，采用连续分数晋升策略。
- 优化案例采用官方 `cases` 结构，包含 `case_id`、`input`、`reference`/评分规则；数据加载后保持 case_id 唯一。
- 留出集不传给 orchestrator，分别对基线和最终 Harness 调用官方 evaluator，避免优化信息泄漏。
- 真实模型角色可分设执行、分析、优化、裁判模型；默认复用同一 OpenAI 兼容网关，允许独立覆盖。

## 异常与安全处理

- 缺少模型配置、非法 YAML/JSON、重复 case_id、引用文件不存在：在真实执行前 fail fast。
- 执行或评审基础设施错误、skipped/inconclusive：不得视为业务失败案例，不得促成发布。
- 候选无提升、目标退化、非目标退化、能力未激活或证据不足：保留原最佳版本，报告真实原因。
- 无有效提升属于合法完成状态，`publication_status` 必须明确表示未发布；不得伪造候选或提分。
- resume 仅在数据、Harness、评审协议与任务指纹一致时允许；变更后新建运行目录。
- 全部案例使用虚构数据；README 要求企业数据先脱敏，API Key 不写入文件、日志或实测输出。
- 最终复装只用于样机验证；生产上线必须经过人工审批和既有企业发布流程。

## 验收设计

默认真实运行验收：

1. 确认 `openjiuwen.__version__ == 0.1.20`。
2. orchestrator 完成，state/report 存在且状态为 completed，基线覆盖全部优化案例。
3. 候选、目标门禁、epoch 全量回放、晋升和发布状态相互一致；每个 accepted/published 都有对应证据路径且文件存在。
4. 无提升时验证保留基线且未发布；有提升时验证发布包与 report 指向一致。
5. 使用官方 Harness 加载器装载最终版本，真实执行一笔贷前审查，得到非空且含必要业务结构的答复。
6. 基线与最终版本均完成独立留出集复验，结果完整；不把留出集波动伪装成稳定提升。
7. 默认门禁验证闭环证据，不强制随机提分。`REQUIRE_IMPROVEMENT=1` 时额外要求最终分数高于基线且已发布，不满足则非零退出。
8. README 的实测输出只摘录本次真实运行；保存 `run_output.txt`，断言退出码为 0。

测试不 mock LLM。可离线执行的内容仅校验静态 JSON/YAML/Markdown 模板、配置约束和索引，不冒充模型验收。真实样例由 `python run_all_samples.py 25` 驱动。

## 仓库级变更与回归

- 根 README 与 FEATURES 增加样例 25 和 Harness RSI 能力索引，保留 Java 20–24 编号不变。
- Python 样例版本基线升级到 0.1.20，按官方 examples/源码核对受影响 API；运行 01–19 与 25 的真实回归，失败项修复并更新对应实测输出。
- Java 样例不做无关重构；现有 `benchmarks/java_ascend_affinity/README.md` 和 `samples/24_java_ascend_affinity/README.md` 的工作区修改保持不动。
- 全量 Python 回归受模型服务可用性影响；若服务不可用，明确报告未完成的验收，不将静态检查写成真实运行通过。
