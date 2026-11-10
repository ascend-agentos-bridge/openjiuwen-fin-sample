# Java AgentHint benchmark：Linux 一键执行与确定性报告

依赖 **Java 17+、Maven 3.9+、Linux Bash 4+、`com.openjiuwen:agent-core-java:0.1.17`**。模型必须真实可用，支持工具调用和 OpenAI 格式流式返回。统计与结论全部由 Java 代码计算，不调用 AI 解读报告；模型仅回答被测业务问题。

已用 DeepSeek 官方 `deepseek-flash` 在 Linux 验证两类基线与并发多轮流程。**当前没有 AgentHint 兼容服务，off/on 加速效果和缓存管理执行尚未验证。**

## Linux 使用指导

先安装并检查 JDK17+ 与 Maven3.9+，确保 `java`、`mvn` 在 PATH。脚本不自动安装系统软件；首次构建需要连接 Maven 仓库，随后请求模型服务。可通过 JAVA_HOME/PATH 指定 JDK；`MAVEN_CMD` 只接受 Maven 可执行文件路径（可为设置企业镜像的脚本），不是带参数的命令字符串。

```bash
java -version
mvn -version
bash --version

# 从仓库根目录运行；密钥只在进程环境，不写入配置或报告
read -r -s -p 'API key: ' API_KEY
export API_KEY
printf '\n'

# 先用普通服务检查业务与报告；不会发送 Hint
bash benchmarks/java_agent_hint/run_benchmark.sh \
  --mode baseline --profile smoke \
  --api-base 'https://api.deepseek.com' --model-id 'deepseek-flash'
```

脚本自动构建可执行 JAR，再运行 benchmark，不需要手工执行 Maven。可从任意工作目录用脚本绝对路径运行；相对的配置文件和显式输出路径相对于调用者目录，默认报告位于工程 `reports/run-时间-随机ID/`。目录已存在会拒绝覆盖。

也可以使用 [benchmark.example.json](benchmark.example.json)：

```bash
bash benchmarks/java_agent_hint/run_benchmark.sh \
  --config benchmarks/java_agent_hint/benchmark.example.json

# 等价的模型环境配置
export API_BASE='https://api.deepseek.com'
export MODEL_NAME='deepseek-flash'
bash benchmarks/java_agent_hint/run_benchmark.sh --mode baseline --profile smoke

bash benchmarks/java_agent_hint/run_benchmark.sh --help
```

配置优先级为 **命令行 > JSON 文件 > 环境变量 > 默认值**。模型地址/ID 没有默认值；密钥只读 `API_KEY`，不接受 `--api-key`。JSON 使用表中相同的连字符字段名，未知字段报错。无鉴权服务未设置 API_KEY 时，SDK 使用无效占位值满足客户端配置要求，服务需允许该请求。

`API_BASE` 后由 SDK 追加 `/chat/completions`；例如服务实际暴露 `/v1/chat/completions` 时填 `http://host:8000/v1`。不要把 DeepSeek 的官方 root 地址惯例套用到所有服务。

## 兼容环境中的一键 off/on

```bash
bash benchmarks/java_agent_hint/run_benchmark.sh \
  --mode compare --profile performance \
  --api-base 'http://compatible-server:8000/v1' --model-id 'your-model-id' \
  --workload both --concurrency 1,4,8 \
  --sessions 8 --rounds 4 --repeats 5 \
  --output '/tmp/agent-hint-comparison-001'
```

compare 两组使用同一 SDK、同一 AscendAffinity provider、端点、模型、工具与业务提示。off 设置 `extensions.kv_cache.mode=none`、Agent 亲和 false，不挂 runtime；on 设置 `affinity`、Agent 亲和 true 并挂官方 KVCacheRuntime。这是在测完整功能配置的差异。off 不等于关闭服务器所有 prefix cache。

固定 temperature=0、top_p=1、相同 max_tokens，关闭可配置模型/流式重试；请求带 `thinking.type=disabled`，服务须接受该扩展（DeepSeek 已验证）。记录实际模型和工具调用数。模型输出不保证逐字相同；每轮校验客户 C1001、735 分、逾期 0 笔，以及恰好一次查档工具。只评估演示任务正确性，不评估授信判断质量。

自动 compare 交替 off→on、on→off，并独立预热。**不会重启引擎或清空缓存**，默认 cache-isolation=uncontrolled，只能给探索性客户端观察。会话 ID 不同不是缓存隔离证明。普通模型可能忽略 Hint 或拒绝扩展，不能用 200 或动作 true 宣称已执行。

