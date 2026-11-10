# AgentHint benchmark 报告

结论：**INVALID — 实验无效：存在运行或业务校验失败**

不能据此确认 AgentHint 实际执行或归因于 AgentHint；外部附件不代表程序已验证真实性。

规则版本：1.0；变化阈值：5.0%；重复同向比例至少80%，不是统计显著性检验。

|组别|负载|并发|轮次类型|计划/调用成功/业务通过|P50 ms|P95 ms|P99 ms|有效轮/s|
|---|---|---:|---|---|---:|---:|---:|---:|
|baseline|continuous|1|all|2/0/0|N/A|N/A|N/A|0.000|
|baseline|continuous|1|first|1/0/0|N/A|N/A|N/A|N/A|
|baseline|continuous|1|subsequent|1/0/0|N/A|N/A|N/A|N/A|

## 分组结论与重复波动

```json
[ ]
```

## 阅读方法

先核对 success/correct 与 errors，再看配置和缓存隔离，最后比较同负载/并发/轮次类型的延迟和波动。恢复首轮须单独解读，不能混入普通轮次。延迟降低率=(off-on)/off，吞吐增加率=(on-off)/off；正数改善。失败样本不计延迟分位数，但保留在成功率分母。

业务轮耗时包含客户端、网络、模型与工具，不含设定等待；首正文不是引擎 TTFT。有效吞吐=业务通过轮数/完整批次窗口，包含等待与清理；闭环 worker 并发不是固定到达率。P95少于20样本、P99少于100样本均为N/A，达到门槛仍不保证估计稳定。

## 可观测边界

requests.jsonl: 逐轮耗时、首正文、真实回答、调用成功和事实校验。model-calls.jsonl: 官方callback的模型调用耗时、最终累计usage（不逐chunk累加）。actions.jsonl: 显式suspend/release的返回和耗时。sdk.log: SDK的Hint构造和警告，构造不等于HTTP完成或服务端执行。自动prefetch精确执行量为unknown。

usageCoverage见summary.json；只有全部业务成功轮的usage都可信时才提供整组token总数。SDK缺失数值可归零，流式cached_tokens未解析，因此缓存token/比例为N/A；不能用字符数代替token。

KV实际迁移量、缓存命中、排队/prefill/decode、NPU/HBM需兼容服务日志、引擎/metrics和设备exporter取证。外部证据只标附件未验证；本报告不会自动证明AgentHint执行。

## 环境与错误

完整参数、业务提示摘要、SDK/JDK/模型及缓存声明见run-manifest.json；逐重复墙钟分母见batches.json。

```json
[ "批次失败，停止后续实验" ]
```
