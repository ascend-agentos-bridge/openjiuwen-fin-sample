# AgentHint benchmark：改 YAML，运行，看报告

默认完成 **AgentHint 关闭 / 开启的对比测试**。配置使用 YAML，接口与原始数据使用 JSON / JSONL，人工阅读报告目前为 Markdown（尚未提供 HTML）。统计和中文结论由确定性 Java 代码生成，不调用 AI 总结。

需要 Linux、Bash 4+、JDK 17+、Maven 3.9+，以及支持 AgentHint 协议、工具调用和流式返回的真实模型服务。Java SDK 固定为 `com.openjiuwen:agent-core-java:0.1.17`。

## 快速开始

**下面均从仓库根目录执行。**

**1. 编辑 [benchmark.yaml](benchmark.yaml)，填写自己的模型地址和模型 ID。**

```yaml
api-base: "http://your-server:8000/v1"
model-id: "your-model-id"

# 不确定时保留以下测试规模。
concurrency: [1]
rounds: 4
repeats: 5
```

API_BASE 后由 SDK 追加 `/chat/completions`：服务暴露 `/v1/chat/completions` 时地址应包含 `/v1`。服务还需接受 `thinking.type=disabled` 扩展。

**2. 提供密钥并执行脚本。**

```bash
read -r -s -p 'API key: ' API_KEY
export API_KEY
printf '\n'

bash benchmarks/java_agent_hint/run_benchmark.sh
```

不需要指定模式、输出路径、实验编号或开关。脚本自动读取旁边的 `benchmark.yaml`，首次构建程序，之后源码未变化时复用 JAR。

**3. 看终端结论，再打开报告。**

终端会显示本次任务、测试规模、执行进度，结束时打印：

```text
测试结论: <状态码及中文说明>
测试报告: /绝对路径/benchmarks/java_agent_hint/reports/run-时间戳-随机ID/report.md
```

这里是输出格式说明，不是虚构实测结果。每次生成新目录，不覆盖历史，也不自动删除旧报告。只需打开 `report.md`；其他文件用于排查与复算，不必逐个阅读。

默认执行两种负载，每组每次重复自动安排至少 8 个会话；`rounds=4、repeats=5、concurrency=[1]` 合计 **640 个正式业务轮、20 个预热轮**。正常每轮调用模型两次，约 1320 次模型调用；实际次数以报告为准。运行前终端也会提示调用预算。

## 手头只有普通模型：先检查能否跑通

普通模型不能验证 AgentHint。保留 YAML 中测试规模，使用 `check` 即可自动缩小到单并发、1 会话、2 轮、1 次重复，只跑普通模型基线：

```bash
# 在 YAML 中填写普通模型的地址和模型 ID，例如 DeepSeek 官方服务。
bash benchmarks/java_agent_hint/run_benchmark.sh check
```

DeepSeek 官方示例连接为 `api-base: "https://api.deepseek.com"`、`model-id: "deepseek-flash"`，密钥仍放在 API_KEY 环境变量。

`check` 正常完成时结论为 `BASELINE_ONLY`，不会假装做过 AgentHint 对比。默认对比执行失败时也不会静默降级为基线。**当前已验证普通 DeepSeek 基线，尚无兼容环境验证 AgentHint 实际执行或性能收益。**

## 日常只需要调整这些

| YAML 字段 | 默认 / 用途 |
|---|---|
| `api-base`、`model-id` | 模型连接；首次使用主要修改这两项 |
| `concurrency` | `[1]`；可改为 `[1, 4, 8]` 比较不同并发，耗时和调用量随档位增加 |
| `rounds` | 4；每个会话连续查询轮数 |
| `repeats` | 5；配对重复次数，程序自动交替 off/on 顺序 |

会话数量自动取“至少8，且不小于最高并发”。预热、两个负载、开关组合、实验编号、统计阈值和报告命名由程序管理，不需要逐项填写。`AGENT_HINT_ENABLED` 是业务样例的变量，不控制此 benchmark。

