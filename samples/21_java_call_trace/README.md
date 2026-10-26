# 21 · Java 调用链轨迹日志（本地 LM Studio）

**支持。** Java Agent Core `0.1.16` 内置 `OtelRail`，可在 ReActAgent 执行时生成 Agent、LLM、工具调用 span，通过 `StreamMode.TRACE` 输出。本例把官方 `TraceSchema` 事件交给 SLF4J/Logback，同时打印到控制台并写入 `logs/call_trace.log`，无需部署链路追踪服务。

## 场景

银行贷前审查助手查询客户 C1001 的演示征信档案：模型决定调用 `query_credit_report`，工具返回信用评分和本次运行独有的报告编号，模型再生成摘要。模型请求全部发往真实 LM Studio；征信档案是明确标注的本地演示数据。

执行顺序：`Agent 开始 → LLM 调工具 → 征信工具 → LLM 总结 → Agent 完成`。调用树中，两次 LLM 和工具是同一个 Agent 根 span 的三个子节点；日志中的 `parentInvokeId` 可还原这个关系。

## 版本和官方依据

- Java SDK：`com.openjiuwen:agent-core-java:0.1.16`，本次核对的[最新 release](https://github.com/openJiuwen-ai/agent-core-java/releases/tag/v0.1.16)，发布于 2026-09-28。
- Agent 和模型配置参考官方 [ReActWeatherAgentExample.java](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/examples/react_agent/ReActWeatherAgentExample.java)。
- 本地函数工具参考官方 [ToolErrorTerminalStateExample.java](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/examples/react_agent/ToolErrorTerminalStateExample.java)；本例工具正常返回演示档案。
- 追踪使用官方 [OtelRail.java](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/core/src/main/java/com/openjiuwen/extensions/tracerotel/OtelRail.java)、[TraceAgentSpan.java](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/core/src/main/java/com/openjiuwen/core/session/tracer/TraceAgentSpan.java) 和 [StreamMode.java](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/core/src/main/java/com/openjiuwen/core/session/stream/StreamMode.java)。没有本地封装模型、Agent 或追踪接口。

## 运行方式

前提：JDK 17+、Maven 3.9+；LM Studio 启动本地服务，加载支持工具调用的模型。默认使用本机已加载的 `google/gemma-4-e4b`。

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `API_BASE` | `http://127.0.0.1:1234/v1` | LM Studio 的 OpenAI 兼容接口 |
| `API_KEY` | `lm-studio` | 服务启用认证时替换为实际密钥 |
| `MODEL_NAME` | `google/gemma-4-e4b` | LM Studio 提供的模型标识 |
| `JAVA_HOME` | 系统 JDK | 必须指向 JDK 17+ |
| `MAVEN_CMD` | `mvn` | 仅 Python 启动入口使用，可指定 Maven 可执行文件的完整路径 |

在仓库根目录执行：

```bash
cd samples/21_java_call_trace
mvn -q compile exec:java
cat logs/call_trace.log
```

首次运行由 Maven 下载官方 Java SDK 及其传递依赖。更换模型时先设置 `MODEL_NAME`；本例固定使用 `openai` provider 连接兼容接口。

接入仓库统一回归（在仓库根目录执行，需要 Python 3.11+）：

```bash
python3 run_all_samples.py 21
```

`main.py` 只负责启动 Maven、传递退出码，最长等待 300 秒；全部 Agent、模型和工具逻辑都在 Java 文件里。

## 代码详解

[`CreditCallTrace.java`](src/main/java/CreditCallTrace.java) 是单文件入口：

1. 用官方 `ReActAgentConfig.configureModelClient(...)` 配置 LM Studio，注册官方 `LocalFunction` 征信工具。
2. `agent.registerRail(new OtelRail())` 开启 Agent、模型、工具的 span 生命周期追踪。
3. `Runner.runAgentStreaming(..., List.of(StreamMode.TRACE, StreamMode.OUTPUT))` 获取执行事件。
4. 消费 `TraceSchema` 的 `TraceAgentSpan`，逐条记录 `traceId`、`invokeId`、`parentInvokeId`、类型、名称、状态和耗时；按调用 ID 保留最后一帧供验收。
5. 校验最终回答和 span 关系，检查日志实际落盘；在 `finally` 关闭流、移除工具、释放会话并停止 Runner。

[`logback-credit-trace.xml`](src/main/resources/logback-credit-trace.xml) 只负责日志格式和输出位置，Maven exec 插件通过 `logback.configurationFile` 显式选择它。日志路径相对样例工作目录，每次运行覆盖旧文件。若用 IDE 或直接运行 Java，请设置 JVM 参数 `-Dlogback.configurationFile=logback-credit-trace.xml`，并把工作目录设为本样例目录。

## 验收方式

通过时打印 `SUCCESS:`，退出码 0。程序断言以下事实，任意一项失败都会返回非零：

- 有且仅有一个成功完成的 Agent 根 span，具有非空 `traceId`。
- 至少两次完成的 LLM 调用和一次征信工具调用；所有 span 都成功结束且无错误。
- 所有 span 的 `traceId` 相同，所有子 span 的 `parentInvokeId` 指向该 Agent 根 span。
- 工具输入包含客户 C1001，工具输出包含本次运行生成的报告编号，最终回答也包含该编号，且结果类型为 `answer`。
- 当前链路 ID 已写入 `logs/call_trace.log`；空轨迹、模型不可用、流错误或没有实际调用工具都不能通过。

## 实测输出

2026-10-26 在本机 LM Studio、`google/gemma-4-e4b`、Java SDK `0.1.16` 上真实运行。下面完整展示本次 `logs/call_trace.log` 的输出，包含配置、调用开始/完成、最终回答和验收结果；同一份日志也保存为 [`run_output.txt`](run_output.txt)。每次运行的 ID、措辞和耗时会变化。

### call_trace.log 输出数据样例

```text
2026-10-26 15:28:38.011 INFO  sample.call_trace - CONFIG: agent-core-java=0.1.16 model=google/gemma-4-e4b apiBase=http://127.0.0.1:1234/v1
2026-10-26 15:28:38.205 INFO  sample.call_trace - TRACE traceId=e1efd974-07ca-470e-8c5c-62523d8e59ce invokeId=55efa16b-4dff-40f4-bb15-4e13c947b79f parentInvokeId=null type=chain name=credit_trace_agent status=start elapsed=null
2026-10-26 15:28:40.128 INFO  sample.call_trace - TRACE traceId=e1efd974-07ca-470e-8c5c-62523d8e59ce invokeId=d64584e9-3d87-4f5e-b47c-9cc67671ab87 parentInvokeId=55efa16b-4dff-40f4-bb15-4e13c947b79f type=llm name=google/gemma-4-e4b status=start elapsed=null
2026-10-26 15:28:50.886 INFO  sample.call_trace - TRACE traceId=e1efd974-07ca-470e-8c5c-62523d8e59ce invokeId=d64584e9-3d87-4f5e-b47c-9cc67671ab87 parentInvokeId=55efa16b-4dff-40f4-bb15-4e13c947b79f type=llm name=google/gemma-4-e4b status=finish elapsed=12.68s
2026-10-26 15:28:50.888 INFO  sample.call_trace - TRACE traceId=e1efd974-07ca-470e-8c5c-62523d8e59ce invokeId=43cc109e-2a54-4163-8cba-8ea9c39c04bd parentInvokeId=55efa16b-4dff-40f4-bb15-4e13c947b79f type=plugin name=query_credit_report status=start elapsed=null
2026-10-26 15:28:50.890 INFO  sample.call_trace - TRACE traceId=e1efd974-07ca-470e-8c5c-62523d8e59ce invokeId=43cc109e-2a54-4163-8cba-8ea9c39c04bd parentInvokeId=55efa16b-4dff-40f4-bb15-4e13c947b79f type=plugin name=query_credit_report status=finish elapsed=1ms
2026-10-26 15:28:51.988 INFO  sample.call_trace - TRACE traceId=e1efd974-07ca-470e-8c5c-62523d8e59ce invokeId=eb006fd2-9b08-4748-9698-d135fdb00e7f parentInvokeId=55efa16b-4dff-40f4-bb15-4e13c947b79f type=llm name=google/gemma-4-e4b status=start elapsed=null
2026-10-26 15:28:52.893 INFO  sample.call_trace - TRACE traceId=e1efd974-07ca-470e-8c5c-62523d8e59ce invokeId=eb006fd2-9b08-4748-9698-d135fdb00e7f parentInvokeId=55efa16b-4dff-40f4-bb15-4e13c947b79f type=llm name=google/gemma-4-e4b status=finish elapsed=2.00s
2026-10-26 15:28:52.893 INFO  sample.call_trace - TRACE traceId=e1efd974-07ca-470e-8c5c-62523d8e59ce invokeId=55efa16b-4dff-40f4-bb15-4e13c947b79f parentInvokeId=null type=chain name=credit_trace_agent status=finish elapsed=14.79s
2026-10-26 15:28:52.895 INFO  sample.call_trace - RESULT: {outputs={output=报告编号：CR-74305066，客户编号：C1001，信用评分：735。, result_type=answer}}
2026-10-26 15:28:52.896 INFO  sample.call_trace - SUCCESS: Java 调用链验收通过，4 个 span，Agent → LLM → 征信工具 → LLM → 回答，日志位于 logs/call_trace.log
```

这次执行产生 8 条 TRACE 事件，对应 4 次调用：1 个 Agent、2 次 LLM、1 次征信工具调用。每次调用都有 `start` 和 `finish` 两条事件，使用同一个 `invokeId`。

| 日志字段 | 含义与本例读法 |
|---|---|
| `traceId` | 整次请求的链路 ID；上面 8 条 TRACE 事件都属于同一条链路 |
| `invokeId` | 单次调用的 ID，用于匹配该调用的开始与完成事件 |
| `parentInvokeId` | 父调用 ID；Agent 根调用为 `null`，两次 LLM 和征信工具均指向该 Agent 的 `invokeId` |
| `type` / `name` | 调用类型和名称：`chain` / `credit_trace_agent`、`llm` / `google/gemma-4-e4b`、`plugin` / `query_credit_report` |
| `status` | `start` 为调用开始，`finish` 为成功完成；异常路径会输出 `error` |
| `elapsed` | 框架记录的调用耗时；开始时为 `null`，本次 Agent 总耗时为 `14.79s` |
| `RESULT` | 最终业务回答，包含工具返回的报告编号 `CR-74305066`、客户编号 `C1001` 和评分 `735` |
| `SUCCESS` | sample 的验收结果；本次 4 个 span 的状态、父子关系和日志落盘均通过检查 |

统一回归实测：

```text
[PASS] 21_java_call_trace           (32.3s)

===== 汇总: 1/1 通过 =====
```

另以不可达的 `http://127.0.0.1:1/v1` 验证失败路径：官方模型客户端重试 3 次，LLM 和 Agent 根 span 均输出 `status=error`，Maven 退出码为 1，未打印 `SUCCESS:`。

## 注意点

- 注册 `OtelRail` 才会为本例 ReActAgent 生成 Agent、LLM、工具 span；普通框架日志不能替代这些轨迹事件。
- 本例用官方 rail 生成 TRACE 数据，并直接写普通日志，没有初始化 OTLP exporter，也不需要 Jaeger、Collector 或外部遥测后端。
- `type=chain` 表示 Agent，`type=llm` 表示模型调用，`type=plugin` 表示工具；每个调用会有开始、完成等多帧，验收按 `invokeId` 合并。
- `elapsed` 来自框架的 span 计时；开始事件还没有结束时间，显示 `null`。
- 仅输出调用元数据和最终演示回答，API Key 不写日志。
