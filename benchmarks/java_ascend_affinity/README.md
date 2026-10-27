# Java Agent Core：算力亲和 benchmark

依赖 **Java 17+、Maven 3.9+、`com.openjiuwen:agent-core-java:0.1.16`**，模型必须真实可用；Agent 负载要求模型支持工具调用。开关使用说明见 [样例 24](../../samples/24_java_ascend_affinity/README.md)。

## 运行与报告

默认使用普通 OpenAI 兼容模型，不需要昇腾环境：

```bash
cd benchmarks/java_ascend_affinity
export API_BASE="http://127.0.0.1:1234/v1"
export API_KEY="lm-studio"
export MODEL_NAME="google/gemma-4-e4b"
mvn -q compile exec:java -Dexec.args="--mode normal --workload both --concurrency 1 --sessions 2 --rounds 3 --repeats 3 --warmup 1"
```

运行结束打印报告绝对路径。默认输出到新的 `reports/run-时间/`，也可用 `--output /absolute/new-directory` 指定；已存在的目录会拒绝覆盖。

| 文件 | 内容 |
|---|---|
| `report.md` | 自动生成的结论、性能表、可采集指标、服务端指标获取位置与解释范围 |
| `requests.jsonl` | 逐轮耗时、首内容、用量、调用数、真实回答、错误；预热标 warmup=true |
| `summary.csv` / `summary.json` | 按组别、负载和并发汇总，缺失值为 N/A/null |
| `batches.json` | 每个重复的窗口时长及请求，便于复算与查看波动 |
| `run-manifest.json` | SDK、模型、参数、工作负载摘要、预热、缓存隔离声明及验证范围；不写 API_KEY |

**结论规则：**所有正式请求成功且通过事实校验、预热无失败，测试才通过；调用失败、内容校验失败或运行错误会生成失败报告，并非零退出。超时或 worker 清理异常保留已完成结果，未完成/未开始轮次标 `NOT_COMPLETED`，耗时和调用数填 null；中止后的批次不计入统计。normal 只建立普通调用基线。compare 的差异是客户端观察；报告始终独立标注本次是否取得服务端证据，不根据耗时下降宣称昇腾加速已生效。

## 工作负载与参数

`model` 直接调用官方 `Model.stream`：每轮发送固定信贷背景，不将生成结果加入下一轮，适合观察同一固定请求的变化。`agent` 使用官方多轮 `ReActAgent`、本地演示征信工具和 `MicroCompactProcessor`，每轮应调用一次工具；同一会话保留真实历史，旧工具结果由官方处理器压缩。两类结果分别统计。

| 参数 | 默认值 | 作用 |
|---|---|---|
| `--mode` | `normal` | `normal` 普通调用；`compare` 亲和 off/on |
| `--workload` | `both` | `model`、`agent` 或两者 |
| `--concurrency` | `1` | 逗号分隔并发档位，如 `1,4,8,16` |
| `--sessions` | `2` | 每个组/负载/并发/重复的总会话数；必须不小于最大并发，否则启动前报错 |
| `--rounds` | `3` | 每个正式会话的轮数 |
| `--repeats` | `3` | 重复实验数，compare 按 off/on、on/off 交替 |
| `--warmup` | `1` | 每个预热会话的轮数；独立预热会话不进入汇总 |
| `--payload-chars` | `2048` | 演示明细字符数；是字符数，不是假定的 token 数 |
| `--max-tokens` | `512` | 每次模型调用输出预算，思考 token 也可能占用预算 |
| `--timeout-seconds` | `60` | 官方客户端请求超时配置 |
| `--batch-timeout-seconds` | `300` | 整个批次（含全部会话）的硬截止时间；超时保留已完成数据、未完成标失败并出报告 |
| `--cache-isolation` | `uncontrolled` | `external` 表示运行方自行处理隔离，并非自动验证 |
| `--output` | 新时间目录 | 不覆盖既有报告 |

固定 temperature=0、top_p=1、SDK 重试和 Agent 流式重试为 0；SDK 必要的流式转非流式回退仍可能发生，模型调用数通过官方 callback 记录。默认是闭环并发，不是固定到达率测试；报告不能直接当作线上容量承诺。Agent 正确率检查客户编号/评分及每轮恰好一次工具调用，不评估真实授信质量。

## 亲和 A/B

有兼容亲和服务时运行：

```bash
export API_BASE="http://your-affinity-server:8000" # 根地址，不带 /v1
export API_KEY="your-key"
export MODEL_NAME="your-model"
mvn -q compile exec:java -Dexec.args="--mode compare --concurrency 1,4,8 --sessions 16 --rounds 6 --repeats 3 --warmup 1 --payload-chars 8192 --batch-timeout-seconds 1800"
```

off/on 都使用官方 `InferenceAffinity` 客户端，只有共享/释放开关不同；Agent 的压缩策略和负载参数相同。off 并不等价于关闭服务端所有 Prefix Cache 优化。固定模型/权重、推理参数、引擎/插件版本、NPU 资源和普通缓存配置后再比较。

benchmark 不重启服务或清空服务器缓存。更换会话 ID 不保证清空全部缓存。冷缓存与热缓存应由部署方分别组织实验；需要清理/重启时可在外部流程安排。`--cache-isolation external` 仅记录运行方声明，不使报告自动获得亲和生效结论。没有插件时可能失败或参数被忽略，不能用普通网关的 200 代替服务端机制证据。

