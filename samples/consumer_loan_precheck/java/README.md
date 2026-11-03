# Java 版：消费贷预审（ask_user 先向客户澄清，再做规则预审）

> 与 [`../python/`](../python/) 是同一个业务场景的两种语言实现，各自基于对应语言 SDK 编写、互不套壳。
> Java 版依赖 **`com.openjiuwen:agent-core-java:0.1.16`**（Maven 中央仓库 release），业务入口只写生产流程，验收全部在 `src/test/java/` 的 JUnit 里。

## 场景

客户陈女士在手机银行留言：*"我想贷点钱装修，你们能批多少？"* ——只说了用途，没说金额和期限。

Agent 不自己假设，先通过内置 `ask_user` 工具向客户一次性问清「金额区间 + 还款期限」；客户选择后，答案回填给 Agent，Agent 再调用 `consumer_loan_precheck` 规则工具算出可贷额度 / 利率 / 月供，最后给出预审意见。

一句话：**信息不全就先问用户，问清楚再办；额度、利率、月供一律由规则算，不让模型编。**

## 目录结构

```text
samples/consumer_loan_precheck/java/
├── pom.xml                                        依赖 agent-core-java 0.1.16 + JUnit 5，release 17
├── src/main/java/ConsumerLoanPrecheck.java        业务入口：规则工具、Agent、一次预审对话、终端问答
├── src/main/resources/logback-consumer-loan.xml   框架日志压到 WARN，保证交互输出可读
├── src/test/java/ConsumerLoanPrecheckTest.java    规则单测（13 个，不依赖模型）
└── src/test/java/ConsumerLoanPrecheckIntegrationTest.java  真实模型集成测试（1 个）
```

`main` 只写业务流程（入口、配置、调官方 API、发起/收集输入/继续、打印结果），**不含 assert / SUCCESS / 测试夹具**；验收全部在 JUnit。

## 运行前提

| 依赖 | 要求 |
|---|---|
| JDK | 17+（本机用 DevEco-Studio 自带 OpenJDK 25 验证，pom 用 `maven.compiler.release=17` 编译） |
| Maven | 3.9+（本机 3.9.9） |
| Java SDK | `com.openjiuwen:agent-core-java:0.1.16`，pom 已固定，首次构建自动下载 |
| 模型服务 | 可用的 OpenAI 兼容网关 + 支持 tool calling 的模型；**不允许 mock 大模型** |

模型配置全部走环境变量，默认值与本仓库其它样例一致：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `API_BASE` | `http://127.0.0.1:1234/v1` | OpenAI 兼容网关地址 |
| `API_KEY` | `lm-studio` | 网关密钥 |
| `MODEL_NAME` | `google/gemma-4-e4b` | 本地实测模型（LM Studio） |
| `MODEL_PROVIDER` | `openai` | 客户端类型 |

## 构建与运行

```bash
cd samples/consumer_loan_precheck/java
mvn -q compile          # 首次构建（exec:java 不负责编译）
mvn -q compile exec:java
```

`pom.xml` 里 `exec-maven-plugin` 已把 `mainClass` 配成 `ConsumerLoanPrecheck`，并让 JVM 加载 `logback-consumer-loan.xml`；框架日志只保留 WARN，交互时只会看到客户问答与预审意见。

## 交互步骤与输入说明

1. **输入客户问题**：出现 `客户问题> ` 后输入客户的咨询内容（直接回车会提示重输），例如：
   ```text
   我想贷点钱装修，能批多少？
   ```
2. **Agent 输出追问和选项**：Agent 判断缺金额、期限，调用 `ask_user`，终端打印每道题的标题、题干和编号选项（1–4 题，每题 2–4 个选项，推荐项在 label 末尾），例如：
   ```text
   [Agent 追问] 贷款金额范围（推荐）: 您计划申请的装修贷款大致在哪个金额区间？
     1. 10万 - 30万元（推荐） —— 适用于中等规模的装修项目。
     2. 30万 - 50万元 —— 适用于较大规模的装修项目。
     3. 50万以上 —— 适用于高端或大规模的装修项目。
   ```
