# 21 · Java 调用链完整日志（本地 LM Studio）

**现有框架已支持，无需修改 Agent Core。** 本例直接使用 Java SDK `0.1.16` 的 `OtelRail` 和 `TraceAgentSpan`，保存官方 span 中的输入、输出、时间、元数据和错误，以及模型响应里的工具调用、结束原因和用量。日志同时输出控制台、`logs/call_trace.log` 和便于分析的 `logs/call_trace.jsonl`。

## 场景

银行贷前审查助手查询客户 C1001 的演示征信档案。模型调用 `query_credit_report`，工具返回信用评分和本次运行独有的报告编号，模型再生成摘要。模型请求全部发往真实 LM Studio；征信档案明确标注为演示数据。

执行顺序为 `Agent 开始 → LLM 调工具 → 征信工具 → LLM 总结 → Agent 完成`。两次 LLM 和工具是同一个 Agent 根 span 的三个子节点。每条记录都有 `runId`、`sessionId`、`caseId` 和 `traceId`，便于将最终结果、错误和后续外部反馈关联起来。

## 版本和官方依据

- Java SDK：`com.openjiuwen:agent-core-java:0.1.16`，本次核对的[最新 release](https://github.com/openJiuwen-ai/agent-core-java/releases/tag/v0.1.16)，发布于 2026-09-28。
- Agent 和模型配置参考官方 [ReActWeatherAgentExample.java](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/examples/react_agent/ReActWeatherAgentExample.java)。
- 本地函数工具参考官方 [ToolErrorTerminalStateExample.java](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/examples/react_agent/ToolErrorTerminalStateExample.java)；本例工具正常返回演示档案。
- 追踪使用官方 [OtelRail.java](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/core/src/main/java/com/openjiuwen/extensions/tracerotel/OtelRail.java)、[TraceAgentSpan.java](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/core/src/main/java/com/openjiuwen/core/session/tracer/TraceAgentSpan.java) 和 [StreamMode.java](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/core/src/main/java/com/openjiuwen/core/session/stream/StreamMode.java)。没有本地封装模型、Agent 或追踪接口。

## 运行方式

前提：JDK 17+、Maven 3.9+；LM Studio 启动本地服务，加载支持工具调用的模型。本例默认使用本机的 `google/gemma-4-e4b`。

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `API_BASE` | `http://127.0.0.1:1234/v1` | LM Studio 的 OpenAI 兼容接口 |
| `API_KEY` | `lm-studio` | 服务启用认证时替换为实际密钥，配置不写入日志 |
| `MODEL_NAME` | `google/gemma-4-e4b` | LM Studio 提供的模型标识 |
| `JAVA_HOME` | 系统 JDK | 必须指向 JDK 17+ |
| `MAVEN_CMD` | `mvn` | Python 入口使用，可指定 Maven 可执行文件的完整路径 |

在仓库根目录执行：

```bash
cd samples/21_java_call_trace
mvn -q compile exec:java
cat logs/call_trace.log
# 或使用只负责启动演示的 Python 入口
python3 main.py
```

首次执行由 Maven 下载官方 Java SDK 及其传递依赖。模型 provider 固定为 `openai`。使用官方 `ModelRequestConfig.setExtraField("stream_options", Map.of("include_usage", true))` 请求流式用量；没有自行发送模型 HTTP 请求或修改框架。

`main.py` 只启动 `mvn compile exec:java` 并传递退出码，Java 最长等待 300 秒。正常演示消费官方 `OutputSchema(type=answer)` 输出业务 `RESULT`，不回读日志、不缓存 span 做断言，也不产生 `VALIDATION` / `SUCCESS` 验收事件。

## 测试与验收

离线单元测试不访问模型服务，覆盖演示/测试启动器边界、JSONL 转义与关联字段、官方输出事件转为业务结果、错误堆栈、span 序列化、用量缺失/全零标记、配置指纹和演示征信工具。测试中构造的 schema 仅用于格式化检查，不用于替代 LLM 调用。

```bash
cd samples/21_java_call_trace
mvn -q test
```

真实模型验收使用 JUnit `@Tag("integration")`，通过 `real-model` profile 开启。它在独立 JVM 运行同一个 `CreditCallTrace.main`，检查真实 LM Studio 输出的 Agent/LLM/工具 span、父子关系、工具调用参数、本次工具生成的报告编号、用量与文件一致性。另一个集成用例访问本机不可达端口，验证实际连接失败的错误轨迹和非零退出码。测试不使用 mock 模型或保存的模型回答。

```bash
cd samples/21_java_call_trace
mvn -q -Preal-model test
# 或运行测试启动器
python3 test.py
```

JUnit 会打印本次真实 `RESULT`、`TRACE SUMMARY`（span/模型/工具数量）和 `USAGE SUMMARY`，用于核对当前运行；完整子进程输出保存在 `target/real-model-output.txt` 和 `target/unavailable-service-output.txt`。测试报告在 `target/surefire-reports/`。只有真实模型集成测试成功后，才能把本次输出作为新版实测证据。

在仓库根目录执行统一回归（Python 3.11+）：

```bash
python3 run_all_samples.py 21
```

统一回归使用独立测试入口 `test.py`；演示入口与验收入口分开。

## 日志包含的数据

| 位置 | 数据与用途 |
|---|---|
| 事件公共字段 | `schemaVersion`、UTC `timestamp`、`event`、`runId`、`sessionId`、固定案例 `caseId=credit_c1001`、`traceId` |
| `CONFIG.data` | SDK/JDK 版本、运行时区、模型 provider/地址、官方模型请求配置（含温度、max_tokens、stream_options）、最大迭代次数、完整系统提示和工具定义 |
| 配置指纹 | `promptSha256`、按键排序后的 `toolSchemaSha256`、`sampleSourceSha256`，用于对比提示、工具 schema 和样例代码变化；没有源文件时源码指纹为 `null` |
| `TRACE.data.span` | 直接序列化官方 span：`invokeId`、`parentInvokeId`、`childInvokesId`、`invokeType`、`name`、`status`、`startTime`、`endTime`、`elapsedTime`、`metaData`、`onInvokeData` |
| `span.inputs` / `span.outputs` | Agent 查询及最终回答、每次模型的完整 messages 和 AssistantMessage、工具调用名/参数/返回值；工具参数可能是官方保留的 JSON 字符串 |
| `TRACE.data.durationMs` | 根据 span 的起止时间计算的数值毫秒，调用尚未结束时为 `null`，便于批量统计 |
| LLM 的 `data.usage` / `usageStatus` | 原始官方 UsageMetadata，含 input/output/total/cache token 等；非零计数标记 `reported_by_sdk`，缺失或全零标记 `not_reported_or_zero` |
| `span.error` / `RUN_ERROR.data` | 官方调用错误（当前通常为 message），以及样例运行异常的类型、message、堆栈；错误事件也关联链路 |
| `RESULT.data` | 直接来自官方 `OutputSchema(type=answer).payload` 的最终业务结果，包含 `result_type` 和 `output` |

`CONFIG` 在执行开始前输出，此时 `traceId=null`，通过相同 `runId` 关联后续轨迹。JSONL 每行一个 JSON 对象；可读日志在同一 JSON 前增加时间、级别和事件名，两份文件的事件逐条一致。

## 代码详解与验收

[`CreditCallTrace.java`](src/main/java/CreditCallTrace.java) 是单文件入口：用有名方法组装征信业务配置与工具，注册官方 OtelRail，消费 `StreamMode.TRACE` / `StreamMode.OUTPUT` 并输出结果，最后释放 stream、工具、session 和 Runner。Jackson 和文件写入仅负责样例输出格式，没有新增模型、Agent、工具或追踪框架封装。

[`logback-credit-trace.xml`](src/main/resources/logback-credit-trace.xml) 控制可读日志格式；Maven exec 插件通过 `logback.configurationFile` 选择它。若用 IDE 或直接运行 Java，请加 JVM 参数 `-Dlogback.configurationFile=logback-credit-trace.xml`，并把工作目录设为本样例目录。两份日志每次运行覆盖；本次实测另保存为下面的版本化文件。

所有验收断言都在 [`src/test/java/`](src/test/java/)：`CreditCallTraceTest` 覆盖离线业务/格式化逻辑，`CreditCallTraceLauncherTest` 检查两个 Python 启动器，`CreditCallTraceIntegrationTest` 执行真实链路，`TraceLogAssertions` 检查日志和业务契约。普通演示只因实际运行异常失败；测试负责报告断言失败。

## 重构后实测输出

以下来自当前业务入口的真实 LM Studio 执行；所有断言均由 JUnit 集成测试执行。数据文件 [run_output.txt](run_output.txt)、[run_trace.jsonl](run_trace.jsonl)、[run_error_trace.jsonl](run_error_trace.jsonl) 已同步为重构后的真实结果，不再包含验收事件。

JUnit 实际打印：

```text
ERROR TRACE: Agent 流执行失败: {output=Model stream failed after retries, no terminal result, result_type=error}
RESULT: 报告编号：CR-4cda47ab，客户编号：C1001，信用评分：735。
TRACE SUMMARY: spanCount=4, llmCalls=2, toolCalls=1
USAGE SUMMARY: inputTokens=428, outputTokens=220, totalTokens=648, reportedCalls=2/2
```

统一回归结果：

```text
[PASS] 21_java_call_trace           (30.9s)

===== 汇总: 1/1 通过 =====
```

共 **15 个测试通过（13 个单元测试、2 个集成测试）**。失败场景访问本机不可达地址，实际错误被记录为 RUN_ERROR 且进程非零退出；成功场景调用真实模型并保留本次工具生成的报告编号。

### call_trace.log 完整输出样例

<details>
<summary>展开当前业务日志（完整模型输入输出、用量和配置，不含验收断言事件）</summary>

```text
2026-10-27 09:05:33.795 INFO  sample.call_trace - CONFIG {"schemaVersion":1,"timestamp":"2026-10-27T01:05:33.776765Z","event":"CONFIG","runId":"13500e4c-328a-471b-9fda-c415287f242c","sessionId":"credit-trace-13500e4c-328a-471b-9fda-c415287f242c","caseId":"credit_c1001","traceId":null,"data":{"sdkVersion":"0.1.16","modelProvider":"openai","apiBase":"http://127.0.0.1:1234/v1","modelRequestConfig":{"temperature":0.1,"model":"google/gemma-4-e4b","top_p":0.1,"max_tokens":512,"stream_options":{"include_usage":true}},"maxIterations":3,"prompt":"你是银行贷前审查助手。必须先调用 query_credit_report 查询客户征信，每次只调用一次，获得结果后不要再次调用工具。用中文简短回答，必须原样包含报告编号、客户编号和信用评分。不得编造征信记录，不得作出实际放款承诺。","promptSha256":"d8bfd2f80a008cf97cf83688dacdf36731e269c6652ae6f0588c17c3f97bc24e","tools":[{"id":"query_credit_report","name":"query_credit_report","description":"查询客户的演示征信档案","inputParams":{"required":["customer_id"],"type":"object","properties":{"customer_id":{"type":"string","description":"客户编号，例如 C1001"}}},"properties":{}}],"toolSchemaSha256":"1b0d274f405535d9f845b9e0178cdf055c153368f7c80753a4db9fcfee83338a","sampleSourceSha256":"0d6993116d7b930e1b3b43f48302d088704a5e9c5e7793327b1d5152c1b1ce95","javaVersion":"17.0.21","timeZone":"Asia/Shanghai"}}
2026-10-27 09:05:33.901 INFO  sample.call_trace - TRACE {"schemaVersion":1,"timestamp":"2026-10-27T01:05:33.881082Z","event":"TRACE","runId":"13500e4c-328a-471b-9fda-c415287f242c","sessionId":"credit-trace-13500e4c-328a-471b-9fda-c415287f242c","caseId":"credit_c1001","traceId":"2a8ebb48-332a-4703-8a33-cea19b1f3afb","data":{"span":{"traceId":"2a8ebb48-332a-4703-8a33-cea19b1f3afb","startTime":"2026-10-27T09:05:33.813141","endTime":null,"inputs":{"query":"请查询客户 C1001 的征信档案，给出简短的贷前核验摘要，保留报告编号。"},"outputs":null,"error":null,"invokeId":"7dd4e056-4b6c-444f-b83f-0b66bd8e29cd","parentInvokeId":null,"childInvokesId":null,"status":"start","onInvokeData":null,"invokeType":"chain","name":"credit_trace_agent","elapsedTime":null,"metaData":{"type":"agent","class_name":"credit_trace_agent"}},"durationMs":null}}
2026-10-27 09:05:39.458 INFO  sample.call_trace - TRACE {"schemaVersion":1,"timestamp":"2026-10-27T01:05:39.437414Z","event":"TRACE","runId":"13500e4c-328a-471b-9fda-c415287f242c","sessionId":"credit-trace-13500e4c-328a-471b-9fda-c415287f242c","caseId":"credit_c1001","traceId":"2a8ebb48-332a-4703-8a33-cea19b1f3afb","data":{"span":{"traceId":"2a8ebb48-332a-4703-8a33-cea19b1f3afb","startTime":"2026-10-27T09:05:33.881016","endTime":null,"inputs":{"messages":[{"role":"system","content":"你是银行贷前审查助手。必须先调用 query_credit_report 查询客户征信，每次只调用一次，获得结果后不要再次调用工具。用中文简短回答，必须原样包含报告编号、客户编号和信用评分。不得编造征信记录，不得作出实际放款承诺。","contentAsString":"你是银行贷前审查助手。必须先调用 query_credit_report 查询客户征信，每次只调用一次，获得结果后不要再次调用工具。用中文简短回答，必须原样包含报告编号、客户编号和信用评分。不得编造征信记录，不得作出实际放款承诺。","contentAsList":[]},{"role":"user","content":"请查询客户 C1001 的征信档案，给出简短的贷前核验摘要，保留报告编号。","contentAsString":"请查询客户 C1001 的征信档案，给出简短的贷前核验摘要，保留报告编号。","contentAsList":[]}]},"outputs":null,"error":null,"invokeId":"bb8416e4-c97d-424e-bb12-c5be2d83c077","parentInvokeId":"7dd4e056-4b6c-444f-b83f-0b66bd8e29cd","childInvokesId":null,"status":"start","onInvokeData":null,"invokeType":"llm","name":"google/gemma-4-e4b","elapsedTime":null,"metaData":{"type":"llm","class_name":"google/gemma-4-e4b"}},"durationMs":null,"usage":null,"usageStatus":"not_reported_or_zero"}}
2026-10-27 09:05:49.886 INFO  sample.call_trace - TRACE {"schemaVersion":1,"timestamp":"2026-10-27T01:05:49.857404Z","event":"TRACE","runId":"13500e4c-328a-471b-9fda-c415287f242c","sessionId":"credit-trace-13500e4c-328a-471b-9fda-c415287f242c","caseId":"credit_c1001","traceId":"2a8ebb48-332a-4703-8a33-cea19b1f3afb","data":{"span":{"traceId":"2a8ebb48-332a-4703-8a33-cea19b1f3afb","startTime":"2026-10-27T09:05:33.881016","endTime":"2026-10-27T09:05:49.852016","inputs":{"messages":[{"role":"system","content":"你是银行贷前审查助手。必须先调用 query_credit_report 查询客户征信，每次只调用一次，获得结果后不要再次调用工具。用中文简短回答，必须原样包含报告编号、客户编号和信用评分。不得编造征信记录，不得作出实际放款承诺。","contentAsString":"你是银行贷前审查助手。必须先调用 query_credit_report 查询客户征信，每次只调用一次，获得结果后不要再次调用工具。用中文简短回答，必须原样包含报告编号、客户编号和信用评分。不得编造征信记录，不得作出实际放款承诺。","contentAsList":[]},{"role":"user","content":"请查询客户 C1001 的征信档案，给出简短的贷前核验摘要，保留报告编号。","contentAsString":"请查询客户 C1001 的征信档案，给出简短的贷前核验摘要，保留报告编号。","contentAsList":[]}]},"outputs":{"outputs":{"role":"assistant","content":"","metadata":{},"contentAsString":"","contentAsList":[],"tool_calls":[{"id":"842643577","type":"function","name":"query_credit_report","arguments":"{\"customer_id\":\"C1001\"}","index":0}],"usage_metadata":{"code":0,"prompt":"","err_msg":"","task_id":"","model_name":"google/gemma-4-e4b","total_latency":0.0,"first_token_time":"","request_start_time":"","input_tokens":173,"output_tokens":191,"total_tokens":364,"cache_tokens":0},"finish_reason":"tool_calls","reasoning_content":"\nThe user wants me to act as a bank pre-loan review assistant.\nI need to query the credit report for customer ID C1001 using the `query_credit_report` tool.\nI must call this tool only once.\nAfter receiving the result, I need to provide a brief pre-loan verification summary in Chinese.\nThe response must strictly include the report number, customer ID, and credit score.\nI must not fabricate any credit records or make actual lending commitments.\n\nPlan:\n1. Call `query_credit_report` with `customer_id=\"C1001\"`.\n2. Wait for the tool output.\n3. Format the final response according to the constraints (brief, Chinese, include report ID, customer ID, credit score)."},"response_string":"{\"role\":\"assistant\",\"content\":\"\",\"metadata\":{},\"contentAsString\":\"\",\"contentAsList\":[],\"tool_calls\":[{\"id\":\"842643577\",\"type\":\"function\",\"name\":\"query_credit_report\",\"arguments\":\"{\\\"customer_id\\\":\\\"C1001\\\"}\",\"index\":0}],\"usage_metadata\":{\"code\":0,\"prompt\":\"\",\"err_msg\":\"\",\"task_id\":\"\",\"model_name\":\"google/gemma-4-e4b\",\"total_latency\":0.0,\"first_token_time\":\"\",\"request_start_time\":\"\",\"input_tokens\":173,\"output_tokens\":191,\"total_tokens\":364,\"cache_tokens\":0},\"finish_reason\":\"tool_calls\",\"reasoning_content\":\"\\nThe user wants me to act as a bank pre-loan review assistant.\\nI need to query the credit report for customer ID C1001 using the `query_credit_report` tool.\\nI must call this tool only once.\\nAfter receiving the result, I need to provide a brief pre-loan verification summary in Chinese.\\nThe response must strictly include the report number, customer ID, and credit score.\\nI must not fabricate any credit records or make actual lending commitments.\\n\\nPlan:\\n1. Call `query_credit_report` with `customer_id=\\\"C1001\\\"`.\\n2. Wait for the tool output.\\n3. Format the final response according to the constraints (brief, Chinese, include report ID, customer ID, credit score).\"}"},"error":null,"invokeId":"bb8416e4-c97d-424e-bb12-c5be2d83c077","parentInvokeId":"7dd4e056-4b6c-444f-b83f-0b66bd8e29cd","childInvokesId":null,"status":"finish","onInvokeData":null,"invokeType":"llm","name":"google/gemma-4-e4b","elapsedTime":"15.97s","metaData":{"type":"llm","class_name":"google/gemma-4-e4b"}},"durationMs":15971,"usage":{"code":0,"prompt":"","err_msg":"","task_id":"","model_name":"google/gemma-4-e4b","total_latency":0.0,"first_token_time":"","request_start_time":"","input_tokens":173,"output_tokens":191,"total_tokens":364,"cache_tokens":0},"usageStatus":"reported_by_sdk"}}
2026-10-27 09:05:49.888 INFO  sample.call_trace - TRACE {"schemaVersion":1,"timestamp":"2026-10-27T01:05:49.888843Z","event":"TRACE","runId":"13500e4c-328a-471b-9fda-c415287f242c","sessionId":"credit-trace-13500e4c-328a-471b-9fda-c415287f242c","caseId":"credit_c1001","traceId":"2a8ebb48-332a-4703-8a33-cea19b1f3afb","data":{"span":{"traceId":"2a8ebb48-332a-4703-8a33-cea19b1f3afb","startTime":"2026-10-27T09:05:49.857332","endTime":null,"inputs":{"inputs":"{\"customer_id\":\"C1001\"}"},"outputs":null,"error":null,"invokeId":"42e87018-798a-4519-9b21-0eea4f535ee9","parentInvokeId":"7dd4e056-4b6c-444f-b83f-0b66bd8e29cd","childInvokesId":null,"status":"start","onInvokeData":null,"invokeType":"plugin","name":"query_credit_report","elapsedTime":null,"metaData":{"type":"plugin","class_name":"query_credit_report"}},"durationMs":null}}
2026-10-27 09:05:49.889 INFO  sample.call_trace - TRACE {"schemaVersion":1,"timestamp":"2026-10-27T01:05:49.889111Z","event":"TRACE","runId":"13500e4c-328a-471b-9fda-c415287f242c","sessionId":"credit-trace-13500e4c-328a-471b-9fda-c415287f242c","caseId":"credit_c1001","traceId":"2a8ebb48-332a-4703-8a33-cea19b1f3afb","data":{"span":{"traceId":"2a8ebb48-332a-4703-8a33-cea19b1f3afb","startTime":"2026-10-27T09:05:49.857332","endTime":"2026-10-27T09:05:49.861729","inputs":{"inputs":"{\"customer_id\":\"C1001\"}"},"outputs":{"outputs":{"data_source":"演示数据","report_id":"CR-4cda47ab","overdue_months":0,"credit_score":735,"customer_id":"C1001"}},"error":null,"invokeId":"42e87018-798a-4519-9b21-0eea4f535ee9","parentInvokeId":"7dd4e056-4b6c-444f-b83f-0b66bd8e29cd","childInvokesId":null,"status":"finish","onInvokeData":null,"invokeType":"plugin","name":"query_credit_report","elapsedTime":"4ms","metaData":{"type":"plugin","class_name":"query_credit_report"}},"durationMs":4}}
2026-10-27 09:05:57.741 INFO  sample.call_trace - TRACE {"schemaVersion":1,"timestamp":"2026-10-27T01:05:57.725689Z","event":"TRACE","runId":"13500e4c-328a-471b-9fda-c415287f242c","sessionId":"credit-trace-13500e4c-328a-471b-9fda-c415287f242c","caseId":"credit_c1001","traceId":"2a8ebb48-332a-4703-8a33-cea19b1f3afb","data":{"span":{"traceId":"2a8ebb48-332a-4703-8a33-cea19b1f3afb","startTime":"2026-10-27T09:05:49.86512","endTime":null,"inputs":{"messages":[{"role":"system","content":"你是银行贷前审查助手。必须先调用 query_credit_report 查询客户征信，每次只调用一次，获得结果后不要再次调用工具。用中文简短回答，必须原样包含报告编号、客户编号和信用评分。不得编造征信记录，不得作出实际放款承诺。","contentAsString":"你是银行贷前审查助手。必须先调用 query_credit_report 查询客户征信，每次只调用一次，获得结果后不要再次调用工具。用中文简短回答，必须原样包含报告编号、客户编号和信用评分。不得编造征信记录，不得作出实际放款承诺。","contentAsList":[]},{"role":"user","content":"请查询客户 C1001 的征信档案，给出简短的贷前核验摘要，保留报告编号。","contentAsString":"请查询客户 C1001 的征信档案，给出简短的贷前核验摘要，保留报告编号。","contentAsList":[]},{"role":"assistant","content":"","metadata":{},"contentAsString":"","contentAsList":[],"tool_calls":[{"id":"842643577","type":"function","name":"query_credit_report","arguments":"{\"customer_id\":\"C1001\"}","index":0}]},{"role":"tool","content":"{data_source=演示数据, report_id=CR-4cda47ab, overdue_months=0, credit_score=735, customer_id=C1001}","metadata":{},"contentAsString":"{data_source=演示数据, report_id=CR-4cda47ab, overdue_months=0, credit_score=735, customer_id=C1001}","contentAsList":[],"tool_call_id":"842643577"}]},"outputs":null,"error":null,"invokeId":"d8477444-1ea9-4685-970a-ecb65e2150ec","parentInvokeId":"7dd4e056-4b6c-444f-b83f-0b66bd8e29cd","childInvokesId":null,"status":"start","onInvokeData":null,"invokeType":"llm","name":"google/gemma-4-e4b","elapsedTime":null,"metaData":{"type":"llm","class_name":"google/gemma-4-e4b"}},"durationMs":null,"usage":null,"usageStatus":"not_reported_or_zero"}}
2026-10-27 09:05:58.620 INFO  sample.call_trace - TRACE {"schemaVersion":1,"timestamp":"2026-10-27T01:05:58.618388Z","event":"TRACE","runId":"13500e4c-328a-471b-9fda-c415287f242c","sessionId":"credit-trace-13500e4c-328a-471b-9fda-c415287f242c","caseId":"credit_c1001","traceId":"2a8ebb48-332a-4703-8a33-cea19b1f3afb","data":{"span":{"traceId":"2a8ebb48-332a-4703-8a33-cea19b1f3afb","startTime":"2026-10-27T09:05:49.86512","endTime":"2026-10-27T09:05:58.615387","inputs":{"messages":[{"role":"system","content":"你是银行贷前审查助手。必须先调用 query_credit_report 查询客户征信，每次只调用一次，获得结果后不要再次调用工具。用中文简短回答，必须原样包含报告编号、客户编号和信用评分。不得编造征信记录，不得作出实际放款承诺。","contentAsString":"你是银行贷前审查助手。必须先调用 query_credit_report 查询客户征信，每次只调用一次，获得结果后不要再次调用工具。用中文简短回答，必须原样包含报告编号、客户编号和信用评分。不得编造征信记录，不得作出实际放款承诺。","contentAsList":[]},{"role":"user","content":"请查询客户 C1001 的征信档案，给出简短的贷前核验摘要，保留报告编号。","contentAsString":"请查询客户 C1001 的征信档案，给出简短的贷前核验摘要，保留报告编号。","contentAsList":[]},{"role":"assistant","content":"","metadata":{},"contentAsString":"","contentAsList":[],"tool_calls":[{"id":"842643577","type":"function","name":"query_credit_report","arguments":"{\"customer_id\":\"C1001\"}","index":0}]},{"role":"tool","content":"{data_source=演示数据, report_id=CR-4cda47ab, overdue_months=0, credit_score=735, customer_id=C1001}","metadata":{},"contentAsString":"{data_source=演示数据, report_id=CR-4cda47ab, overdue_months=0, credit_score=735, customer_id=C1001}","contentAsList":[],"tool_call_id":"842643577"}]},"outputs":{"outputs":{"role":"assistant","content":"报告编号：CR-4cda47ab，客户编号：C1001，信用评分：735。","metadata":{},"contentAsString":"报告编号：CR-4cda47ab，客户编号：C1001，信用评分：735。","contentAsList":[],"usage_metadata":{"code":0,"prompt":"","err_msg":"","task_id":"","model_name":"google/gemma-4-e4b","total_latency":0.0,"first_token_time":"","request_start_time":"","input_tokens":255,"output_tokens":29,"total_tokens":284,"cache_tokens":0},"finish_reason":"stop","reasoning_content":""},"response_string":"{\"role\":\"assistant\",\"content\":\"报告编号：CR-4cda47ab，客户编号：C1001，信用评分：735。\",\"metadata\":{},\"contentAsString\":\"报告编号：CR-4cda47ab，客户编号：C1001，信用评分：735。\",\"contentAsList\":[],\"usage_metadata\":{\"code\":0,\"prompt\":\"\",\"err_msg\":\"\",\"task_id\":\"\",\"model_name\":\"google/gemma-4-e4b\",\"total_latency\":0.0,\"first_token_time\":\"\",\"request_start_time\":\"\",\"input_tokens\":255,\"output_tokens\":29,\"total_tokens\":284,\"cache_tokens\":0},\"finish_reason\":\"stop\",\"reasoning_content\":\"\"}"},"error":null,"invokeId":"d8477444-1ea9-4685-970a-ecb65e2150ec","parentInvokeId":"7dd4e056-4b6c-444f-b83f-0b66bd8e29cd","childInvokesId":null,"status":"finish","onInvokeData":null,"invokeType":"llm","name":"google/gemma-4-e4b","elapsedTime":"8.75s","metaData":{"type":"llm","class_name":"google/gemma-4-e4b"}},"durationMs":8750,"usage":{"code":0,"prompt":"","err_msg":"","task_id":"","model_name":"google/gemma-4-e4b","total_latency":0.0,"first_token_time":"","request_start_time":"","input_tokens":255,"output_tokens":29,"total_tokens":284,"cache_tokens":0},"usageStatus":"reported_by_sdk"}}
2026-10-27 09:05:58.622 INFO  sample.call_trace - TRACE {"schemaVersion":1,"timestamp":"2026-10-27T01:05:58.621275Z","event":"TRACE","runId":"13500e4c-328a-471b-9fda-c415287f242c","sessionId":"credit-trace-13500e4c-328a-471b-9fda-c415287f242c","caseId":"credit_c1001","traceId":"2a8ebb48-332a-4703-8a33-cea19b1f3afb","data":{"span":{"traceId":"2a8ebb48-332a-4703-8a33-cea19b1f3afb","startTime":"2026-10-27T09:05:33.813141","endTime":"2026-10-27T09:05:58.61809","inputs":{"query":"请查询客户 C1001 的征信档案，给出简短的贷前核验摘要，保留报告编号。"},"outputs":{"outputs":{"output":"报告编号：CR-4cda47ab，客户编号：C1001，信用评分：735。","result_type":"answer"}},"error":null,"invokeId":"7dd4e056-4b6c-444f-b83f-0b66bd8e29cd","parentInvokeId":null,"childInvokesId":["bb8416e4-c97d-424e-bb12-c5be2d83c077","42e87018-798a-4519-9b21-0eea4f535ee9","d8477444-1ea9-4685-970a-ecb65e2150ec"],"status":"finish","onInvokeData":null,"invokeType":"chain","name":"credit_trace_agent","elapsedTime":"24.80s","metaData":{"type":"agent","class_name":"credit_trace_agent"}},"durationMs":24804}}
2026-10-27 09:05:58.622 INFO  sample.call_trace - RESULT {"schemaVersion":1,"timestamp":"2026-10-27T01:05:58.622418Z","event":"RESULT","runId":"13500e4c-328a-471b-9fda-c415287f242c","sessionId":"credit-trace-13500e4c-328a-471b-9fda-c415287f242c","caseId":"credit_c1001","traceId":"2a8ebb48-332a-4703-8a33-cea19b1f3afb","data":{"output":"报告编号：CR-4cda47ab，客户编号：C1001，信用评分：735。","result_type":"answer"}}
```

</details>

## 数据边界

- 框架可输出轨迹和模型服务返回的用量；首 token 时间、服务内部延迟等字段如果为空或零，可能是 SDK 默认值，不视为实际测量结果。本例不编造这些指标，也不把缺失 token 统计解释为免费或零消耗。
- `finish` 表示调用结束；JUnit 通过表示样例契约验收成功，不代表业务答案质量评分。人工反馈、期望答案和评测分数需由业务或评测系统提供，可按 `caseId` / `runId` / `traceId` / `invokeId` 关联。
- 日志记录完整提示和业务输入输出，本例使用演示征信数据；只序列化模型请求配置，不序列化含 API Key 的模型客户端配置。
- 这里的 JSONL 是本样例的日志格式，不是 OTLP 协议。无需部署 Collector；需要标准遥测后端时可使用框架已有的 OtelAgentHandler / exporter。
