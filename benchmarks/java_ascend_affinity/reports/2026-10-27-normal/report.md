# Java Agent Core benchmark 测试报告

## 结论

**调用及业务校验通过。** 已执行批次的正式计划轮数 12 次，成功 12 次，业务规则通过 12 次。

本次运行 normal 普通模型模式，发起真实模型请求。没有执行亲和开关 A/B，昇腾算力亲和及加速效果**未验证**。性能结论：本次仅建立普通调用基线，无法判断亲和提升。

## 实验配置与解释范围

配置及版本见 [run-manifest.json](run-manifest.json)，逐请求见 [requests.jsonl](requests.jsonl)，汇总见 [summary.csv](summary.csv)，每轮实验原始数据见 [batches.json](batches.json)。

- 固定 temperature=0、相同上下文/工具数据和 max_tokens；输出长度、实际模型及工具次数同时记录。
- 闭环并发：每个会话顺序执行多轮；sessions 是每个组/负载/并发/重复的总会话数。
- 预热不计入正式统计；预热使用独立会话，并非冷缓存实验。本实现不清空服务端缓存。
- A/B 交替顺序；cache-isolation=uncontrolled。external 仅为运行方声明，benchmark 未自动验证隔离；未隔离时不能归因。
- wall_seconds 为各重复的批次墙钟窗口之和，包含执行及会话清理；吞吐不使用各请求耗时之和。
- 延迟仅统计成功请求；失败、超时和内容错误均保留。批次超时保留已完成记录，未完成/未开始轮次用 NOT_COMPLETED 标记并填 null，不伪造耗时或调用次数。中止后的未执行批次不计入统计。
- P95 至少 20 个成功样本，P99 至少 100 个；N/A 为样本不足或指标不可用。
- 分位数使用 nearest-rank；重复范围用于观察波动，不作统计显著性或生产容量结论。
- model 负载每轮发送固定上下文，不将生成结果加入下一轮；agent 负载保留真实多轮历史并压缩旧工具内容。
- 正确率是演示事实校验（C1001/735、Agent 每轮恰好一次工具调用），不是信贷决策质量评估。

## 客户端可采集统计

| 组 | 负载 | 并发 | 请求/成功/正确 | 均值 ms | P50 ms | P95 ms | P99 ms | 首内容 P50 ms | 成功请求/s |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| normal | model | 1 | 6/6/6 | 14111.560 | 14058.779 | N/A | N/A | 14007.516 | 0.071 |
| normal | agent | 1 | 6/6/6 | 14549.719 | 14110.734 | N/A | N/A | 14053.790 | 0.069 |

| 组/负载/并发 | 模型/工具调用 | 模型总耗时/成功轮 ms | 有效 usage/成功轮 | 输入/输出 token | 输出 token/s | 有效缓存样本/成功轮 | 缓存 token 比例 |
|---|---:|---:|---:|---:|---:|---:|---:|
| normal/model/1 | 6/0 | 14111.510 | 6/6 | 9492/1266 | 14.951 | 0/6 | N/A |
| normal/agent/1 | 12/6 | 14540.094 | 6/6 | 30918/778 | 8.912 | 0/6 | N/A |

- model 的首内容是客户端首个非空 content 到达延迟；agent 的首内容可能在工具执行后才到达，不能当作引擎 TTFT。
- SDK 0.1.16 将缺失 usage/cache 字段归零。仅接受正数的输入/输出计数；缓存零值标 ambiguous，不能认定零命中。
- 流式 usage 只在每次模型调用结束取一次有效累计值；一个 Agent 轮次可包含多次模型调用。
- 部分成功请求缺少有效 usage 时，不报告总 token 或 token/s；逐请求仍保留已有值和覆盖率。
- 输出 token 按服务返回的 completion_tokens 口径，可能包含思考 token，不等于可见正文长度。
- 缓存 token 比例仅在全部成功样本有可信缓存计数时计算；它不是设备 KV Cache 命中率。
- 未从片段数量推算 token、ITL/TPOT，也未使用未填充的 SDK totalLatency/firstTokenTime。

## 服务端指标：本次未采集

| 待验证指标 | 获取位置 | 用途 |
|---|---|---|
| Prefix/KV Cache 查询量、命中量 | 推理引擎 /metrics → Prometheus/Grafana | 证明缓存复用；按同一窗口 counter 增量计算 |
| KV Cache 占用、峰值、空闲块 | 引擎缓存监控 | 判断缓存压力和并发容量 |
| 释放请求/成功数、实际释放块/字节 | affinity 插件指标或日志 | 2xx 只能证明受理，释放量需服务端确认 |
| 排队、Prefill、Decode、服务端 TTFT/TPOT | 引擎时延直方图/trace | 定位收益阶段，不能与客户端首内容混用 |
| 运行/等待请求、抢占、重计算 | 引擎调度监控 | 判断高负载稳定性 |
| NPU 利用率、HBM、功耗 | 昇腾设备 exporter/监控平台、npu-smi | 证明实际昇腾设备和资源效率 |
| 预取、卸载、迁移 | 支持该能力的版本及调度器/插件监控 | 无能力或无指标时标不可用 |

服务端指标名按实际引擎/插件版本核对；MindIE Motor 按其版本从 Coordinator Observability /metrics 获取。独占测试窗口或过滤测试流量，counter 重置需处理；非独占且不能过滤时，不作本测试归因。

参考：[官方 KV 示例](https://github.com/openJiuwen-ai/agent-core-java/tree/v0.1.16/examples/kv-cache-demo)、[vLLM 指标](https://docs.vllm.ai/en/latest/usage/metrics/)。
