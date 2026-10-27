# 22 · Java Agent Core 对接 Langfuse（本地 LM Studio）

业务入口直接展示官方 `ReActAgent → OtelRail → OtelAgentHandler → BatchSpanProcessor → Langfuse OTLP/HTTP` 的使用方式，无需修改框架。`main` 只配置遥测、调用征信 Agent、输出结果并释放资源；收包、span 数量、字段和报告编号等验收均位于 `src/test/`。

## 场景与版本

银行贷前核验助手查询客户 C1001 的演示征信档案。工具生成报告编号，模型返回摘要。运行时使用真实 LM Studio，征信档案是明确标注的演示数据。

- Java SDK：`com.openjiuwen:agent-core-java:0.1.16`，本次核对的[最新 release](https://github.com/openJiuwen-ai/agent-core-java/releases/tag/v0.1.16)。
- OpenTelemetry：`1.64.0`，与该 SDK 的依赖一致。生产代码使用 `BatchSpanProcessor`；`opentelemetry-sdk-testing` 和内存 exporter 仅在 test scope。
- Langfuse：采用官方 [OpenTelemetry 接入方案](https://langfuse.com/integrations/native/opentelemetry)，适用于 Cloud 或自托管实例；自托管 OTLP 接口至少要求 v3.22.0，当前 v4 接入头为 `x-langfuse-ingestion-version: 4`。
- 代码依据：官方 [ReAct 示例](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/examples/react_agent/ReActWeatherAgentExample.java)、[LocalFunction 示例](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/examples/react_agent/ToolErrorTerminalStateExample.java)、[OtelAgentHandler](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/core/src/main/java/com/openjiuwen/extensions/tracerotel/OtelAgentHandler.java)、[OtelTracerSetup](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/core/src/main/java/com/openjiuwen/extensions/tracerotel/OtelTracerSetup.java)、[AgentRail](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/core/src/main/java/com/openjiuwen/core/singleagent/rail/AgentRail.java)。

## 运行业务示例

前提：JDK 17+、Maven 3.9+；LM Studio 已启动并加载支持工具调用的模型。Python 启动器需要 Python 3.11+。

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `API_BASE` | `http://127.0.0.1:1234/v1` | 模型 OpenAI 兼容地址 |
| `API_KEY` | `lm-studio` | 模型服务密钥 |
| `MODEL_NAME` | `google/gemma-4-e4b` | LM Studio 模型名 |
| `MODEL_TIMEOUT_SECONDS` | `180` | 模型客户端超时，适应本地同步推理 |
| `LANGFUSE_BASE_URL` | `http://localhost:3000` | Langfuse 根地址，不含 `/api/public/otel` |
| `LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY` | 必填 | Langfuse 项目密钥，不写入日志 |
| `JAVA_HOME` / `MAVEN_CMD` | 系统 JDK / `mvn` | JDK 17+；MAVEN_CMD 仅 Python 启动器使用 |

```bash
export LANGFUSE_BASE_URL="https://cloud.langfuse.com"
export LANGFUSE_PUBLIC_KEY="你的项目 Public Key"
export LANGFUSE_SECRET_KEY="你的项目 Secret Key"
cd samples/22_java_langfuse
mvn -q compile exec:java
# 或使用同样只启动业务的 Python 入口
python3 main.py
```

使用 `Basic base64(public_key:secret_key)` 认证和 `x-langfuse-ingestion-version: 4`。官方 HTTP exporter 自动补齐 `/v1/traces`，最终地址为 `<LANGFUSE_BASE_URL>/api/public/otel/v1/traces`。普通业务入口没有本地测试开关，也不打印验收成功标记。

## 代码结构

- [`CreditLangfuse.main`](src/main/java/CreditLangfuse.java)：读取配置、注册官方 handler、调用业务 Agent、输出 `RESULT`、释放资源。
- `tracing` / `tracingConfig`：直接装配官方 OTel provider、BatchSpanProcessor、HTTP exporter 与项目认证。
- `createAgent` / `creditTool`：银行征信业务装配，只使用官方 Agent、工具和模型 API。
- `langfuseLabels`：用官方 AgentRail 生命周期回调给当前 span 设置 Langfuse 展示字段。
- [`src/test/java/CreditLangfuseTest.java`](src/test/java/CreditLangfuseTest.java)：JUnit 单元测试，检查字段映射、批量导出配置、认证及业务结果的错误处理，不调用模型。
- [`src/test/python/`](src/test/python/)：实际模型及 OTLP/protobuf 协议集成测试；本地接收器、断言和测试密钥只存在于测试目录。

遥测由标准 BatchSpanProcessor 异步批量发送，程序结束时 flush/shutdown。生产代码不缓存 span 做数量检查，也不手动拼批导出。上传失败由 SDK 记录，业务调用成功时不会再因为验收断言而失败。模型或业务执行异常仍正常抛出。

## 数据映射

| 数据 | Langfuse/OTel 属性 |
|---|---|
| Agent / 模型 / 工具类型 | `langfuse.observation.type=agent/generation/tool` |
| 每一步的输入输出 | `langfuse.observation.input/output`，JSON 字符串 |
| 会话和链路名 | 每个 span 都设置 `langfuse.session.id`、`langfuse.trace.name` |
| 案例、样例版本、环境 | `langfuse.trace.metadata.case_id`、`langfuse.version`、`langfuse.environment` |
| 模型参数 | `langfuse.observation.model.parameters` |
| 模型和 token 用量 | 官方 handler 的 `gen_ai.request.model`、`gen_ai.usage.prompt_tokens/completion_tokens/total_tokens` |
| 框架原始链路和调用 ID | 官方 handler 的 `openjiuwen.trace.id`、`openjiuwen.invoke_id` 等 |


原生 OTel trace ID 是 32 位十六进制，框架自身的 UUID 保存在 `openjiuwen.trace.id`；终端打印 `OTEL_TRACE_ID`，用于关联业务结果。

## 独立测试

仅运行 JUnit 单元测试（不访问模型）：

```bash
cd samples/22_java_langfuse
mvn -q test
```

在仓库根目录安装测试依赖并运行完整验收：

```bash
python3 -m pip install -r samples/22_java_langfuse/requirements-test.txt
python3 samples/22_java_langfuse/test.py
# 统一回归会自动选择 test.py
python3 run_all_samples.py 22
```

`test.py` 先运行 JUnit，再运行 Python unittest 中的真实模型集成用例。测试使用真实 LM Studio；只为遥测协议启动本地接收器，并临时注入测试项目密钥，不需要真实 Langfuse 实例。接收器汇总批处理器实际发送的多个 protobuf 请求，并在测试中校验路径、认证、v4 header、4 个 span、父子关系、类型、输入输出、会话、模型用量和报告编号回传。

另一个集成用例让遥测接收端返回 HTTP 401，断言业务仍成功并输出实际结果，同时 SDK 记录导出失败。缺失凭据、业务失败、断言失败分别按对应错误报告，业务 main 不承担验收职责。

测试报告位于 `target/surefire-reports/`；真实模型与协议证据保存在 `target/acceptance/`。它们不进入业务入口。

## 重构后实测输出

使用真实 LM Studio `google/gemma-4-e4b`、Corretto 17.0.21、Maven 3.9.9。当前 `main` 由独立集成测试启动，真实 protobuf 收包与业务结果保存为 [otlp_capture.json](otlp_capture.json)、[run_output.txt](run_output.txt)；上传拒绝场景见 [rejected_output.txt](rejected_output.txt)。本轮仍是本地协议验证，没有连接真实 Langfuse 平台。

当前业务入口输出：

```text
OTEL_TRACE_ID: 108f058492e802e2314f8610809456ff
RESULT: {"output":"客户 C1001 的征信报告编号为 CR-a0f7540d，客户编号为 C1001，评分是 735。请注意，本数据为演示档案，不构成实际放款承诺。","result_type":"answer"}
```

独立回归结果：

```text
[PASS] 22_java_langfuse             (62.8s)

===== 汇总: 1/1 通过 =====
```

JUnit **4 个单元测试通过**，Python unittest **2 个真实模型/协议集成测试通过**。正常场景汇总收到 4 个成功 span；HTTP 401 场景下业务依然完成，由 SDK 记录遥测导出失败，测试检查这两个结果。业务 main 不打印验收成功标记。


## 注意点

- 本轮验证真实模型调用和本地 OTLP 协议，仍未连接真实 Langfuse 实例，平台入库和界面展示需配置项目后验证。
- 本例上传完整演示输入输出，关闭了 handler 的内容哈希，并在回调设置明文展示字段；只改 handler 的脱敏开关不会自动处理这些展示字段。
- 框架返回的用量原样保留，不估算未提供的内部时间或费用；业务质量分和人工反馈需要业务或评测系统提供。
