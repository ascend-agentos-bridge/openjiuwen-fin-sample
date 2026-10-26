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

业务数据是代码内明确标注的虚构客户演示记录；模型推理、工具选择、两次委派和逐级汇总均调用真实 LM Studio。运行时工具生成随机报告编号，主管最终输出必须保留该编号，防止绕过工具也通过验收。该样例展示调用机制，未实现真实银行审批规则。

## 代码详解

- `src/main/java/CreditNestedAgents.java`：单个 Java 源文件，直接使用官方 SDK，不实现 Agent/LLM/Tool 核心封装。
- `HierarchicalToolsTeam` 自动把子 Agent 注册为父 Agent 可见的工具；只给主管暴露 `risk_officer`，只给风控专员暴露 `credit_checker`。
- 子 Agent 的 `AgentCard.inputParams` 使用 `query` 字段，匹配 `ReActAgent.invoke()`；不使用默认的 `message` schema。
- `LocalFunction` 只实现演示业务查询，仅第三层能调用。
- 官方 `AgentCallbackEvent` 记录进入/退出、成功模型调用和工具调用，作为程序验收证据。
- `main.py` 只启动 Maven，供现有 `run_all_samples.py` 发现及运行；Agent 逻辑全部在 Java 中。

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

也可以从仓库根目录运行：

```bash
python run_all_samples.py 20
```

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
python run_all_samples.py 20
```

## 验收方式

程序自带运行后检查，抛异常/Maven 非零退出即失败：

1. 观察到进入顺序为主管 → 风控专员 → 核验员，退出顺序相反。
2. 观察到主管调用风控专员、风控专员调用核验员、核验员调用查询工具，且本例每条边只执行一次。
3. 三个 Agent 各至少完成一次真实模型调用。
4. 演示征信工具确实查询 `C1001`，最终主管回答包含工具本次生成的随机报告编号。
5. 团队返回 `result_type=answer`，打印 `SUCCESS:`。

## 实测输出

2026-10-26 在本机真实运行：Amazon Corretto JDK 17.0.21、Maven 3.9.9、官方 SDK 0.1.16，LM Studio `google/gemma-4-e4b`。以下从运行日志提取，省略框架 INFO 日志；三层各调用模型 2 次，共 6 次。输出副本见 [verification-output.txt](verification-output.txt)。

```text
SDK: com.openjiuwen:agent-core-java:0.1.16
MODEL: google/gemma-4-e4b
ENTER credit_master
CALL  credit_master -> risk_officer
ENTER risk_officer
CALL  risk_officer -> credit_checker
ENTER credit_checker
CALL  credit_checker -> query_credit_record
DATA  customer=C1001 report_id=CR-2ca87ea7
EXIT  credit_checker
EXIT  risk_officer
EXIT  credit_master
RESULT: {output=根据报告编号 CR-2ca87ea7，客户 C1001 的征信记录良好，信用评分 735，过去 24 个月内无逾期。建议继续进行后续的贷前审批流程。, result_type=answer}
NESTING: [enter:credit_master, enter:risk_officer, enter:credit_checker, exit:credit_checker, exit:risk_officer, exit:credit_master]
MODEL_CALLS: {credit_master=2, risk_officer=2, credit_checker=2}
SUCCESS: Java 三层 Agent 委派、真实模型调用和报告编号逐级回传验收通过
```

每次运行的随机报告编号及模型回答措辞会变化；验收不匹配固定编号或固定回答。

同日再通过统一回归入口执行，输出为：

```text
[PASS] 20_java_nested_agents        (79.5s)

===== 汇总: 1/1 通过 =====
```

本次仅回归新增的 20，未重跑未修改的 01–19。

## 注意点

- 这是层级式请求/返回委派，适合父 Agent 汇总子 Agent 结果；与样例 12 的 handoff 转交语义不同。
- `maxParallelToolCalls=1` 让三层调用次序清晰；模型超时设为 600 秒，适应本地推理。
- 若模型只输出“我会转交”但未产生工具调用，验收会失败。换模型后应重新运行验收，不能只检查回答措辞。
- 不把 Java 源码复制进样例充当 SDK，不修改 SDK 源码；Maven 直接依赖官方发布构件。
- 本例真实验证三层；更深结构的延迟、上下文开销和稳定性需在目标模型及环境中单独测量。