报告中延迟降低率为 `(off-on)/off`，吞吐增加率为 `(on-off)/off`；正数为改善，负数为退化，基线为零或缺失则 N/A。记录每个重复的 P50 差异范围，不宣称统计显著性。

## 客户端可采集统计

| 指标 | 来源及口径 |
|---|---|
| 请求/会话轮耗时 | 单调时钟；成功请求均值、P50、P95、P99，失败另计 |
| 首个有效内容延迟 | 首个非空正文片段；Agent 可能在工具后才输出，不能与引擎 TTFT 混用 |
| 成功率/业务规则通过率 | 分别除以全部正式请求数，错误和真实回答保存在 JSONL |
| 成功请求吞吐 | 成功轮数 ÷ 批次墙钟窗口之和，包含会话执行/清理 |
| 模型/工具调用及模型耗时 | 官方 Agent callback；固定模型负载在直接调用边界计时 |
| 输入/输出 token、输出 token/s | SDK 暴露且输入/输出均有效时采集；成功样本覆盖不全则汇总 N/A |
| 缓存 token 及占比 | 只有可信返回计数时统计，比例为缓存 token 总和/输入 token 总和 |

SDK 0.1.16 的客户端解析会把缺失 usage 字段归零，缓存零值不能区分缺失或零命中，因此记为 ambiguous/null，不能报零命中率。每次模型调用只取最后一个有效累计 usage，不按流式片段累加；一轮 Agent 的多次模型调用才做求和。`completion_tokens` 可能包含思考 token。没有返回的时延/缓存指标不猜测、不用字符串长度代替 token。

P95 至少需要 20 个成功样本，P99 至少 100 个；少于这些数量报告 N/A。即使达到数量，仍需看重复波动与真实生产负载。流式片段不等于 token，因此没有把片段间隔包装为 ITL/TPOT。

## 服务端指标与获取位置

| 指标 | 获取位置 |
|---|---|
| KV/Prefix Cache 命中查询量、命中量 | 推理引擎 `/metrics`，接 Prometheus/Grafana，按窗口 counter 增量 |
| KV 占用/峰值/空闲块 | 引擎缓存监控 |
| 释放次数、失败、实际释放块/字节 | affinity 插件监控或日志；接口 2xx 不代表实际释放量 |
| 排队、Prefill、Decode、TTFT、TPOT | 引擎 histogram 或服务端 trace |
| 运行/等待请求、抢占、重计算 | 引擎调度监控 |
| NPU 利用率、HBM、功耗 | 昇腾设备 exporter、设备监控或 `npu-smi` |
| 预取/卸载/迁移 | 支持该功能的版本及调度器/插件；未提供则不可统计 |

具体名字按部署版本核对，不写死不存在的指标。MindIE Motor 的指标从对应版本 Coordinator Observability `/metrics` 获取。需要独占压测窗口或可过滤测试流量；无法隔离其他业务时，服务端变化只作参考。本次没有服务端环境，报告明确标未采集。

## 验收与实测输出

```bash
mvn test
mvn -Preal-model test
```

离线测试验证统计/配置与缺失字段语义；真实集成测试连接模型运行两个工作负载，并连接不可达端点验证失败报告，不 mock 模型。最终 JUnit 15 项通过（13 项离线，2 项真实调用/失败路径），验收记录见 [test_output.txt](test_output.txt)。

2026-10-27 已在 Mac aarch64 / JDK 17.0.21 / 本机真实 `google/gemma-4-e4b` 上运行：

```bash
mvn -q compile exec:java -Dexec.args="--mode normal --workload both --concurrency 1 --sessions 1 --rounds 3 --repeats 2 --warmup 1 --output reports/2026-10-27-normal"
```

[本次完整测试报告](reports/2026-10-27-normal/report.md) 与 [真实运行输出](run_output.txt) 已保存。12 个正式轮次全部调用成功且通过业务校验，4 个预热轮次不计入统计。

| 负载 | 正式轮次/通过 | 均值 ms | P50 ms | 模型/工具调用 |
|---|---:|---:|---:|---:|
| model | 6/6 | 14111.560 | 14058.779 | 6/0 |
| agent | 6/6 | 14549.719 | 14110.734 | 12/6 |

结论：普通模型请求、多轮工具调用、统计导出和结论报告均已跑通。本次只建立普通调用基线，昇腾亲和生效及提升效果未验证；缓存字段语义不可确认，缓存比例为 N/A；各类负载仅 6 个正式样本，因此 P95/P99 为 N/A。

另用真实不可达端点 `http://127.0.0.1:1/v1` 验证了 [全失败报告](reports/2026-10-27-unreachable/report.md)，Maven CLI 退出码为 1，记录见 [failure_output.txt](failure_output.txt) 与 [退出码](reports/2026-10-27-unreachable/cli-exit-code.txt)。


官方依据：[v0.1.16 KV 示例](https://github.com/openJiuwen-ai/agent-core-java/tree/v0.1.16/examples/kv-cache-demo)、[Model](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/core/src/main/java/com/openjiuwen/core/foundation/llm/Model.java)、[vLLM 指标](https://docs.vllm.ai/en/latest/usage/metrics/)。
