# 22 · Java Agent Core 对接 Langfuse（本地 LM Studio）

**有直接对接方案，无需修改框架：** 官方 `ReActAgent → OtelRail → OtelAgentHandler → OTLP/HTTP exporter → Langfuse`。本例另用官方 AgentRail 的 after 回调设置 Langfuse 的 agent/generation/tool 类型、输入输出和会话字段；模型与工具调用、span 创建和结束仍由官方框架执行。

## 场景和版本

银行贷前核验助手查询客户 C1001 的演示征信档案，工具生成本次独有的报告编号，模型返回摘要。调用真实本地 LM Studio；征信档案明确标注为演示数据。

- Java SDK：`com.openjiuwen:agent-core-java:0.1.16`，本次核对的[最新 release](https://github.com/openJiuwen-ai/agent-core-java/releases/tag/v0.1.16)。
- OpenTelemetry：`1.64.0`，与该 SDK 的依赖一致；额外使用官方 `opentelemetry-sdk-testing` 中的 `InMemorySpanExporter`，收集本次短任务的 4 个 span 后显式导出，便于检查上传结果。
- Langfuse：采用官方 [OpenTelemetry 接入方案](https://langfuse.com/integrations/native/opentelemetry)，适用于 Cloud 或自托管实例；自托管 OTLP 接口至少要求 v3.22.0，当前 v4 接入头为 `x-langfuse-ingestion-version: 4`。
- 代码依据：官方 [ReAct 示例](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/examples/react_agent/ReActWeatherAgentExample.java)、[LocalFunction 示例](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/examples/react_agent/ToolErrorTerminalStateExample.java)、[OtelAgentHandler](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/core/src/main/java/com/openjiuwen/extensions/tracerotel/OtelAgentHandler.java)、[OtelTracerSetup](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/core/src/main/java/com/openjiuwen/extensions/tracerotel/OtelTracerSetup.java)、[AgentRail](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/core/src/main/java/com/openjiuwen/core/singleagent/rail/AgentRail.java)。

## 运行方式：连接 Langfuse

前提：JDK 17+、Maven 3.9+；LM Studio 已启动并加载支持工具调用的模型。Python 入口需要 Python 3.11+。在 Langfuse 项目设置中取得该项目 Public Key / Secret Key，无需安装 Langfuse Java SDK，也不依赖样例 21。

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `API_BASE` | `http://127.0.0.1:1234/v1` | 真实模型的 OpenAI 兼容接口 |
| `API_KEY` | `lm-studio` | 模型服务密钥 |
| `MODEL_NAME` | `google/gemma-4-e4b` | LM Studio 模型标识 |
| `MODEL_TIMEOUT_SECONDS` | `180` | 官方模型客户端超时；本地同步推理可能超过默认 60 秒 |
| `LANGFUSE_BASE_URL` | `http://localhost:3000` | Langfuse 根地址，不含 `/api/public/otel`；Cloud 按项目所在区域填写 |
| `LANGFUSE_PUBLIC_KEY` | 必填 | Langfuse 项目 Public Key |
| `LANGFUSE_SECRET_KEY` | 必填 | Langfuse 项目 Secret Key |
| `JAVA_HOME` / `MAVEN_CMD` | 系统 JDK / `mvn` | JDK 17+；MAVEN_CMD 仅 Python 入口使用 |
| `LANGFUSE_LOCAL_TEST` | 不启用 | 仅 Python 入口识别，设为 `1` 明确开启本地协议验收 |

例如，在仓库根目录执行：

```bash
export LANGFUSE_BASE_URL="https://cloud.langfuse.com"
export LANGFUSE_PUBLIC_KEY="你的项目 Public Key"
export LANGFUSE_SECRET_KEY="你的项目 Secret Key"
cd samples/22_java_langfuse
mvn -q compile exec:java
```

程序使用 `Basic base64(public_key:secret_key)` 认证；官方 HTTP exporter 自动补齐 `/v1/traces`，最终地址为 `<LANGFUSE_BASE_URL>/api/public/otel/v1/traces`。这里使用 Langfuse 支持的 OTLP/HTTP，不使用 gRPC。密钥和 Authorization 头不打印。

也可在仓库根目录运行 `python3 run_all_samples.py 22`。没有项目密钥时立即返回非零，不调用模型，也不会自动退回本地测试。

## 没有 Langfuse 实例：本地 OTLP 导出验证

按本次用户选择，本轮使用本地协议接收器验收。它只解析真实 exporter 发出的 OTLP/protobuf，不是 Langfuse 服务，不响应模型请求；模型始终由官方 Java SDK 调用真实 LM Studio。

```bash
python3 -m pip install -r samples/22_java_langfuse/requirements-local.txt
python3 samples/22_java_langfuse/main.py --local-test
```

统一回归与拒绝上传测试分别为：

```bash
LANGFUSE_LOCAL_TEST=1 python3 run_all_samples.py 22
python3 samples/22_java_langfuse/main.py --local-test --reject-export
```

接收器绑定随机本机端口，临时注入专用本地测试密钥，结束后关闭；解码结果写入 `logs/local_otlp_capture.json`，只保存认证是否匹配，不保存认证值。拒绝模式真实执行模型和工具，再让接收器返回 HTTP 401，要求 Java 非零退出且无 SUCCESS；入口的 NEGATIVE_PASS 表示这个负向测试通过。

## 代码详解与数据映射

[`CreditLangfuse.java`](src/main/java/CreditLangfuse.java) 注册官方 OtelAgentHandler 和 OtelRail；默认优先级 50 的 AgentRail 回调在优先级 0 的 OtelRail 关闭 span 前设置 Langfuse 属性。没有封装或替代 Agent、模型、工具或 tracer API。

| 数据 | Langfuse/OTel 属性 |
|---|---|
| Agent / 模型 / 工具类型 | `langfuse.observation.type=agent/generation/tool` |
| 每一步的输入输出 | `langfuse.observation.input/output`，JSON 字符串 |
| 会话和链路名 | 每个 span 都设置 `langfuse.session.id`、`langfuse.trace.name` |
| 案例、样例版本、环境 | `langfuse.trace.metadata.case_id`、`langfuse.version`、`langfuse.environment` |
| 模型参数 | `langfuse.observation.model.parameters` |
| 模型和 token 用量 | 官方 handler 的 `gen_ai.request.model`、`gen_ai.usage.prompt_tokens/completion_tokens/total_tokens` |
| 框架原始链路和调用 ID | 官方 handler 的 `openjiuwen.trace.id`、`openjiuwen.invoke_id` 等 |

这些属性按 Langfuse 官方映射设置，便于按其数据模型展示输入输出和步骤类型。原生 OTel trace ID 是 32 位十六进制，框架自身的 UUID 保存在 `openjiuwen.trace.id`；在 Langfuse 中查找本次调用时使用终端的 `OTEL_TRACE_ID`。

本例是短任务，使用官方内存 exporter 收集有界 span，再显式调用官方 OTLP exporter 并检查返回结果，上传失败或超时返回非零。Java SUCCESS 只表示 OTLP 接收端确认了请求，不等于已经核实 Langfuse 平台入库或 UI 展示。

[`verify_capture.py`](verify_capture.py) 检查实际收包：路径、认证、v4 header、protobuf 类型、4 个 span、父子关系、Langfuse 属性、模型用量以及报告编号从工具到最终回答的回传。

## 实测输出

2026-10-26 在 Python 3.12.14 / Corretto 17.0.21 / Maven 3.9.9 上，调用本地 LM Studio `google/gemma-4-e4b`，并由本地接收器解析官方 exporter 实际发送的 OTLP/protobuf。**以下是本地导出实测，不是 Langfuse 平台接收证明。**

完整 Java 输出见 [`run_output.txt`](run_output.txt)，实际收包解码见 [`otlp_capture.json`](otlp_capture.json)，HTTP 401 的实际失败输出见 [`rejected_output.txt`](rejected_output.txt)。收包文件是便于查看的解码记录，不是 OTLP 原始 JSON 协议格式；认证只保留是否匹配。

```text
CONFIG: sdk=0.1.16 model=google/gemma-4-e4b endpoint=http://127.0.0.1:62496/api/public/otel/v1/traces
RESULT: {"output":"客户 C1001 的征信报告编号为 CR-425b83e1，客户编号为 C1001，评分是 735。请注意，本数据为演示档案，不构成实际放款承诺。","result_type":"answer"}
OTEL_TRACE_ID: 0b26109adf0a1963a61125122a8bb277 spans=4
SUCCESS: OTLP/HTTP 接收端已确认收到 4 个 span；traceId=0b26109adf0a1963a61125122a8bb277
```

本地收包验收结果（来自实际 protobuf 数据）：

```json
{
  "traceId": "0b26109adf0a1963a61125122a8bb277",
  "spanCount": 4,
  "types": [
    "generation",
    "tool",
    "generation",
    "agent"
  ],
  "totalTokens": [
    349,
    297
  ],
  "reportId": "CR-425b83e1"
}
```

已验证路径 `/api/public/otel/v1/traces`、Basic Auth、`x-langfuse-ingestion-version=4`、protobuf 类型、统一 trace ID、父子 span、Langfuse 展示属性、两次模型用量和报告编号回传。统一回归：

```text
[PASS] 22_java_langfuse             (40.1s)

===== 汇总: 1/1 通过 =====
```

负向实测：接收器对真实导出请求返回 HTTP 401，Java 退出码非零，未打印 SUCCESS，测试入口打印 NEGATIVE_PASS。缺失项目密钥的普通入口也已验证为立即非零退出。


## 注意点

- 本轮没有连接真实 Langfuse 实例，平台入库、UI 展示及费用计算尚未实测。
- 本例上传完整演示输入输出，关闭了官方 handler 的内容哈希，并在回调中设置明文展示字段；只改变 handler 的脱敏开关不会自动处理这些展示字段。
- 未返回的用量或服务内部时间不作估算。SUCCESS 是执行和上传验收，业务质量分、人工反馈和评测数据集需另行接入。
- `logs/run.log`、`logs/rejected_run.log` 和收包文件每次覆盖。Python 本地模式依赖官方 protobuf 定义，普通 Java 对接不需要这些 Python 依赖。