3. **输入选择**：在 `请选择/输入答案> ` 后输入选项文案（建议直接粘贴 label；也可以输入自定义答案，例如 `10万-20万`）。空输入会重新提示；Agent 有几道题就问几次。
4. **输出预审意见**：答案回填后 Agent 继续执行，调用 `consumer_loan_precheck` 规则工具，打印预审意见（可贷额度、年利率、期限、月供与结论；超上限时规则返回需人工复核）。本轮实测结论为：**装修用途 20 万元、年利率 3.6%、48 期、月供 4480.1 元、预审通过**。

> 追问文案和选项每次运行可能不同（真实模型生成）；只要按实际情况回答金额与期限即可。

## 实测输出（真实交互运行记录）

以下记录来自一次真实运行：命令为 `mvn -q compile exec:java`（本机额外带了 Maven settings / 本地仓库参数），用脚本自动扮演运行样例的人输入客户问题与每题答案，输入行由驱动脚本在发送时回显（等价于终端回显），已去掉 Maven/JDK 启动告警（logback 已把框架日志压到 WARN，无框架日志混入）。退出码 0。

```text
客户问题> 我想贷点钱装修，能批多少？

[Agent 追问] 贷款金额范围（推荐）: 您计划申请的装修贷款大致在哪个金额区间？
  1. 10万 - 30万元（推荐） —— 适用于中等规模的装修项目。
  2. 30万 - 50万元 —— 适用于较大规模的装修项目。
  3. 50万以上 —— 适用于高端或大规模的装修项目。
请选择/输入答案> 10万 - 30万元（推荐）

[Agent 追问] 贷款期限选择（推荐）: 您希望的还款周期是多久？
  1. 1 - 3年（推荐） —— 还款压力较小，利息支出较低。
  2. 3 - 5年 —— 平衡还款压力和总利息。
  3. 5年以上 —— 适合预算紧张，希望每月还款额较低的客户。
请选择/输入答案> 3 - 5年

=== 预审意见 ===
好的，根据您提供的“装修”用途、金额区间（10万 - 30万元）和期限区间（3 - 5年），我为您进行了预审。

**【消费贷预审结果】**

*   **预审结论：** 预审通过，可进入正式审批流程。
*   **最高可贷额度：** 20.0 万元
*   **年利率：** 3.6%
*   **还款期限：** 48个月（即4年）
*   **每月还款额：** 4,480.1 元

请问您是否需要了解更多关于贷款的细节，或者准备进入下一步的正式申请流程？
```

规则侧对应：金额区间 `10万 - 30万元` 取中位数 20 万，期限区间 `3 - 5年` 取中位数 4 年（48 个月），装修用途上限 30 万、年利率 3.6%，未超上限所以直接预审通过。

## JUnit 验收

```bash
cd samples/consumer_loan_precheck/java
mvn -q test              # 默认就包含真实模型集成测试（需要网关可用）
# 仓库回归脚本使用的等价命令：
mvn -q -Preal-model test
```

| 测试 | 数量 | 说明 |
|---|---|---|
| `ConsumerLoanPrecheckTest` | 13 | 纯规则单测，不依赖模型：金额/期限解析（区间中位数、“以上”、年/月换算）、装修/其它用途上限与利率、等额本息月供公式、人工复核分支、输出字段 |
| `ConsumerLoanPrecheckIntegrationTest` | 1 | 真实模型：脚本化客户（金额挑最接近 15 万、期限挑最接近 4.5 年的选项）走完 ask_user 追问 → 回答 → 恢复，断言确实追问过、追问 1–4 题、每题 2–4 个选项、最终预审意见非空 |

实测结果（2026-11-03，`mvn test` 真实运行；为了看到 surefire 汇总这里去掉了 `-q`，节选）：

```text
[INFO] Running ConsumerLoanPrecheckTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.028 s -- in ConsumerLoanPrecheckTest
[INFO] Running ConsumerLoanPrecheckIntegrationTest
ask_user 追问 2 题: [{header=贷款金额范围（推荐）, question=您希望申请的装修贷款大致在哪个金额区间？, options=[...]}, {header=贷款期限选择（推荐）, question=您希望将这笔装修款项分多少年偿还？, options=[...]}]
真实模型验收（ask_user 预审）: {output=好的，陈女士您好。根据您提供的“装修”用途、金额区间（10万-30万元）和期限区间（3-5年），我们为您进行了初步的消费贷预审。 ... result_type=answer}
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 111.1 s -- in ConsumerLoanPrecheckIntegrationTest
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  01:52 min
```

