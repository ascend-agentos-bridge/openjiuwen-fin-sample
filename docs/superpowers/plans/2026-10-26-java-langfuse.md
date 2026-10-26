# Java Langfuse sample 22

用户要求：新增独立 Langfuse 对接样例。用户已选择先完成 sample 与本地导出验证，不要求本轮连接真实 Langfuse 实例。

方案：官方 ReActAgent / LocalFunction / OtelRail 产生征信查询轨迹；官方 OtelAgentHandler 转为 OTel span；官方 OTLP/HTTP exporter 发送到 Langfuse `/api/public/otel/v1/traces`，用项目 public/secret key 的 Basic Auth，携带 v4 ingestion header。官方 AgentRail 的 after 回调只给当前官方 OTel span 增补 Langfuse 展示属性，不封装或替代框架调用。

为明确判断上传失败，使用 OTel 官方 InMemorySpanExporter 收集本次短任务的 span，任务结束后显式调用官方 OTLP exporter.export 并检查结果；避免将业务成功误报为上传成功。不修改 Agent Core，不写私有 SDK。

- [x] 本地 OTLP 验收先因缺少实际收包文件失败；验证脚本使用官方 OTLP protobuf 定义。
- [x] 新建单文件 Java、独立 Maven/Logback 配置、Python 启动入口和本地 OTLP 收包验收。
- [x] 本机 LM Studio 真实执行，校验 4 个 span、父子 ID、Langfuse 输入输出/类型/会话字段、模型用量、HTTP 路径/认证/v4 header。
- [x] 验证接收端拒绝请求时非零退出，不能打印成功；README 区分本地收包实测与 Langfuse 平台接收未验证。
- [x] 补真实运行输出、README 环境变量/运行说明/官方依据及根索引。

范围限定：本地 OTLP 接收器仅用于验证遥测协议，不模拟 LLM，不冒充 Langfuse。无 Langfuse 配置时普通运行失败并给配置说明；本地验收模式显式开启。

执行记录：普通入口缺密钥时非零退出；本地收包验收先因缺少实际记录失败。首次同步 LM Studio 请求超过默认 60 秒，使用官方 ModelClientConfig.timeout=180 后真实回归 1/1 PASS（40.1 秒）。接收器解析到 4 个成功 span（generation/tool/generation/agent），token 总量 349 和 297，路径、Basic Auth、v4 header、属性、父子关系及报告回传均通过。额外 HTTP 401 导出负向测试通过：模型完成但 Java 因导出失败非零退出，无 SUCCESS。未连接真实 Langfuse 实例，文档明确区分本地协议验证与平台入库。

独立只读审查完成，未发现实质问题；官方 API、Langfuse 认证和属性、失败传播及实测边界说明一致。
