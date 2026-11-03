# 消费贷预审（场景样例：Python + Java）—— Agent 先追问澄清，再规则预审

> 新结构场景样例：`python/` 用开源 **openjiuwen Agent Core**（`openjiuwen==0.1.20`），`java/` 用 **agent-core-java**（`com.openjiuwen:agent-core-java:0.1.16`）。两个语言各写各的，都是真实模型服务，无 mock。
>
> 交互由**运行样例的人**完成：客户问题自己输入，Agent 追问的金额/期限等信息也自己填。

## 场景

客户咨询：*"我想贷点钱装修，能批多少？"*——只说了用途，没说金额和期限。

Agent 不自己假设：先用 `ask_user` 追问金额区间和还款期限；运行者逐题作答后，Agent 调用 `consumer_loan_precheck` 规则工具计算可贷额度、利率、月供和审批结论，输出预审意见。

一句话：**信息不全先问人，问清楚再按规则办。**

## 目录结构

```text
samples/consumer_loan_precheck/
├── README.md
├── python/
│   ├── main.py                             业务入口：交互式预审（客户问题 / 澄清答案由运行者输入）
│   └── tests/
│       ├── test_precheck_rules.py          UT：金额/期限解析与预审规则（不依赖模型）
│       └── test_ask_user_flow.py           UT：真实模型追问 → 脚本化作答 → 恢复 → 预审意见
└── java/
    ├── README.md                           Java 版运行前提、交互说明、实测输出
    ├── pom.xml
    └── src/
        ├── main/java/ConsumerLoanPrecheck.java         业务入口与规则工具
        └── test/java/ConsumerLoanPrecheckTest.java     规则单测
        └── test/java/ConsumerLoanPrecheckIntegrationTest.java  真实模型集成测试
```

按 [AGENTS.md](../../AGENTS.md) 硬约束 7–9：场景命名、Python/Java 分目录；业务入口只写业务流程，验收在 UT；样例按生产代码的写法组织，不展示框架内部机制。

## 怎么交互（每一步的输入输出）

| 步骤 | 运行者要做什么 | 程序会输出什么 |
|---|---|---|
| 1 | 启动程序后，在 `客户问题> ` 后输入自己的问题，例如：`我想贷点钱装修，能批多少？` | — |
| 2 | 阅读 Agent 的追问（1–4 个问题，每个问题 2–4 个选项，如金额区间、还款期限） | `[Agent 追问] 贷款金额范围（推荐）: 您计划申请的装修贷款大致在哪个金额区间？` + 编号选项 |
| 3 | 在 `请选择/输入答案> ` 后逐题输入答案：可直接输入选项文本（如 `10-20万元`），也可以输入自己的描述 | 继续下一题，或进入下一步 |
| 4 | — | Agent 调用规则工具，输出 `=== 预审意见 ===`：可贷额度、年利率、期限、月供、预审结论 |

客户问题、澄清答案都不写死在代码里；UT 通过回调注入脚本化回答，业务入口则读终端输入。

## 运行

### Python 版

```bash
cd samples/consumer_loan_precheck/python
pip install -U openjiuwen   # 0.1.20
python main.py
```

环境变量（与仓库其它 Python 样例一致）：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `API_BASE` | `http://127.0.0.1:1234/v1` | OpenAI 兼容网关地址 |
| `API_KEY` | `lm-studio` | 网关密钥 |
| `MODEL_NAME` | `google/gemma-4-e4b` | 本地实测模型名 |
| `MODEL_PROVIDER` | `openai` | 客户端类型 |

### Java 版

```bash
cd samples/consumer_loan_precheck/java
mvn -q exec:java
```

需要 JDK 17+、Maven 3.9+，依赖 `com.openjiuwen:agent-core-java:0.1.16`；模型环境变量同上。运行细节、实测交互输出见 [java/README.md](java/README.md)。

## 实测交互（Python 版真实运行，框架日志已关闭便于阅读）