## 外部准备缓存、单组运行与合并

冷缓存与热缓存应分别组织。由部署方在每组前准备等价缓存状态，记录引擎版本、时间、资源和清理动作。以下只是命令形式，当前没有兼容环境的实测输出：

```bash
# 每次只跑一个重复；实验ID、重复编号与负载参数两组一致
bash benchmarks/java_agent_hint/run_benchmark.sh \
  --mode compare --group off --experiment-id credit-hint-001 --repeat-index 1 \
  --profile performance --repeats 1 --cache-isolation external \
  --api-base 'http://compatible-server:8000/v1' --model-id 'your-model-id' \
  --output '/tmp/hint-off-r1'

# 部署方此时准备缓存/等价环境，再跑 on
bash benchmarks/java_agent_hint/run_benchmark.sh \
  --mode compare --group on --experiment-id credit-hint-001 --repeat-index 1 \
  --profile performance --repeats 1 --cache-isolation external \
  --api-base 'http://compatible-server:8000/v1' --model-id 'your-model-id' \
  --output '/tmp/hint-on-r1'

bash benchmarks/java_agent_hint/run_benchmark.sh \
  --mode report --inputs '/tmp/hint-off-r1' --inputs '/tmp/hint-on-r1' \
  --output '/tmp/hint-merged'
```

至少组织 5 个配对重复，偶数重复先 on 后 off。汇总时对所有目录重复传 `--inputs`；合并程序不请求模型，不需要模型地址/密钥，检查实验 ID、SDK、提示/数据、负载参数、缓存声明、重复配对和重复计数。参数冲突或缺配对会失败，原报告保持不变。单次单组成功结论为 `INCOMPLETE_COMPARISON`；一对仍是 `INSUFFICIENT_DATA`。

`--cache-isolation external` 只记录声明，不验证实际隔离。可用 `--server-evidence /path/evidence.txt` 附加部署方证据，原文件复制到报告目录；程序仅标记“附件已提供，未经自动验证”，不将声明或附件自动当作执行事实。

## 参数

| 参数 | 默认 / 环境变量 | 含义 |
|---|---|---|
| `--api-base` | API_BASE，必填 | 模型服务地址 |
| `--model-id` | MODEL_NAME，必填 | 服务端模型 ID |
| 无命令行参数 | API_KEY | 密钥只从环境读取 |
| `--config` | 无 | 无密钥 JSON 文件 |
| `--mode` | compare | baseline / compare / report |
| `--profile` | smoke | smoke / performance，只为未显式配置的参数赋默认值 |
| `--workload` | both | continuous / idle-resume / both |
| `--concurrency` | 1 | 逗号分隔并发档位，每项不能超过 sessions |
| `--sessions` | smoke 1 / performance 8 | 每组每档位每次重复的会话总数 |
| `--rounds` | 2 / 4 | 每会话正式轮数 |
| `--repeats` | 1 / 5 | 正式重复数；单组运行必须为 1 |
| `--warmup-rounds` | 1 | 每组/档位/重复的独立预热会话轮数；0 表示禁用 |
| `--idle-seconds` | 5 | 暂停后的固定等待窗口 |
| `--payload-chars` | 8192 | 演示明细字符数，不是 token 数 |
| `--max-tokens` | 512 | 单模型调用输出预算，思考 token 也可能占用预算 |
| `--request-timeout-seconds` | 60 | 官方客户端请求与显式管理动作超时 |
| `--batch-timeout-seconds` | 1800 | 包含全部会话的批次截止时间；另有有限清理预算 |
| `--min-change-percent` | 5 | 判断有实际变化的阈值，需在测试前设定 |
| `--group` | both | compare 的 both / off / on |
| `--experiment-id` / `--repeat-index` | 自动 / 1 | 单组必须显式配置，供配对合并 |
| `--cache-isolation` | uncontrolled | uncontrolled / external；仅声明 |
| `--server-evidence` | 无 | 外部证据文本附件 |
| `--inputs` | 无 | report 输入目录，可重复传入 |
| `--output` | 新的 reports/run-* | 不覆盖历史报告 |

## 两种负载与测量窗口

