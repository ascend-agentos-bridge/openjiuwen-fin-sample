# 样例 20 · Java 三层子智能体委派（银行贷前审查）

依赖：**`com.openjiuwen:agent-core-java:0.1.16`**。截至 2026-10-26，已核对 [GitHub 最新 release](https://github.com/openJiuwen-ai/agent-core-java/releases/tag/v0.1.16) 和 [Maven Central 版本元数据](https://repo.maven.apache.org/maven2/com/openjiuwen/agent-core-java/maven-metadata.xml)。

## 支持多层吗？最多几层？

**支持 Master → SubAgent → SubAgent。** 本样例用官方 `HierarchicalToolsTeam`，通过 `addAgent(card, provider, parentAgentId)` 定义两条父子边；第三层仍是完整的、调用真实模型的 `ReActAgent`。

层数必须区分具体 API：

| 实现 | v0.1.16 源码核实结果 |
|---|---|
| `HierarchicalToolsTeam` | `parentByAgent` 可记录多层父子关系，没有单独的配置树深度上限。共享 `AgentGroupSessionApi` 的 `TeamRuntime.send()` 有硬编码的 **64 层同时嵌套 dispatch** 保护；团队入口调用根 Agent 也占一层，即单链场景为根节点 + 最多 63 层子节点。第 65 次同时嵌套派发被拒绝。此数字是源码防护边界，不是 64 层真实模型压测结果。 |
| `DeepAgent` / `SubAgentConfig` | `SubAgentConfig.subagents` 可递归配置，`toDeepAgentConfig()` 保留子配置，`createSubagent()` 再创建子 `DeepAgent`；`TaskTool.delegate()` 调用子 Agent。核查这条路径未发现独立的最大层数参数，不能套用团队运行时的 64 常量，也不能承诺无限层。 |

`maxIterations` 控制单个 Agent 的模型循环次数；`maxAgents` 表达团队成员数量配置，均不等同于层数。源码的 64 防护只有在 dispatch 传递同一团队会话时生效，本例显式传入团队会话并保持单工具串行委派。

依据（全部固定到 release tag）：

- [HierarchicalToolsTeam.addAgent / injectChildCards](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/core/src/main/java/com/openjiuwen/core/multiagent/teams/hierarchical_tools/HierarchicalToolsTeam.java)
- [TeamRuntime.MAX_DISPATCH_DEPTH / send](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/core/src/main/java/com/openjiuwen/core/multiagent/runtime/TeamRuntime.java#L35)
- [HierarchicalDelegateTool.invoke：传递同一团队会话](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/core/src/main/java/com/openjiuwen/core/multiagent/teams/hierarchical_tools/HierarchicalDelegateTool.java)
- [SubAgentConfig.toDeepAgentConfig](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/core/src/main/java/com/openjiuwen/harness/subagents/SubAgentConfig.java)、[DeepAgent.createSubagent](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/core/src/main/java/com/openjiuwen/harness/deep_agent/DeepAgent.java)、[TaskTool.delegate](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/core/src/main/java/com/openjiuwen/harness/tools/TaskTool.java)

## 场景

```text
客户请求：核查 C1001 的贷前征信风险
  └─ credit_master   审批主管（Master）
       └─ risk_officer     风控专员（SubAgent）
            └─ credit_checker   征信核验员（SubAgent 的 SubAgent）
                 └─ query_credit_record   本地演示业务数据工具

征信记录 → 核验结论 → 风控建议 → 主管汇总
```

业务数据是代码内明确标注的虚构客户演示记录；模型推理、工具选择、两次委派和逐级汇总均调用真实 LM Studio。运行时工具生成随机报告编号，由核验员、风控专员和主管逐级保留。该样例展示调用机制，未实现真实银行审批规则。

## 代码详解

- `src/main/java/CreditNestedAgents.java`：直接使用官方 SDK；`main` 只装配业务、调用 `team.invoke()`、输出业务结果并释放资源。具名静态方法分别装配三个业务角色、征信工具和团队，没有 Agent/LLM/Tool 核心封装。
- `HierarchicalToolsTeam` 自动把子 Agent 注册为父 Agent 可见的工具；只给主管暴露 `risk_officer`，只给风控专员暴露 `credit_checker`。
- 子 Agent 的 `AgentCard.inputParams` 使用 `query` 字段，匹配 `ReActAgent.invoke()`；不使用默认的 `message` schema。
- `LocalFunction` 只实现演示业务查询，仅第三层能调用。
- 主程序通过官方 `AgentCallbackEvent` 输出进入/退出和工具调用日志，便于观察业务委派过程；验收状态和断言全部位于 `src/test/java/`。
- `CreditNestedAgentsTest` 直接调用真实 `LocalFunction`，检查演示记录、未知客户拒绝和报告编号生成；不调用或替换模型。
- `CreditNestedAgentsIntegrationTest` 标记 `@Tag("integration")`，复用业务装配方法，连接真实模型检查嵌套顺序、调用次数、工具参数及各层报告编号回传。
- `main.py` 只启动 `mvn compile exec:java`；`test.py` 只启动 `mvn -Preal-model test`，供根目录统一回归入口运行。

API 用法参考官方 [ReActWeatherAgentExample](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/examples/react_agent/ReActWeatherAgentExample.java)、[Hierarchical Teams 示例说明](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/examples/multi_agent/hierarchical/README.md)，并核对上述框架源码。官方 README 的安装片段可能仍写旧版版本号，本例以 release 和 Central 的 `0.1.16` 为准。

## 运行方式

前提：**JDK 17+、Maven 3.9+**，LM Studio 启动 OpenAI 兼容服务并加载支持工具调用的模型。首次 Maven 构建需要访问 Maven Central。

```bash
java -version
mvn -version
curl http://127.0.0.1:1234/v1/models

cd samples/20_java_nested_agents
mvn -q compile exec:java
```

也可以通过 Python 启动同一业务演示：

```bash
python samples/20_java_nested_agents/main.py
```

主程序保留 `ENTER`、`CALL`、`DATA`、`EXIT` 和 `RESULT` 业务日志；框架返回 `result_type=error` 时抛出包含原始错误的异常。报告编号、调用顺序与次数由测试检查。

| 环境变量 | 默认值 | 用途 |
|---|---|---|
| `API_BASE` | `http://127.0.0.1:1234/v1` | OpenAI 兼容服务地址 |
| `API_KEY` | `lm-studio` | 模型服务密钥；不写入运行输出 |
| `MODEL_NAME` | `google/gemma-4-e4b` | 本机检测到的聊天模型；其他模型用此变量覆盖 |
| `MODEL_PROVIDER` | `openai` | SDK 模型客户端 provider |
| `MAVEN_CMD` | `mvn` | 仅 Python 启动器使用；可指定 Maven 可执行文件绝对路径 |

例如：

```bash
export API_BASE=http://127.0.0.1:1234/v1
export MODEL_NAME=google/gemma-4-e4b
python samples/20_java_nested_agents/main.py
```

## 验收方式

JUnit 5.10.2 和 Maven Surefire 3.2.5 执行验收，测试失败时 Maven 非零退出。业务主程序不包含验收逻辑。

```bash
cd samples/20_java_nested_agents
mvn -q test                     # 只执行离线单元测试，不连接模型
mvn -q -Preal-model test         # 单元测试 + 真实模型集成测试
```

从仓库根目录执行完整回归：

```bash
python samples/20_java_nested_agents/test.py
python run_all_samples.py 20
```

默认 `mvn test` 排除 `integration` 标签；`real-model` profile 显式启用真实模型测试。集成测试检查：

1. 观察到进入顺序为主管 → 风控专员 → 核验员，退出顺序相反。
2. 观察到主管调用风控专员、风控专员调用核验员、核验员调用查询工具，且本例每条边只执行一次。
3. 三个 Agent 各至少完成两次真实模型调用，分别完成工具选择与结果汇总，并打印实际次数。
4. 两次委派的 `query` 保留客户编号 `C1001`；征信查询参数为 `customer_id=C1001`。
5. 征信工具生成的随机报告编号出现在核验员、风控专员和主管的答案中，三层均返回 `result_type=answer`。

集成测试打印实际 `NESTING`、`CALLS`、`MODEL_CALLS`、`TOOL_ARGUMENTS` 和 `RESULT`，供排查及记录实测结果。JUnit 汇总为最终验收依据，不匹配固定报告编号或回答措辞，也不 mock LLM。

## 重构后实测输出

以下为重构后 JUnit 真实模型集成测试的观察结果，使用 LM Studio `google/gemma-4-e4b`、Corretto 17.0.21、Maven 3.9.9、官方 SDK 0.1.16。顺序、次数和报告编号检查均在测试中完成；业务 main 不打印这些验收汇总。

```text
NESTING: [enter:credit_master, enter:risk_officer, enter:credit_checker, exit:credit_checker, exit:risk_officer, exit:credit_master]
CALLS: [credit_master -> risk_officer, risk_officer -> credit_checker, credit_checker -> query_credit_record]
MODEL_CALLS: {credit_master=2, risk_officer=2, credit_checker=2}
TOOL_ARGUMENTS: {credit_master -> risk_officer={"query":"请对客户 C1001 做贷前征信核查，给出简短审查建议并注明报告编号。"}, credit_checker -> query_credit_record={"customer_id":"C1001"}, risk_officer -> credit_checker={"query":"请对客户 C1001 进行贷前征信核查，并给出简短审查建议。"}}
RESULT: {output=根据报告编号 CR-dcc9abc8，客户 C1001 的征信记录良好，信用评分 735，过去 24 个月内无逾期。建议继续进行贷前审批流程。, result_type=answer}

```

完整摘录见 [verification-output.txt](verification-output.txt)。`mvn -Preal-model test` 实测 **6 个测试通过（5 个单元测试、1 个真实模型集成测试），失败/错误/跳过均为 0**。每次运行的报告编号和回答措辞会变化，不使用保存的模型输出代替执行。

## 注意点

- 这是层级式请求/返回委派，适合父 Agent 汇总子 Agent 结果；与样例 12 的 handoff 转交语义不同。
- `maxParallelToolCalls=1` 让三层调用次序清晰；模型超时设为 600 秒，适应本地推理。
- 若模型只输出“我会转交”但未产生工具调用，验收会失败。换模型后应重新运行验收，不能只检查回答措辞。
- 不把 Java 源码复制进样例充当 SDK，不修改 SDK 源码；Maven 直接依赖官方发布构件。
- 本例真实验证三层；更深结构的延迟、上下文开销和稳定性需在目标模型及环境中单独测量。