有特定测试目的时，可选填以下 YAML 字段；普通运行不用写：

- `sessions`：手动指定每组每档位每次重复的会话数，不能小于最高并发。
- `idle-seconds`：暂停窗口，默认 5 秒。
- `payload-chars`：档案明细字符数，默认 8192，不是 token 数。
- `max-tokens`：单次模型输出预算，默认 512。
- `request-timeout-seconds`：请求及显式管理动作超时，默认 60 秒。

配置优先级为 YAML > 模型环境变量 API_BASE / MODEL_NAME > 程序默认值。密钥只从 API_KEY 读取，不允许写进 YAML。配置只接受 `.yaml` / `.yml`；重复字段、未知字段或类型错误会报错。

需要换一份配置时传文件即可，路径含空格也可使用：

```bash
bash benchmarks/java_agent_hint/run_benchmark.sh '/path/my-test.yaml'
bash benchmarks/java_agent_hint/run_benchmark.sh check '/path/deepseek.yaml'
# 也兼容 --config FILE.yaml
```

相对配置路径相对于当前工作目录；报告始终位于脚本工程的 reports 下。`--mode`、`--output` 等旧命令行控制不再接受，YAML 中的 mode/output/profile/group 等旧字段也需删除。查看简短帮助不需要安装 Java 或构建程序：

```bash
bash benchmarks/java_agent_hint/run_benchmark.sh --help
```

## 报告怎么读

1. **先看成功与正确性。** 每轮应查询一次 C1001 档案，回答评分 735、逾期 0 笔；模型调用、事实校验、缓存动作或清理失败时为 `INVALID`。
2. **再看同一负载、并发和轮次类型的 off/on。** `all` 为全部轮次；`first` 为首轮；`resumed` 为暂停恢复后的第2轮；`subsequent` 为其他后续轮。不要跨组混比。
3. **看延迟和吞吐是否存在取舍。** 延迟降低率 `(off-on)/off`，吞吐增加率 `(on-off)/off`，正数改善。有效吞吐的分母含等待和清理；业务轮时延不含设定等待。
4. **最后看重复波动和证据。** 默认至少5次重复、分组至少20个正确轮次，变化达到5%且80%重复同向才判稳定改善/退化。这是工程规则，不是统计显著性检验。退出码0表示本次执行完成，不代表性能提升。

| 结论 | 含义 |
|---|---|
| BASELINE_ONLY | 只完成普通模型检查，没有测 Hint 收益 |
| INSUFFICIENT_DATA | 对比样本或重复不足；缩小规模后可能出现 |
| OBSERVED_IMPROVEMENT / OBSERVED_REGRESSION | 客户端观察到改善 / 退化 |
| TRADEOFF / INCONCLUSIVE | 指标有取舍 / 差异不稳定或缺少必要指标 |
| NO_MATERIAL_CHANGE | 未观察到达到阈值的稳定变化 |
| INVALID | 存在运行或业务校验失败，应先排查 |

P95 少于20个成功样本、P99 少于100个成功样本时为 N/A；达到门槛也不保证尾部估计稳定。客户端改善不等于服务端 Hint 已执行，更不能直接泛化为线上容量结论。

## 常见问题

| 现象 | 怎么处理 |
|---|---|
| 构建失败、尚无报告 | 打开终端的构建日志，检查依赖仓库、JDK17、Maven3.9；MAVEN_CMD 可指定 Maven 可执行文件或企业镜像包装脚本 |
| 401 / 403 | 检查密钥是否 export、权限及服务地址 |
| 404 | 检查模型 ID 和 API_BASE 的路径 |
| 429 / 超时 | 先降低 YAML 的 concurrency，再检查服务限流、容量和超时 |
| INVALID / 空回答 / 工具错误 | 读 report.md 的错误，再看 requests.jsonl 的 error、answer、toolCalls；确认工具调用支持及输出预算 |
| 普通模型不接受 Hint | 使用 check 做基线检查；真实对比需换兼容环境，不把普通模型响应当 Hint 生效证明 |