```text
客户问题> 我想贷点钱装修，能批多少？

[Agent 追问] 贷款金额范围（推荐）: 您计划申请的装修贷款大致在哪个金额区间？
  1. 1-5万元 —— 小额装修需求（推荐）
  2. 5-10万元 —— 中等装修需求
  3. 10-20万元 —— 较大规模装修需求
  4. 20万元以上 —— 高额度装修需求
请选择/输入答案> 10-20万元

[Agent 追问] 贷款期限选择（推荐）: 您希望将这笔装修款在多长时间内还清？
  1. 1-3年 —— 短期还款，月供压力小（推荐）
  2. 4-5年 —— 中等期限，平衡月供和利息
  3. 6-8年 —— 较长期限，月供压力更小
  4. 9年以上 —— 超长期限，适合预算紧张者
请选择/输入答案> 4-5年

=== 预审意见 ===
好的，根据您提供的装修贷款需求（金额区间：10-20万元，期限：4-5年），我为您进行了预审。

**【消费贷预审结果】**

*   **贷款用途：** 装修
*   **可贷额度（预估）：** **15.0 万元**
*   **年利率：** 3.6%
*   **每月还款额（预估）：** **3013.01 元**
*   **预审结论：** 预审通过，可批 15.0 万元，进入正式审批流程。

请注意，以上数据为系统规则预估结果，最终的贷款额度、利率和还款计划以银行正式审批为准。如果您确认接受此预审结果，我可以为您办理后续的申请流程。
```

> 该记录来自真实验证运行：验证脚本按提示向交互入口写入客户问题与每题答案（等价于终端输入），输入行按终端回显展示；框架日志已关闭以便阅读。

Java 版的真实交互输出见 [java/README.md](java/README.md)。

## 验收方式

业务入口不含断言；验收全部在 UT（真实模型，不做 mock）。

Python 规则单测 + 真实模型集成测试：

```bash
cd samples/consumer_loan_precheck/python
python -m unittest discover -s tests -v
```

实测：`Ran 11 tests ... OK`（10 个规则单测 + 1 个真实模型集成测试）。

Java JUnit：规则单测 + 真实模型集成测试：

```bash
cd samples/consumer_loan_precheck/java
mvn -q test          # -Preal-model 与默认一致，仓库回归脚本统一带该 profile
```

实测：`Tests run: 14, Failures: 0, Errors: 0`（详见 [java/README.md](java/README.md)）。

从仓库根目录跑该样例回归（Python UT + Java JUnit）：

```bash
python run_all_samples.py consumer_loan_precheck
```

## 代码说明

| 位置 | 内容 |
|---|---|
| `python/main.py` | `build_agent` 建 Agent；`run_precheck(query, ask_user)` 跑一次预审对话（追问交给宿主回调收集答案）；`ask_in_terminal` 终端交互；`evaluate_consumer_loan_precheck` 纯规则计算；`consumer_loan_precheck` 注册成 Agent 工具 |
| `java/.../ConsumerLoanPrecheck.java` | 结构同上：`main`、`runPrecheck(query, askUser)`、`askInTerminal`、`evaluatePrecheck`、`LocalFunction` 规则工具 |
| 规则（两语言一致） | 装修用途上限 30 万、年利率 3.6%；其它用途上限 20 万、年利率 4.2%；金额/期限区间取中位数（"以上"取起始值）；等额本息月供；超上限给出人工复核结论 |

追问/回答的中断与恢复由框架 `ask_user` 能力处理，业务代码只保留一个很短的宿主适配函数（Python 约 10 行、Java 同理），不让框架内部字段进入业务主流程。

## 开发者注意

- 模型必须支持工具调用（tool calling）；本地 `google/gemma-4-e4b` 一轮 LLM 约 60–120s，慢但稳定；
- 追问的问题数量（1–4）、选项文案每次都不同，UT 只断言结构事实（是否追问、问题/选项数量、是否有预审意见），不断言具体措辞；
- 额度/利率/月供一律由规则函数计算，不让模型自己算，否则实测输出会漂；
- 运行者可以输入选项原文，也可以输入自定义描述（如"首付三成，贷 18 万，五年"），Agent 会据此调用规则工具；
- Python 版适配 `openjiuwen==0.1.20`；Java 版适配 `com.openjiuwen:agent-core-java:0.1.16`，两版都基于各自 SDK 的最新 release。