`continuous` 连续多轮查询。`idle-resume` 先查询，on 请求 suspend，off 按相同业务节奏等待，再继续查询；on 后续由 runtime 自动 prefetch，结束请求 release。两者都保留会话历史。SDK 的流式队列在一轮后关闭，所以 benchmark 按官方 Runner 的方式每轮创建新的 AgentSession facade，复用同一 ID、Agent 和 runtime；非流式样例不需要这样处理。

sessions 可以大于 concurrency：按波次执行，每波最多 concurrency 个会话，波内并发、暂停、恢复、清理后再启动下一波，避免屏障死锁。并发是会话 worker 数，等待时仍占位，不代表服务端同时在推理的请求数。这是闭环负载，不是固定到达率容量测试。

分别统计首次、普通后续、恢复首轮；有效吞吐分母为批次完整墙钟窗口，含初始化、等待和清理，不能用调用耗时之和代替。业务轮时延不含设定等待。首次/后续延迟可变，开启 Hint 不保证更快。当前不包含第三方会话施压实验，不据暂停恢复负载推断资源释放收益。

## 客户端能收集什么

| 指标 / 记录 | 来源与边界 |
|---|---|
| 轮端到端、首正文延迟 | 单调时钟与 Runner 流；首正文不是引擎 TTFT |
| 均值、P50/P95/P99 | 业务成功轮，nearest-rank；失败仍在成功率分母 |
| 调用成功率、业务正确率 | 全部正式计划轮次为分母，逐轮保留错误和真实回答 |
| 有效吞吐 | 调用成功且事实正确的轮次 / 完整批次墙钟窗口 |
| 模型/工具调用数、完成耗时 | 每个 Agent 的官方 callback，按会话/轮次关联，不覆盖全局 callback |
| 输入/输出 token | 每模型调用最后的累计 usage，只计一次；提供覆盖率，不逐 chunk 累加 |
| suspend/release 耗时与返回 | 宿主调用公开 future；仅客户端动作结果，不是实际迁移量 |
| Hint 构造与缓存警告 | 过滤后的 sdk.log；日志中 sent 在 HTTP 完成前，不能当服务端执行次数 |
| 缓存 token / 缓存占比 | **N/A**：0.1.17 流式解析没有读取 cached_tokens |
| 每模型调用的首 token / 自动 prefetch 成功量 | 当前采集路径不能可靠取得，null/unknown，不伪装成 TTFT |

SDK 的用量字段缺失可能归零。只接受正的输入/输出累计计数为已知；真实为零但无法区分缺失时仍记 ambiguous。整组成功轮覆盖不全时 token 总量为 N/A，避免只汇总已知部分冒充完整用量。completion_tokens 可能包含思考 token，chunk 也不是 token，不能据片段间隔计算引擎 ITL/TPOT。SDK 定义了 first_token_time 等字段不代表 provider 实际填充，不使用默认值当实测。

## 确定性结论及阅读顺序

终端与 report.md / conclusion.json 使用同一结论对象，不存在 AI 总结步骤。相同原始记录、阈值与规则版本产生相同结果。程序先判有效性，再分类；基线永不输出 Hint 收益。

- 调用、业务事实、未完成轮、显式管理动作或清理失败：`INVALID`，非零退出。
- 仅基线：`BASELINE_ONLY`；缺另一组：`INCOMPLETE_COMPARISON`。
- smoke、少于 5 个配对重复，或分组少于 20 个成功轮：`INSUFFICIENT_DATA`。
- 按负载/并发/轮次类型对比 P50，按完整窗口对比吞吐，恢复首轮单列。变化达到阈值且至少 80% 的重复同向才判改善/退化；小于阈值且至少 80% 的重复也在阈值内判无明显变化，否则波动不一致。
- 同时存在改善与退化：`TRADEOFF`；不稳定或缺指标：`INCONCLUSIVE`；其余为 `OBSERVED_IMPROVEMENT`、`OBSERVED_REGRESSION`、`NO_MATERIAL_CHANGE`。不把不同负载平均成一个总提升率。

延迟降低率 `(off-on)/off`；吞吐增加率 `(on-off)/off`；正数改善、负数退化；基线零或缺失为 N/A。5% 和 80% 是可审计工程规则，**不是统计显著性检验**。P95 少于 20 个成功样本、P99 少于 100 个成功样本不显示；达到门槛也不保证尾部稳定。同一会话多轮有相关性，160 轮不等于160个独立实验。

按以下顺序读报告：先看业务是否全部成功和 errors → 看两组配置、缓存状态与证据 → 比较同组的延迟/吞吐/恢复代价 → 检查逐重复波动、token 覆盖和调用次数。客户端改善与服务端 Hint 执行是独立结论，证据缺失时不能归因于 AgentHint。

