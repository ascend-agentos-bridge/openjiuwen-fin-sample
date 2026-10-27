# Java 昇腾亲和样例与 benchmark

用户已批准实现，并明确当前没有昇腾环境：先用真实普通模型跑通，benchmark 必须生成含结论的测试报告。

## 方案

新增最小样例 `samples/24_java_ascend_affinity`，使用官方 ReActAgent、ContextEngineConfig、MicroCompactProcessor 和 LocalFunction；通过环境变量显式切换 OpenAI 普通模式与 InferenceAffinity 模式。普通模式不请求亲和释放。

新增独立 Maven 工程 `benchmarks/java_ascend_affinity`，提供固定模型请求及多轮工具 Agent 两类负载。默认 normal，仅运行普通调用；compare 使用同一个 InferenceAffinity provider 对比开关，按重复轮次交替顺序，不自动伪装可用亲和环境。

## 统计与报告

记录逐请求 JSONL、分组 CSV、配置快照和 Markdown 报告。统计成功/失败、正确率、端到端延迟、首个有效内容延迟、吞吐、模型/工具调用及有效 token 用量。延迟统计只包含成功请求，同时报告失败率。流式片段不是 token；SDK 默认零值不可视作字段真实返回，无法确认时标 unavailable/ambiguous。预热不计入正式统计。

报告必须给出真实调用是否跑通、业务内容校验是否通过、性能差异是否可比、亲和是否已验证的独立结论。normal 永不产生昇腾加速结论；compare 仅报告客户端观察，服务端机制证据需从部署方监控取得。无服务端采集时一律标未验证。样本不足时不宣称稳定尾延迟或统计显著提升。

客户端指标与服务端指标分两部分，列明引擎 /metrics、Prometheus/Grafana、affinity 插件日志及 NPU 监控获取位置，不编造指标名。请求失败也尽量写报告，并以非零状态退出。配置错误在请求前失败。

## 约束

- Java 17、Maven、官方 com.openjiuwen:agent-core-java:0.1.16（2026-10-27 已查询 GitHub latest release）。
- 直接使用官方 API；不增加模型/Agent 包装层、不 mock 模型。
- 真实调用验收；本次无昇腾性能验收。
- compare 使用同一客户端与同一负载；组间缓存隔离/清理由运行方确认，未确认则不作因果结论。
- 本地信贷工具数据为固定演示数据，不是 LLM 响应替身。

## 验收

单元测试覆盖分位数、吞吐分母、缺失用量、失败报告、零基线、CLI 校验、普通/亲和配置。真实集成验收分别运行样例和 benchmark 两类负载，保存输出与生成报告。使用官方 0.1.16 examples/react_agent 和 examples/kv-cache-demo 核对 API。
