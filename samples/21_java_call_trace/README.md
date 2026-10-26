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
python3 verify_logs.py
```

首次执行由 Maven 下载官方 Java SDK 及其传递依赖。模型 provider 固定为 `openai`。使用官方 `ModelRequestConfig.setExtraField("stream_options", Map.of("include_usage", true))` 请求流式用量；没有自行发送模型 HTTP 请求或修改框架。

在仓库根目录执行统一回归（Python 3.11+）：

```bash
python3 run_all_samples.py 21
```

`main.py` 启动 Maven，传递退出码，成功后调用 `verify_logs.py` 验收真实文件。Java 最长等待 300 秒。

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
| `RESULT` / `VALIDATION` / `SUCCESS` | 最终业务结果、现有样例断言结果与通过标记；`VALIDATION.scope=sample_contract`，只代表样例契约验收 |

`CONFIG` 在执行开始前输出，此时 `traceId=null`，通过相同 `runId` 关联后续轨迹。JSONL 每行一个 JSON 对象；可读日志在同一 JSON 前增加时间、级别和事件名，两份文件的事件逐条一致。

## 代码详解与验收

[`CreditCallTrace.java`](src/main/java/CreditCallTrace.java) 是单文件入口：配置官方 ReActAgent 和 LocalFunction，注册官方 OtelRail，消费 `StreamMode.TRACE`，直接序列化官方 span。Jackson 和文件写入仅负责样例输出格式，没有新增模型、Agent、工具或追踪框架封装。

[`logback-credit-trace.xml`](src/main/resources/logback-credit-trace.xml) 控制可读日志格式；Maven exec 插件通过 `logback.configurationFile` 选择它。若用 IDE 或直接运行 Java，请加 JVM 参数 `-Dlogback.configurationFile=logback-credit-trace.xml`，并把工作目录设为本样例目录。两份日志每次运行覆盖；本次实测另保存为下面的版本化文件。

Java 检查一个完成的 Agent 根 span、至少两次 LLM 和一次工具调用、统一链路 ID、正确父子关系、工具真实生成的报告编号回传及日志落盘。Python 继续检查 JSONL 可解析、公共关联字段、实际模型消息和工具数据、用量标记、稳定配置指纹，以及可读日志和 JSONL 完全一致。任一项失败都返回非零。

## 实测输出

2026-10-26，Corretto 17.0.21 / Maven 3.9.9 / SDK `0.1.16`，真实调用本机 LM Studio `google/gemma-4-e4b`。保存文件：

- [`run_output.txt`](run_output.txt)：完整 `call_trace.log`。
- [`run_trace.jsonl`](run_trace.jsonl)：与上述可读日志相同的完整 JSONL 事件。
- [`run_error_trace.jsonl`](run_error_trace.jsonl)：在独立临时目录用不可达的 `127.0.0.1:1` 实测的错误轨迹。

本次调用产生 8 条 TRACE 事件，对应 4 个 span（Agent、LLM、征信工具、LLM）。以下用量来自模型服务返回的数据，由官方 SDK 解析：

| 模型调用 | usageStatus | input_tokens | output_tokens | total_tokens |
|---|---|---|---|---|
| 第 1 次 | reported_by_sdk | 173 | 195 | 368 |
| 第 2 次 | reported_by_sdk | 257 | 30 | 287 |

### call_trace.log 完整输出样例

<details>
<summary>展开完整实际日志，包含模型消息、工具参数与结果、用量和配置指纹</summary>

```text
2026-10-26 19:26:15.341 INFO  sample.call_trace - CONFIG {"schemaVersion":1,"timestamp":"2026-10-26T11:26:15.327202Z","event":"CONFIG","runId":"f0f87b24-a361-43df-bef4-fff8ba7f3924","sessionId":"credit-trace-f0f87b24-a361-43df-bef4-fff8ba7f3924","caseId":"credit_c1001","traceId":null,"data":{"sdkVersion":"0.1.16","modelProvider":"openai","apiBase":"http://127.0.0.1:1234/v1","modelRequestConfig":{"temperature":0.1,"model":"google/gemma-4-e4b","top_p":0.1,"max_tokens":512,"stream_options":{"include_usage":true}},"maxIterations":3,"prompt":"你是银行贷前审查助手。必须先调用 query_credit_report 查询客户征信，每次只调用一次，获得结果后不要再次调用工具。用中文简短回答，必须原样包含报告编号、客户编号和信用评分。不得编造征信记录，不得作出实际放款承诺。","promptSha256":"d8bfd2f80a008cf97cf83688dacdf36731e269c6652ae6f0588c17c3f97bc24e","tools":[{"id":"query_credit_report","name":"query_credit_report","description":"查询客户的演示征信档案","inputParams":{"properties":{"customer_id":{"type":"string","description":"客户编号，例如 C1001"}},"type":"object","required":["customer_id"]},"properties":{}}],"toolSchemaSha256":"1b0d274f405535d9f845b9e0178cdf055c153368f7c80753a4db9fcfee83338a","sampleSourceSha256":"25f84eb9081894a87a6ce0a79fa8db6de8fad2b39e215bcdcf9aa6fde06a3353","javaVersion":"17.0.21","timeZone":"Asia/Shanghai"}}
2026-10-26 19:26:15.471 INFO  sample.call_trace - TRACE {"schemaVersion":1,"timestamp":"2026-10-26T11:26:15.453263Z","event":"TRACE","runId":"f0f87b24-a361-43df-bef4-fff8ba7f3924","sessionId":"credit-trace-f0f87b24-a361-43df-bef4-fff8ba7f3924","caseId":"credit_c1001","traceId":"8a34ef90-4630-4c82-b3ca-4a4e07728b0e","data":{"span":{"traceId":"8a34ef90-4630-4c82-b3ca-4a4e07728b0e","startTime":"2026-10-26T19:26:15.365018","endTime":null,"inputs":{"query":"请查询客户 C1001 的征信档案，给出简短的贷前核验摘要，保留报告编号。"},"outputs":null,"error":null,"invokeId":"7abcf9e8-b0f3-49ac-aa5b-e94329c73726","parentInvokeId":null,"childInvokesId":null,"status":"start","onInvokeData":null,"invokeType":"chain","name":"credit_trace_agent","elapsedTime":null,"metaData":{"type":"agent","class_name":"credit_trace_agent"}},"durationMs":null}}
2026-10-26 19:27:08.120 INFO  sample.call_trace - TRACE {"schemaVersion":1,"timestamp":"2026-10-26T11:27:08.084266Z","event":"TRACE","runId":"f0f87b24-a361-43df-bef4-fff8ba7f3924","sessionId":"credit-trace-f0f87b24-a361-43df-bef4-fff8ba7f3924","caseId":"credit_c1001","traceId":"8a34ef90-4630-4c82-b3ca-4a4e07728b0e","data":{"span":{"traceId":"8a34ef90-4630-4c82-b3ca-4a4e07728b0e","startTime":"2026-10-26T19:26:15.453194","endTime":null,"inputs":{"messages":[{"role":"system","content":"你是银行贷前审查助手。必须先调用 query_credit_report 查询客户征信，每次只调用一次，获得结果后不要再次调用工具。用中文简短回答，必须原样包含报告编号、客户编号和信用评分。不得编造征信记录，不得作出实际放款承诺。","contentAsString":"你是银行贷前审查助手。必须先调用 query_credit_report 查询客户征信，每次只调用一次，获得结果后不要再次调用工具。用中文简短回答，必须原样包含报告编号、客户编号和信用评分。不得编造征信记录，不得作出实际放款承诺。","contentAsList":[]},{"role":"user","content":"请查询客户 C1001 的征信档案，给出简短的贷前核验摘要，保留报告编号。","contentAsString":"请查询客户 C1001 的征信档案，给出简短的贷前核验摘要，保留报告编号。","contentAsList":[]}]},"outputs":null,"error":null,"invokeId":"c500aa1f-767b-425f-9a9a-7f7ca55174d7","parentInvokeId":"7abcf9e8-b0f3-49ac-aa5b-e94329c73726","childInvokesId":null,"status":"start","onInvokeData":null,"invokeType":"llm","name":"google/gemma-4-e4b","elapsedTime":null,"metaData":{"type":"llm","class_name":"google/gemma-4-e4b"}},"durationMs":null,"usage":null,"usageStatus":"not_reported_or_zero"}}
2026-10-26 19:27:19.310 INFO  sample.call_trace - TRACE {"schemaVersion":1,"timestamp":"2026-10-26T11:27:19.288774Z","event":"TRACE","runId":"f0f87b24-a361-43df-bef4-fff8ba7f3924","sessionId":"credit-trace-f0f87b24-a361-43df-bef4-fff8ba7f3924","caseId":"credit_c1001","traceId":"8a34ef90-4630-4c82-b3ca-4a4e07728b0e","data":{"span":{"traceId":"8a34ef90-4630-4c82-b3ca-4a4e07728b0e","startTime":"2026-10-26T19:26:15.453194","endTime":"2026-10-26T19:27:19.272964","inputs":{"messages":[{"role":"system","content":"你是银行贷前审查助手。必须先调用 query_credit_report 查询客户征信，每次只调用一次，获得结果后不要再次调用工具。用中文简短回答，必须原样包含报告编号、客户编号和信用评分。不得编造征信记录，不得作出实际放款承诺。","contentAsString":"你是银行贷前审查助手。必须先调用 query_credit_report 查询客户征信，每次只调用一次，获得结果后不要再次调用工具。用中文简短回答，必须原样包含报告编号、客户编号和信用评分。不得编造征信记录，不得作出实际放款承诺。","contentAsList":[]},{"role":"user","content":"请查询客户 C1001 的征信档案，给出简短的贷前核验摘要，保留报告编号。","contentAsString":"请查询客户 C1001 的征信档案，给出简短的贷前核验摘要，保留报告编号。","contentAsList":[]}]},"outputs":{"outputs":{"role":"assistant","content":"","metadata":{},"contentAsString":"","contentAsList":[],"tool_calls":[{"id":"271699332","type":"function","name":"query_credit_report","arguments":"{\"customer_id\":\"C1001\"}","index":0}],"usage_metadata":{"code":0,"prompt":"","err_msg":"","task_id":"","model_name":"google/gemma-4-e4b","total_latency":0.0,"first_token_time":"","request_start_time":"","input_tokens":173,"output_tokens":195,"total_tokens":368,"cache_tokens":0},"finish_reason":"tool_calls","reasoning_content":"\nThe user wants me to act as a bank pre-loan review assistant.\nI need to query the credit report for customer ID C1001 using the `query_credit_report` tool.\nI must call this tool only once.\nAfter receiving the result, I need to provide a brief pre-loan verification summary in Chinese.\nThe response must strictly include the report number, customer ID, and credit score.\nI must not fabricate any credit records or make actual lending commitments.\n\nPlan:\n1. Call `query_credit_report` with `customer_id=\"C1001\"`.\n2. Wait for the tool output.\n3. Format the final response according to the constraints (brief, Chinese, include report ID, customer ID, credit score, no lending commitment)."},"response_string":"{\"role\":\"assistant\",\"content\":\"\",\"metadata\":{},\"contentAsString\":\"\",\"contentAsList\":[],\"tool_calls\":[{\"id\":\"271699332\",\"type\":\"function\",\"name\":\"query_credit_report\",\"arguments\":\"{\\\"customer_id\\\":\\\"C1001\\\"}\",\"index\":0}],\"usage_metadata\":{\"code\":0,\"prompt\":\"\",\"err_msg\":\"\",\"task_id\":\"\",\"model_name\":\"google/gemma-4-e4b\",\"total_latency\":0.0,\"first_token_time\":\"\",\"request_start_time\":\"\",\"input_tokens\":173,\"output_tokens\":195,\"total_tokens\":368,\"cache_tokens\":0},\"finish_reason\":\"tool_calls\",\"reasoning_content\":\"\\nThe user wants me to act as a bank pre-loan review assistant.\\nI need to query the credit report for customer ID C1001 using the `query_credit_report` tool.\\nI must call this tool only once.\\nAfter receiving the result, I need to provide a brief pre-loan verification summary in Chinese.\\nThe response must strictly include the report number, customer ID, and credit score.\\nI must not fabricate any credit records or make actual lending commitments.\\n\\nPlan:\\n1. Call `query_credit_report` with `customer_id=\\\"C1001\\\"`.\\n2. Wait for the tool output.\\n3. Format the final response according to the constraints (brief, Chinese, include report ID, customer ID, credit score, no lending commitment).\"}"},"error":null,"invokeId":"c500aa1f-767b-425f-9a9a-7f7ca55174d7","parentInvokeId":"7abcf9e8-b0f3-49ac-aa5b-e94329c73726","childInvokesId":null,"status":"finish","onInvokeData":null,"invokeType":"llm","name":"google/gemma-4-e4b","elapsedTime":"63.82s","metaData":{"type":"llm","class_name":"google/gemma-4-e4b"}},"durationMs":63819,"usage":{"code":0,"prompt":"","err_msg":"","task_id":"","model_name":"google/gemma-4-e4b","total_latency":0.0,"first_token_time":"","request_start_time":"","input_tokens":173,"output_tokens":195,"total_tokens":368,"cache_tokens":0},"usageStatus":"reported_by_sdk"}}
2026-10-26 19:27:19.312 INFO  sample.call_trace - TRACE {"schemaVersion":1,"timestamp":"2026-10-26T11:27:19.312022Z","event":"TRACE","runId":"f0f87b24-a361-43df-bef4-fff8ba7f3924","sessionId":"credit-trace-f0f87b24-a361-43df-bef4-fff8ba7f3924","caseId":"credit_c1001","traceId":"8a34ef90-4630-4c82-b3ca-4a4e07728b0e","data":{"span":{"traceId":"8a34ef90-4630-4c82-b3ca-4a4e07728b0e","startTime":"2026-10-26T19:27:19.288626","endTime":null,"inputs":{"inputs":"{\"customer_id\":\"C1001\"}"},"outputs":null,"error":null,"invokeId":"1b133a06-0fd6-405f-b4fc-48233d3d4f68","parentInvokeId":"7abcf9e8-b0f3-49ac-aa5b-e94329c73726","childInvokesId":null,"status":"start","onInvokeData":null,"invokeType":"plugin","name":"query_credit_report","elapsedTime":null,"metaData":{"type":"plugin","class_name":"query_credit_report"}},"durationMs":null}}
2026-10-26 19:27:19.312 INFO  sample.call_trace - TRACE {"schemaVersion":1,"timestamp":"2026-10-26T11:27:19.312337Z","event":"TRACE","runId":"f0f87b24-a361-43df-bef4-fff8ba7f3924","sessionId":"credit-trace-f0f87b24-a361-43df-bef4-fff8ba7f3924","caseId":"credit_c1001","traceId":"8a34ef90-4630-4c82-b3ca-4a4e07728b0e","data":{"span":{"traceId":"8a34ef90-4630-4c82-b3ca-4a4e07728b0e","startTime":"2026-10-26T19:27:19.288626","endTime":"2026-10-26T19:27:19.295498","inputs":{"inputs":"{\"customer_id\":\"C1001\"}"},"outputs":{"outputs":{"overdue_months":0,"report_id":"CR-bc729a53","data_source":"演示数据","customer_id":"C1001","credit_score":735}},"error":null,"invokeId":"1b133a06-0fd6-405f-b4fc-48233d3d4f68","parentInvokeId":"7abcf9e8-b0f3-49ac-aa5b-e94329c73726","childInvokesId":null,"status":"finish","onInvokeData":null,"invokeType":"plugin","name":"query_credit_report","elapsedTime":"6ms","metaData":{"type":"plugin","class_name":"query_credit_report"}},"durationMs":6}}
2026-10-26 19:27:20.404 INFO  sample.call_trace - TRACE {"schemaVersion":1,"timestamp":"2026-10-26T11:27:20.399622Z","event":"TRACE","runId":"f0f87b24-a361-43df-bef4-fff8ba7f3924","sessionId":"credit-trace-f0f87b24-a361-43df-bef4-fff8ba7f3924","caseId":"credit_c1001","traceId":"8a34ef90-4630-4c82-b3ca-4a4e07728b0e","data":{"span":{"traceId":"8a34ef90-4630-4c82-b3ca-4a4e07728b0e","startTime":"2026-10-26T19:27:19.298699","endTime":null,"inputs":{"messages":[{"role":"system","content":"你是银行贷前审查助手。必须先调用 query_credit_report 查询客户征信，每次只调用一次，获得结果后不要再次调用工具。用中文简短回答，必须原样包含报告编号、客户编号和信用评分。不得编造征信记录，不得作出实际放款承诺。","contentAsString":"你是银行贷前审查助手。必须先调用 query_credit_report 查询客户征信，每次只调用一次，获得结果后不要再次调用工具。用中文简短回答，必须原样包含报告编号、客户编号和信用评分。不得编造征信记录，不得作出实际放款承诺。","contentAsList":[]},{"role":"user","content":"请查询客户 C1001 的征信档案，给出简短的贷前核验摘要，保留报告编号。","contentAsString":"请查询客户 C1001 的征信档案，给出简短的贷前核验摘要，保留报告编号。","contentAsList":[]},{"role":"assistant","content":"","metadata":{},"contentAsString":"","contentAsList":[],"tool_calls":[{"id":"271699332","type":"function","name":"query_credit_report","arguments":"{\"customer_id\":\"C1001\"}","index":0}]},{"role":"tool","content":"{overdue_months=0, report_id=CR-bc729a53, data_source=演示数据, customer_id=C1001, credit_score=735}","metadata":{},"contentAsString":"{overdue_months=0, report_id=CR-bc729a53, data_source=演示数据, customer_id=C1001, credit_score=735}","contentAsList":[],"tool_call_id":"271699332"}]},"outputs":null,"error":null,"invokeId":"d14a396e-81b7-42d1-a4cf-1a54f4a74773","parentInvokeId":"7abcf9e8-b0f3-49ac-aa5b-e94329c73726","childInvokesId":null,"status":"start","onInvokeData":null,"invokeType":"llm","name":"google/gemma-4-e4b","elapsedTime":null,"metaData":{"type":"llm","class_name":"google/gemma-4-e4b"}},"durationMs":null,"usage":null,"usageStatus":"not_reported_or_zero"}}
2026-10-26 19:27:21.300 INFO  sample.call_trace - TRACE {"schemaVersion":1,"timestamp":"2026-10-26T11:27:21.299355Z","event":"TRACE","runId":"f0f87b24-a361-43df-bef4-fff8ba7f3924","sessionId":"credit-trace-f0f87b24-a361-43df-bef4-fff8ba7f3924","caseId":"credit_c1001","traceId":"8a34ef90-4630-4c82-b3ca-4a4e07728b0e","data":{"span":{"traceId":"8a34ef90-4630-4c82-b3ca-4a4e07728b0e","startTime":"2026-10-26T19:27:19.298699","endTime":"2026-10-26T19:27:21.297855","inputs":{"messages":[{"role":"system","content":"你是银行贷前审查助手。必须先调用 query_credit_report 查询客户征信，每次只调用一次，获得结果后不要再次调用工具。用中文简短回答，必须原样包含报告编号、客户编号和信用评分。不得编造征信记录，不得作出实际放款承诺。","contentAsString":"你是银行贷前审查助手。必须先调用 query_credit_report 查询客户征信，每次只调用一次，获得结果后不要再次调用工具。用中文简短回答，必须原样包含报告编号、客户编号和信用评分。不得编造征信记录，不得作出实际放款承诺。","contentAsList":[]},{"role":"user","content":"请查询客户 C1001 的征信档案，给出简短的贷前核验摘要，保留报告编号。","contentAsString":"请查询客户 C1001 的征信档案，给出简短的贷前核验摘要，保留报告编号。","contentAsList":[]},{"role":"assistant","content":"","metadata":{},"contentAsString":"","contentAsList":[],"tool_calls":[{"id":"271699332","type":"function","name":"query_credit_report","arguments":"{\"customer_id\":\"C1001\"}","index":0}]},{"role":"tool","content":"{overdue_months=0, report_id=CR-bc729a53, data_source=演示数据, customer_id=C1001, credit_score=735}","metadata":{},"contentAsString":"{overdue_months=0, report_id=CR-bc729a53, data_source=演示数据, customer_id=C1001, credit_score=735}","contentAsList":[],"tool_call_id":"271699332"}]},"outputs":{"outputs":{"role":"assistant","content":"报告编号：CR-bc729a53，客户编号：C1001，信用评分：735。","metadata":{},"contentAsString":"报告编号：CR-bc729a53，客户编号：C1001，信用评分：735。","contentAsList":[],"usage_metadata":{"code":0,"prompt":"","err_msg":"","task_id":"","model_name":"google/gemma-4-e4b","total_latency":0.0,"first_token_time":"","request_start_time":"","input_tokens":257,"output_tokens":30,"total_tokens":287,"cache_tokens":0},"finish_reason":"stop","reasoning_content":""},"response_string":"{\"role\":\"assistant\",\"content\":\"报告编号：CR-bc729a53，客户编号：C1001，信用评分：735。\",\"metadata\":{},\"contentAsString\":\"报告编号：CR-bc729a53，客户编号：C1001，信用评分：735。\",\"contentAsList\":[],\"usage_metadata\":{\"code\":0,\"prompt\":\"\",\"err_msg\":\"\",\"task_id\":\"\",\"model_name\":\"google/gemma-4-e4b\",\"total_latency\":0.0,\"first_token_time\":\"\",\"request_start_time\":\"\",\"input_tokens\":257,\"output_tokens\":30,\"total_tokens\":287,\"cache_tokens\":0},\"finish_reason\":\"stop\",\"reasoning_content\":\"\"}"},"error":null,"invokeId":"d14a396e-81b7-42d1-a4cf-1a54f4a74773","parentInvokeId":"7abcf9e8-b0f3-49ac-aa5b-e94329c73726","childInvokesId":null,"status":"finish","onInvokeData":null,"invokeType":"llm","name":"google/gemma-4-e4b","elapsedTime":"2.00s","metaData":{"type":"llm","class_name":"google/gemma-4-e4b"}},"durationMs":1999,"usage":{"code":0,"prompt":"","err_msg":"","task_id":"","model_name":"google/gemma-4-e4b","total_latency":0.0,"first_token_time":"","request_start_time":"","input_tokens":257,"output_tokens":30,"total_tokens":287,"cache_tokens":0},"usageStatus":"reported_by_sdk"}}
2026-10-26 19:27:21.302 INFO  sample.call_trace - TRACE {"schemaVersion":1,"timestamp":"2026-10-26T11:27:21.302066Z","event":"TRACE","runId":"f0f87b24-a361-43df-bef4-fff8ba7f3924","sessionId":"credit-trace-f0f87b24-a361-43df-bef4-fff8ba7f3924","caseId":"credit_c1001","traceId":"8a34ef90-4630-4c82-b3ca-4a4e07728b0e","data":{"span":{"traceId":"8a34ef90-4630-4c82-b3ca-4a4e07728b0e","startTime":"2026-10-26T19:26:15.365018","endTime":"2026-10-26T19:27:21.29916","inputs":{"query":"请查询客户 C1001 的征信档案，给出简短的贷前核验摘要，保留报告编号。"},"outputs":{"outputs":{"output":"报告编号：CR-bc729a53，客户编号：C1001，信用评分：735。","result_type":"answer"}},"error":null,"invokeId":"7abcf9e8-b0f3-49ac-aa5b-e94329c73726","parentInvokeId":null,"childInvokesId":["c500aa1f-767b-425f-9a9a-7f7ca55174d7","1b133a06-0fd6-405f-b4fc-48233d3d4f68","d14a396e-81b7-42d1-a4cf-1a54f4a74773"],"status":"finish","onInvokeData":null,"invokeType":"chain","name":"credit_trace_agent","elapsedTime":"65.93s","metaData":{"type":"agent","class_name":"credit_trace_agent"}},"durationMs":65934}}
2026-10-26 19:27:21.310 INFO  sample.call_trace - RESULT {"schemaVersion":1,"timestamp":"2026-10-26T11:27:21.310479Z","event":"RESULT","runId":"f0f87b24-a361-43df-bef4-fff8ba7f3924","sessionId":"credit-trace-f0f87b24-a361-43df-bef4-fff8ba7f3924","caseId":"credit_c1001","traceId":"8a34ef90-4630-4c82-b3ca-4a4e07728b0e","data":{"outputs":{"output":"报告编号：CR-bc729a53，客户编号：C1001，信用评分：735。","result_type":"answer"}}}
2026-10-26 19:27:21.313 INFO  sample.call_trace - VALIDATION {"schemaVersion":1,"timestamp":"2026-10-26T11:27:21.311174Z","event":"VALIDATION","runId":"f0f87b24-a361-43df-bef4-fff8ba7f3924","sessionId":"credit-trace-f0f87b24-a361-43df-bef4-fff8ba7f3924","caseId":"credit_c1001","traceId":"8a34ef90-4630-4c82-b3ca-4a4e07728b0e","data":{"checks":["completed_agent_and_model_calls","tool_called_with_customer_id","report_id_preserved","trace_parent_relationships","log_written"],"scope":"sample_contract","passed":true}}
2026-10-26 19:27:21.314 INFO  sample.call_trace - SUCCESS {"schemaVersion":1,"timestamp":"2026-10-26T11:27:21.314001Z","event":"SUCCESS","runId":"f0f87b24-a361-43df-bef4-fff8ba7f3924","sessionId":"credit-trace-f0f87b24-a361-43df-bef4-fff8ba7f3924","caseId":"credit_c1001","traceId":"8a34ef90-4630-4c82-b3ca-4a4e07728b0e","data":{"spanCount":4,"message":"Java 调用链验收通过"}}
```

</details>

### JSONL 中的工具完成事件

下面一条记录从同次运行的 JSONL 原样提取，仅缩进便于阅读：

```json
{
  "schemaVersion": 1,
  "timestamp": "2026-10-26T11:27:19.312337Z",
  "event": "TRACE",
  "runId": "f0f87b24-a361-43df-bef4-fff8ba7f3924",
  "sessionId": "credit-trace-f0f87b24-a361-43df-bef4-fff8ba7f3924",
  "caseId": "credit_c1001",
  "traceId": "8a34ef90-4630-4c82-b3ca-4a4e07728b0e",
  "data": {
    "span": {
      "traceId": "8a34ef90-4630-4c82-b3ca-4a4e07728b0e",
      "startTime": "2026-10-26T19:27:19.288626",
      "endTime": "2026-10-26T19:27:19.295498",
      "inputs": {
        "inputs": "{\"customer_id\":\"C1001\"}"
      },
      "outputs": {
        "outputs": {
          "overdue_months": 0,
          "report_id": "CR-bc729a53",
          "data_source": "演示数据",
          "customer_id": "C1001",
          "credit_score": 735
        }
      },
      "error": null,
      "invokeId": "1b133a06-0fd6-405f-b4fc-48233d3d4f68",
      "parentInvokeId": "7abcf9e8-b0f3-49ac-aa5b-e94329c73726",
      "childInvokesId": null,
      "status": "finish",
      "onInvokeData": null,
      "invokeType": "plugin",
      "name": "query_credit_report",
      "elapsedTime": "6ms",
      "metaData": {
        "type": "plugin",
        "class_name": "query_credit_report"
      }
    },
    "durationMs": 6
  }
}
```

统一回归实测：

```text
[PASS] 21_java_call_trace           (83.0s)

===== 汇总: 1/1 通过 =====
```

负向实测：模型服务不可达时，官方客户端尝试 3 次，模型及 Agent 根 span 均为 `status=error`，日志记录实际 error message 和 RUN_ERROR 堆栈，Maven 退出码 1，未打印 SUCCESS。错误文件同样通过 `verify_logs.py --expect-error` 验收。

## 数据边界

- 框架可输出轨迹和模型服务返回的用量；首 token 时间、服务内部延迟等字段如果为空或零，可能是 SDK 默认值，不视为实际测量结果。本例不编造这些指标，也不把缺失 token 统计解释为免费或零消耗。
- `finish` 表示调用结束；`VALIDATION` 和 `SUCCESS` 只代表样例断言通过，均不代表业务答案质量评分。人工反馈、期望答案和评测分数需由业务或评测系统提供，可按 `caseId` / `runId` / `traceId` / `invokeId` 关联。
- 日志记录完整提示和业务输入输出，本例使用演示征信数据；只序列化模型请求配置，不序列化含 API Key 的模型客户端配置。
- 这里的 JSONL 是本样例的日志格式，不是 OTLP 协议。无需部署 Collector；需要标准遥测后端时可使用框架已有的 OtelAgentHandler / exporter。
