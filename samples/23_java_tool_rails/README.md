# 23 · Java 自定义工具校验、审批恢复与异常重试

依赖：**`com.openjiuwen:agent-core-java:0.1.16`**，本次核对的[最新 release](https://github.com/openJiuwen-ai/agent-core-java/releases/tag/v0.1.16)。JDK 17+、Maven 3.9+。

客户 C1001 申请放款 50000 元：金额先经过自定义业务校验，大额申请暂停等审批，审批通过后恢复原会话；演示银行接口首次返回 `BANK_BUSY`，异常 Rail 重试一次后完成。

**`main` 只写业务流程，验收全部在 JUnit 中。** 模型使用真实服务；银行工具是本地演示业务，不连接真实账户、不发放资金。

## 代码怎么看

只有一个业务源文件：[CreditToolRails.java](src/main/java/CreditToolRails.java)。

| 位置 | 作用 |
|---|---|
| `main` | 创建 Agent → 发起申请 → 提交演示审批结果 → 恢复执行 → 打印结果、释放资源 |
| `createAgent` | 配置真实模型、注册官方 `LocalFunction` 和两个 Rail |
| `LoanApprovalRail` | 继承官方 `BaseInterruptRail`，实现业务校验和恢复后的审批决策 |
| `BankRetryRail` | 继承官方 `AgentRail`，在 `onToolException` 中请求一次重试 |

本目录只保留 Java/Maven 入口，没有 Python 启动器。根目录回归脚本直接识别 `pom.xml` 并调用 Maven 运行 JUnit。

金额规则刻意保留在自定义 Rail 中，便于看到扩展点的作用：

- 非数字、金额 ≤ 0、金额 > 100000：`reject(...)`，跳过工具执行。
- 0 < 金额 ≤ 20000：`approve()`，直接放行。
- 20000 < 金额 ≤ 100000，尚未审批：`interrupt(...)`，等待外部输入。
- 恢复时收到布尔值 `true`：放行；收到其他非空值：拒绝。文本 `"true"` 不视为批准。

`main` 使用 `InteractiveInput.update(interruptId, true)` 模拟业务系统提交审批结果，再以**同一个 `conversation_id`** 继续执行。实际项目把这一步接到审批页面或审批服务即可，不让模型决定是否批准。

工具异常在 SDK 中可能被包装，因此本例按消息中的演示错误码 `BANK_BUSY` 分类；只允许额外尝试一次，人工审批中断和普通业务错误均不重试。首次故障发生在放款处理之前；真实资金接口还应使用其业务幂等机制。

## 运行前提

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `API_BASE` | `http://127.0.0.1:1234/v1` | 真实 OpenAI 兼容模型接口 |
| `API_KEY` | `lm-studio` | 模型服务密钥 |
| `MODEL_NAME` | `google/gemma-4-e4b` | 需支持工具调用 |
| `MODEL_PROVIDER` | `openai` | 官方客户端类型 |
| `JAVA_HOME` | 系统配置 | 指向 JDK 17+ |
| `MAVEN_CMD` | `mvn` | 根目录回归脚本调用 Maven 时使用；直接执行 Maven 不需要此变量 |

首次运行 Maven 会下载官方 SDK、JUnit 及其依赖。

## 演示入口

```bash
cd samples/23_java_tool_rails
mvn -q compile exec:java
```

`main` 按业务分支处理暂停与恢复，不包含断言、验收计数器、轨迹校验或 `SUCCESS` 标记。SDK 返回业务错误时这里仅展示结果，正确性由下面的测试判断。

## 验收方式

```bash
# 23 个单元用例，不需要模型服务
mvn test

# 单元用例 + 真实模型验收；模型不可达会失败，不跳过
mvn -Preal-model test

# 根目录的一键回归同样运行 JUnit，不在 main 里验收
cd ../..
python3 run_all_samples.py 23
```

| 测试文件 | 覆盖内容 |
|---|---|
| [CreditToolRailsTest.java](src/test/java/CreditToolRailsTest.java) | 金额边界、缺失/非法参数、审批放行/拒绝、临时错误重试成功/耗尽、业务错误及审批中断不重试 |
| [CreditToolRailsIntegrationTest.java](src/test/java/CreditToolRailsIntegrationTest.java) | 调用真实模型，检查审批前不执行银行工具；批准后恢复、仅重试一次并返回正确客户和金额；拒绝后不执行银行工具 |

真实模型验收使用 `integration` 标签，默认 `mvn test` 不运行；`real-model` profile 将其纳入同一套 JUnit 测试。测试观察器与断言均留在 `src/test/java`。

## 实测输出

2026-10-27，本机 JDK 17、Maven 3.9.9、官方 SDK 0.1.16，连接 LM Studio `google/gemma-4-e4b` 真实运行。模型措辞可能变化。

演示入口的完整输出保存在 [run_output.txt](run_output.txt)：

```text
首次结果: interrupt
审批通过，恢复原会话
异常处理: BANK_BUSY，重试一次
恢复结果: {output=贷款已发放，参考编号为 DEMO-LOAN-001。, result_type=answer}
```

JUnit 与根目录回归输出摘录如下，完整记录见 [test_output.txt](test_output.txt)：

```text
Tests run: 23, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.315 s -- in CreditToolRailsTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 31.56 s -- in CreditToolRailsIntegrationTest
[PASS] 23_java_tool_rails           (33.7s)
===== 汇总: 1/1 通过 =====
```

共 25 个用例（23 个单元用例、2 个真实模型验收），失败、错误、跳过均为 0。

## 注意点与官方参考

- 本例用 `InMemoryCheckpointer` 保存会话，只演示同进程中断恢复。跨进程恢复需要换为官方持久化实现或自定义 `Checkpointer`。
- `reject(...)` 是可处理的业务结果：模型会据此回答，通常返回 `result_type=answer`；未恢复的中断返回 `interrupt`。
- `shouldFailTaskOnToolError(true)` 保留默认行为：重试耗尽后以错误结束；审批拒绝不属于工具执行异常。
- 框架没有独立的 `onResume` 事件；本例通过 `BaseInterruptRail.resolveInterrupt(..., userInput)` 参与恢复决策。
- 所有核心能力直接使用官方 SDK，没有实现本地 Agent/Tool 执行器或模型适配层。

API 用法参考官方 [ReActWeatherAgentExample](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/examples/react_agent/ReActWeatherAgentExample.java)、[ReActInterruptResumeExample](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/examples/react_agent/ReActInterruptResumeExample.java)、[ToolErrorTerminalStateExample](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/examples/react_agent/ToolErrorTerminalStateExample.java)，并核对 [BaseInterruptRail](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/core/src/main/java/com/openjiuwen/harness/rails/interrupt/BaseInterruptRail.java) 与 [RailExecutor](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/core/src/main/java/com/openjiuwen/core/singleagent/rail/RailExecutor.java) 源码。官方示例中的模型替身未复制到本例。
