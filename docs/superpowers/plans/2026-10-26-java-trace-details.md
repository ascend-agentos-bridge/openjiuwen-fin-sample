# 完善 Java sample 调用链日志

用户要求：仅在现有框架 API 支持、不修改框架的前提下完善样例日志，供后续分析和优化反馈。

选择：保留官方 ReActAgent / LocalFunction / OtelRail；直接序列化官方 TraceAgentSpan（含 inputs、outputs、error、metaData、时间）及模型响应。可读日志与 JSONL 保存相同事件；只在样例 Java 文件中做输出格式化，不新增框架封装或模型适配层。

- [x] 先写独立的日志验收脚本，以已有真实日志观测缺少结构化轨迹、调用内容和配置指纹的失败。
- [x] 输出运行配置、提示词/工具 schema/样例源码 SHA-256、完整 span、模型 usage、结束原因、最终结果、样例断言结果和运行失败详情；每条事件带 runId、sessionId、caseId 和 traceId。
- [x] 用本地 LM Studio 真实运行，验收模型消息、工具参数和结果、报告编号回传及 JSONL/可读日志一致性。缺失数据不伪造；全零 SDK 用量注明无法区分未返回和零值。
- [x] 在独立临时目录验证模型不可达时的 error span 与非零退出；更新 README 和真实输出文件。

边界：不把框架 finish 或样例断言通过当作业务质量评分；人工反馈和期望答案仍由业务系统/评测提供。不重复运行未修改的 01–20。

验收记录：新增日志验收脚本先因缺少 JSONL 失败；工具 schema 指纹断言先发现键顺序不稳定并失败，随后使用官方工具 Card 转 Map 并按键排序计算 SHA-256。最终 LM Studio 真实回归 1/1 PASS（83.0 秒），12 条事件，4 个 span；两次 token 用量分别 173/195/368 与 257/30/287。可读日志、JSONL、源码指纹和 README 完整日志均一致。无服务端口负向运行 Maven 退出码 1，10 条事件，真实 error span 与 RUN_ERROR 堆栈；负向日志验收通过。SDK 0.1.16 与框架源码未修改。