## 报告地址、文件与退出码

终端结束打印测试结论、分组结果、证据说明以及 **report.md、conclusion.json、原始记录目录的绝对路径**。默认是 Linux 文件路径，不是 HTTP 网址；远端运行时下载整个目录查看。Docker 输出的 `/work/...` 是容器挂载路径，对应宿主仓库相同相对路径。

| 文件 | 内容 |
|---|---|
| report.md | 代码生成的结论、指标表、阅读方法和边界 |
| conclusion.json | 规则版本、状态码、原因码、阈值、逐重复变化和证据等级 |
| requests.jsonl | 逐轮真实记录；预热标 warmup=true，缺失轮标 NOT_COMPLETED |
| model-calls.jsonl | 官方 callback 模型调用耗时和累计用量 |
| actions.jsonl / sdk.log | 显式动作与 SDK 构造日志，分别保留来源 |
| batches.json | 各重复的窗口、等待和轮记录，可复算吞吐 |
| summary.json / summary.csv | 分组汇总、样本数和覆盖率 |
| run-manifest.json | SDK、JDK/OS、模型、全部非敏感参数、业务提示摘要、实验 ID 和错误 |

0 表示请求的执行范围完成，包括仅基线、单组完成、证据不足或性能退化；1 为运行/校验/管理动作/报告失败；2 为配置/依赖/构建失败；SIGINT/SIGTERM 保留 130/143。可处理失败尽量生成报告，构建或配置失败尚未生成报告时会明确说明。强制杀进程不能保证最终报告写完，但原始记录逐轮落盘。

## 服务端仍需提供的指标

实际 offload/prefetch/evict 执行量和迁移字节需兼容网关/调度器日志；KV 占用、缓存命中、排队/prefill/decode 需引擎 `/metrics` 与 Prometheus/Grafana；NPU 利用率、HBM、功耗需设备 exporter。指标名按部署版本核对，counter 使用测量窗口增量，不能直接比较累计值。共享业务流量无法隔离时，只作参考。程序未内建通用服务端采集器。

## 验收与已保存的真实基线

```bash
cd benchmarks/java_agent_hint
mvn test                    # 离线统计、参数、结论与合并测试
mvn -Preal-model test       # 配置 API_BASE / MODEL_NAME / API_KEY，真实并发多轮验收
```

2026-11-10 在 Linux Docker、Eclipse Temurin Java17、Maven3.9.16 上通过脚本调用 DeepSeek 官方 deepseek-flash：

```bash
bash benchmarks/java_agent_hint/run_benchmark.sh \
  --mode baseline --profile smoke \
  --api-base https://api.deepseek.com --model-id deepseek-flash \
  --workload both --payload-chars 2048 --idle-seconds 1 \
  --output /work/benchmarks/java_agent_hint/reports/2026-11-10-deepseek-linux-baseline
```

[真实报告](reports/2026-11-10-deepseek-linux-baseline/report.md)、[终端输出](run_output.txt)。4 个正式轮次全部通过，2 个预热轮次不计统计；每轮真实调用模型两次、查档工具一次。

| 负载 | 正式通过 | P50 ms | 完整窗口有效轮/s |
|---|---:|---:|---:|
| continuous | 2/2 | 2102.781 | 0.472 |
| idle-resume | 2/2 | 2277.679 | 0.300 |

结论为 `BASELINE_ONLY`。样本少，P95/P99 为 N/A；这些数字只证明本次业务和报告路径可跑，不是 DeepSeek 容量结论，也不是 AgentHint 收益。JUnit 共 25 项通过（24 项离线、1 项真实并发多轮集成），真实集成覆盖另8个业务轮。最终离线回归见 [offline_test_output.txt](offline_test_output.txt)。其余真实测试与失败路径见 [test_output.txt](test_output.txt)、[失败报告](reports/2026-11-10-unreachable-verified/report.md)、[脚本验收](script_test_output.txt)。

官方依据：[v0.1.17 KV 示例](https://github.com/openJiuwen-ai/agent-core-java/tree/v0.1.17/examples/kv-cache-demo)、[SDK usage 与 Hint 编码](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.17/core/src/main/java/com/openjiuwen/core/foundation/llm/modelclients/OpenAIModelClient.java)、[DeepSeek 官方模型配置](https://api-docs.deepseek.com/quick_start/pricing)。