另实测仓库回归脚本使用的 `mvn -Preal-model test`：`Tests run: 14, Failures: 0, Errors: 0, Skipped: 0`，集成测试 53.12 s，`BUILD SUCCESS`。

本地 `google/gemma-4-e4b` 带 reasoning，单轮 LLM 约 30–110 秒，一次完整预审（3–4 次模型调用）约 1–2 分钟；集成测试用例本身实测 48.4s / 53.12s / 111.1s / 134.1s 不等（模型波动较大），整轮 `mvn test` 约 1–2 分钟。

## 代码讲解（`src/main/java/ConsumerLoanPrecheck.java`）

| 关键点 | 官方 API | 说明 |
|---|---|---|
| 业务入口 | `main` | 读终端客户问题 → `runPrecheck` → 打印预审意见；`finally` 里 `Runner.release` / `Runner.stop` |
| 一次预审对话 | `Runner.runAgent(agent, Map.of("query", ..., "conversation_id", ...))` | 循环处理：Agent 返回追问 → 宿主收集答案 → 带答案恢复，直到返回预审意见 |
| Agent 构建 | `ReActAgent` + `ReActAgentConfig` + `ModelClientConfig` | 模型配置走环境变量；`maxIterations=5`、`maxParallelToolCalls=1` |
| 提问能力 | `new AskUserTool()` + `new AskUserRail()` | Java SDK 的 rail 不会自动挂工具：用 `Runner.resourceMgr().addTool(...)` + `agent.getAbilityManager().add(tool.getCard())` 注册后，模型才看得到 `ask_user` |
| 宿主问答 | `InteractiveInput.update(...)` + `Map.of("answers", ...)` | 逐题打印 header/题干/编号选项，从 stdin 读答案；答案按问题原文回填后继续同一会话 |
| 规则工具 | `ToolCard` + `LocalFunction` | 工具名 `consumer_loan_precheck`，入参是用途/金额区间/期限区间原文，返回 8 个业务字段 |
| 规则纯函数 | `evaluatePrecheck` / `amountWan` / `termMonths` | 与 Python 版一致：装修上限 30 万、利率 3.6%；其它用途上限 20 万、利率 4.2%；区间取中位数、“以上”取起始值；等额本息月供；超上限 `manual_review=true` |

## 注意点（真实踩坑）

- **Java 的 `AskUserRail` 不会自动注册 `ask_user` 工具**（这点和 Python 版不同）：必须自己 `Runner.resourceMgr().addTool(askUserTool, agentId)`，再把 `askUserTool.getCard()` 加进 `abilityManager`，否则模型根本看不到提问工具。
- **恢复必须复用同一个 Agent 和 `conversation_id`**：中断状态保存在会话里，`Runner` 按 session id 用 checkpointer 恢复；本样例在 `main` 启动时设置 `InMemoryCheckpointer`，恢复时复用同一会话 id，才能接着刚才那轮继续。
- **答案按问题原文回填**：宿主把每道题的回答收成 `{问题原文: 答案}` 交给 `AskUserRail`，键必须与 `question` 字段逐字一致，否则匹配不到答案、会再次追问。
- **追问结构交给工具 schema**：`ask_user` 支持 1–4 题、每题 2–4 个选项，schema 由 `AskUserTool` 提供，不要自己拼一个简化版工具卡。
- **额度/利率/月供必须由规则算**：样例把计算放在纯函数 `evaluatePrecheck`，模型只负责组织结论；如果让模型心算，实测数字会漂。
- **先 `mvn -q compile` 再 `exec:java`**：`exec:java` 不负责编译，clean 后直接跑会找不到 `ConsumerLoanPrecheck`。
- **JDK 25 跑 Maven 3.9.9 会有 jansi native-access / Unsafe 告警**，不影响构建和运行。
- **默认 `mvn test` 就执行真实模型集成测试**，所以运行前必须把模型网关准备好；`-Preal-model` 与默认等价（仓库回归脚本统一带该 profile）。
- **LLM 输出有随机性**：两次实测追问的选项文案不同（一次 `1-3年`，一次 `2-3年`），单测只断言结构事实，不断言措辞。