脚本退出码：0执行完成；1运行/校验失败；2配置/依赖/构建失败；SIGINT/SIGTERM 为130/143。可处理失败尽量保存报告；强制杀进程不能保证最终报告完整。

<details>
<summary><strong>高级说明：实验边界、指标和原始文件（按需展开）</strong></summary>

### 自动完成的实验步骤

两组使用同一 SDK、AscendAffinity provider、模型、端点、业务提示与工具。off 关闭 Agent 亲和和 kv_cache 扩展，不挂 runtime；on 同时开启并使用官方 KVCacheRuntime。两组 temperature=0、top_p=1，关闭可配置的模型/流式重试。off 不代表关闭服务端全部 prefix caching。

continuous 是连续多轮查询；idle-resume 在首轮后等待，on 请求 suspend，继续时 runtime 按状态自动 prefetch，结束时 release。按并发波次执行，防止会话数大于 worker 数时形成屏障死锁。并发表示会话 worker 数，是闭环负载，不代表固定到达率。

程序不会清缓存或重启引擎。默认前置缓存状态未受控，仅可描述客户端观察；更换 session ID 不构成隔离证明。冷/热缓存和第三方会话施压需部署方组织，不根据本负载断言释放资源的收益。服务端实际迁移量、KV命中、排队/推理阶段耗时和NPU/HBM需要部署方监控，当前程序不自动采集或认证这些证据。

### 可采集指标

端到端与首正文延迟（不是引擎TTFT）、分位数、调用成功/业务正确率、有效吞吐、模型和工具调用数/完成耗时、SDK最终累计token用量及覆盖率、显式suspend/release的返回与耗时。缓存动作true及SDK构造日志不等于实际迁移量。

SDK0.1.17流式路径不读取cached_tokens，缺失数值还可能归零，因此缓存token和命中率为N/A；usage覆盖不足不输出完整token总量，不用字符或chunk估算token。每模型调用首token和自动prefetch精确执行量仍为null/unknown。

### 原始文件与历史结果合并

只阅读 report.md 即可；其余为诊断材料：conclusion.json是确定性结论；requests.jsonl是逐轮真实回答与错误；model-calls.jsonl是调用耗时/用量；actions.jsonl、sdk.log是管理动作和构造记录；batches.json保存墙钟窗口；summary.json/csv为统计；run-manifest.json为非敏感配置与环境。

如已有分开执行的配对报告（例如旧版本生成的单组结果），可执行 `bash benchmarks/java_agent_hint/run_benchmark.sh merge /path/off /path/on`。合并不调用模型，检查实验ID、参数、重复配对及重复计数；结果同样生成新时间戳目录。日常对比不需要这个步骤，也不必手填实验编号。

### 已保存验收记录

2026-11-10 Linux Docker / Java17 / Maven3.9.16 / DeepSeek官方deepseek-flash，两个基线负载共4正式轮与2预热轮通过。该记录对应旧版入口，历史命令不作为新版用法：

- [真实报告](reports/2026-11-10-deepseek-linux-baseline/report.md)、[终端记录](run_output.txt)。结论BASELINE_ONLY，continuous P50为2102.781ms，idle-resume P50为2277.679ms；仅少量样本，不代表Hint收益。
- [真实集成测试](test_output.txt)、[离线测试](offline_test_output.txt)、[失败报告](reports/2026-11-10-unreachable-verified/report.md)。

开发者回归：`mvn -f benchmarks/java_agent_hint/pom.xml test`；真实模型集成用 `mvn -f benchmarks/java_agent_hint/pom.xml -Preal-model test`，并配置 API_BASE / MODEL_NAME / API_KEY。离线统计夹具不充当模型运行效果。

官方依据：[v0.1.17示例](https://github.com/openJiuwen-ai/agent-core-java/tree/v0.1.17/examples/kv-cache-demo)、[SDK客户端](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.17/core/src/main/java/com/openjiuwen/core/foundation/llm/modelclients/OpenAIModelClient.java)。

</details>
