# Java 0.1.17 AgentHint 样例与性能对比设计

状态：设计提案，待用户评审；本轮不实现业务代码、不运行性能实验，不包含实测收益。

用户已明确：兼容 AgentHint 的推理环境待准备，本轮先完成设计；后续真实 off/on 验收待环境就绪，不以普通模型基线替代。

补充需求：必须交付 Linux 一键执行 benchmark 的脚本，支持模型地址、模型 ID 等参数；终端打印报告绝对路径及确定性代码生成的测试结论，并提供脚本使用指导。该要求纳入本期设计，当前仍不实现脚本或压测程序。

## 1. 目标与已有工程

面向银行业务开发者，提供一个能照着写的 Java AgentHint 样例，以及可复算、能说明适用边界的基线 / Hint 对比程序。回答四个问题：如何开启、什么时候管理缓存、客户端能看到什么、性能报告应该怎样读。

- 新编号建议为 `28_credit_review_agent_hint`；已有 26、27 保持现状。
- 样例 24 和 `benchmarks/java_ascend_affinity` 使用 0.1.16 的旧协议，不改造成 AgentHint，不继承其性能结论。
- 已有样例 27 的 pom 已声明 0.1.17；本设计另外直接核对官方 `v0.1.17` 的 examples 与源码。
- 两个新 Maven 工程固定依赖 `com.openjiuwen:agent-core-java:0.1.17`，Java 17+、Maven 3.9+。开始实现时再次确认 release、仓库可取得的制品与实际解析版本；不能用 develop 或自建框架补足缺失 API。
- Python 目录仅放 README，明确“待实现”；本次不承诺 Python 有同一能力。

## 2. 方案取舍

| 方案 | 优点 | 代价 / 判断 |
|---|---|---|
| 扩展旧 sample 24 和旧 benchmark | 改动文件少 | 混合两个协议和版本，违背老样例维持现状的约定，不采用 |
| 新建单 Agent 样例和独立 benchmark | 开启步骤短；变量和指标容易解释 | 不覆盖子 Agent 血缘；推荐作为本期方案 |
| 多 Agent、上下文压缩、缓存调度一次覆盖 | 生命周期覆盖广 | 调用数、上下文变化与调度耦合，难以归因；留作后续独立扩展 |

以下按推荐方案设计，均为设计建议，尚未视为用户已经选定。

## 3. 官方能力与开启方式

官方示例的 embedded mock gateway 只作为读取 API 用法的材料；不复制其假模型、模拟压缩器或断言到业务入口。

### 三项配置与会话装配

```java
ModelClientConfig clientConfig = ModelClientConfig.builder()
    .clientProvider("AscendAffinity")
    .apiBase(apiBase)
    .apiKey(apiKey)
    .maxRetries(0)
    .extraFields(Map.of("extensions",
        Map.of("kv_cache", Map.of("mode", "affinity"))))
    .build();

ReActAgentConfig agentConfig = ReActAgentConfig.builder()
    // 业务提示、工具、调用上限等与基线相同
    .build();
agentConfig.setEnableKvCacheAffinity(true);
```

这段代码仅说明配置位置，实际业务提示和工具按官方 ReAct 示例装配。生命周期场景还需按官方 scheduling 示例创建 `KVCacheRuntime`，注入同一个 `AgentSession`，多轮调用复用该 session。超时预算使用官方 `KVCacheConfig`，在报告中记录，不私自增加 Runtime / Model 包装层。

- 普通推理：SDK 自动构造 `agent_hint` 身份信息，业务代码不拼 wire JSON。
- 暂停：宿主在业务调用完成、没有在途推理时调用 `session.suspendKvc()`，等待 future 完成。
- 继续：复用 session 发起下一轮，runtime 的 admission 路径按状态自动 prefetch；不在每次推理前手动重复 prefetch。
- 结束：`session.releaseKvc()` 后不再继续该会话；最后关闭 runtime，并关闭 Runner。
- `release` 是释放缓存，不是删除业务历史；README 需要说明两者区别。
- 官方客户端能力开关来自 `extensions.kv_cache.mode`；仅换 provider 名称、仅开 Agent 开关都不足以说明完整装配。
- 此协议的管理动作也经官方模型客户端发送。不能照旧例要求 `/release_kv_cache`，也不能只凭普通 OpenAI 服务返回 200 判断兼容。
- `agent_hint sent` 日志出现在 HTTP 完成前，仅说明 SDK 构造了请求意图。模型能力检查也仅是本地声明；服务端接收、执行和实际资源变化要另取证。
- runtime 存在 fail-open：缓存动作失败时业务推理可能仍成功，必须分别记录业务成功与 Hint 管理异常。

README 提供 `AGENT_HINT_ENABLED=false/true` 的运行命令，并分别列出 API_BASE、API_KEY、MODEL_NAME、工具调用和流式支持要求；API_BASE 的具体拼接约定在实现时按该版本客户端核对，不沿用旧 provider 的地址规则。

## 4. 样例业务与目录

业务：信贷专员复核一位虚构客户，Agent 用只读工具查征信明细，再回答信用情况和风险。使用固定演示档案，真实模型生成回答；不模拟真实放款、不增加多 Agent 或自定义上下文压缩。

交互主线：运行者输入客户问题 → Agent 查档回答 → 运行者继续提问或输入 `/pause` → 宿主暂停缓存 → 运行者输入 `/resume` 并继续提问 → `/exit` 结束并清理。暂停期间不接受业务推理；等待输入时长不算性能。客户问题与客户编号由运行者输入，入口不替人作答。

```text
samples/28_credit_review_agent_hint/
  README.md
  python/README.md                 # 待实现
  java/
    README.md
    pom.xml
    src/main/java/CreditReviewAgentHint.java
    src/test/java/...              # 配置/业务规则 UT、真实模型集成验收

benchmarks/java_agent_hint/
  README.md
  run_benchmark.sh                 # Linux 一键构建、运行、输出报告
  benchmark.example.json           # 无密钥的参数模板
  pom.xml
  src/main/java/AgentHintBenchmark.java
  src/main/java/BenchmarkOptions.java
  src/main/java/BenchmarkWorkload.java
  src/main/java/BenchmarkMetrics.java
  src/main/java/BenchmarkReport.java
  src/test/java/...
  reports/<run-id>/
```

保持业务样例单文件单特性。benchmark 中只拆负载、采集、统计和报告职责，直接调官方 API，不新增共享 Agent/LLM/Tool 核心库。benchmark 数据集属于测试输入，与业务入口分开；业务工具输出和提示版本保持一致，并记录摘要。

## 5. benchmark 对照实验

### 组别

`baseline` 模式可在普通真实模型上验证业务与采集；它只生成基线，报告不出现 AgentHint 收益结论。

`compare` 模式在同一兼容服务、同一模型上运行 A/B：

| 项目 | off：基线 | on：AgentHint |
|---|---|---|
| SDK / provider / 传输 | 同版本 / AscendAffinity / 同一端点 | 相同 |
| `extensions.kv_cache.mode` | `none` | `affinity` |
| Agent 亲和开关 | false | true |
| Hint runtime 和管理动作 | 不启用 | 注入并执行 |
| 业务提示、工具、上下文策略、推理参数 | 相同 | 相同 |

这是完整 AgentHint 功能配置的对比，不拆解每个配置项的独立贡献。off 不意味着关闭引擎所有 prefix caching；普通缓存策略两组必须一致。

temperature=0、top_p=1、相同输出预算；关闭可配置的 SDK / Agent 重试，记录无法禁用的回退及实际调用次数。两组使用相同测试案例和相同会话轮序；每组使用全新 session/runtime，跨组不共享业务历史。相同参数并不保证模型输出逐字一致，需要对业务事实和调用轨迹校验。

### 两类正式负载

1. `continuous`：同一客户多轮查询长征信档案，不主动暂停，用于观察连续会话的身份 Hint 行为和延迟。不能保证出现正收益。
2. `idle-resume`：按波次启动会话，每波不超过 concurrency；本波会话完成首轮后进入固定等待窗口；on 调 suspend，off 按同样业务节奏等待；恢复后继续真实查询。本波完成并清理后才启动下一波，禁止让占着全部 worker 的会话等待尚未被调度的会话。记录各会话实际暂停/恢复时间与波次窗口。单列恢复首轮延迟、offload 开销和完整周期吞吐，用于观察缓存管理代价及恢复体验。

两类负载分别统计；首次轮、普通后续轮、恢复首轮分别统计，不能混合掩盖恢复成本。初期不引入压缩、TaskTool 子 Agent，也不宣称测全了范围 evict / 血缘；终结会话的 session evict 通过 release 展示。

并发档位与活跃会话总数独立配置。默认闭环负载，报告说明并发是活动会话 worker 数，等待期间 worker 仍占位，不能当固定到达率或服务端并发请求数。本期只实现上述两类负载，不实现缓存压力实验，不以 idle-resume 证明 offload 的资源收益。若后续需要证明释放缓存有利于其他会话，再增加独立实验：暂停驻留会话→独立探测会话运行→恢复驻留会话，并要求足够长上下文和服务端缓存占用证据。

固定 benchmark 数据集每轮要求调用一次只读查档工具，结果包含对应客户编号和明确的档案事实（例如评分、逾期笔数）；校验从工具轨迹和真实回答分别取证。空正文、客户串线、遗漏事实、工具次数不符均记业务校验失败。此规则衡量演示任务是否完成，不评估真实授信结论的专业质量。

### 顺序、预热与缓存控制

- 每个配置重复实验，交替 off→on / on→off；保存每个重复的独立结果，不只给汇总均值。
- 独立会话预热，预热记录保留但不计入正式统计；预热失败使本次不可验收。
- 冷缓存、热缓存分开组织，不把二者混为一个分布。更换 session ID 不是清缓存证明。
- 程序不自动清空生产服务缓存或重启引擎。外部清理 / 独立等价实例由部署方完成，记录具体操作、时间和证据；`uncontrolled` 默认只允许探索性比较。
- 受控实验提供单组运行入口：compare 可加 `--group off` 或 `--group on`，用相同 `--experiment-id`、重复编号和负载摘要关联。部署方在两个独立进程之间清理/准备缓存，再用 report 模式合并两个结果目录。合并前检查 manifest 配置一致和组别完整，差异不能被静默忽略。自动连续 compare 不包含缓存重置机制；没有外部隔离证据仍标 uncontrolled。
- 两组前置缓存状态、资源、模型、网关、路由、负载、限流配置及后台流量有差异时，报告降低可比性等级。等待窗口、payload 字符数、上下文长度、模型调用数也要列出。
- `smoke` 仅验证流程，建议 1 会话×2 轮×1 重复；不产出稳定收益结论。正式建议起点每组每档位 8 会话×4 轮×5 重复；这是 160 个轮次，不是 160 个独立实验，连续会话存在相关性。运行前打印计划轮数及最大模型调用预算，可通过参数缩小。

## 6. 客户端采集指标与可信度

统一使用单调时钟计算时长，墙钟只用来与服务端日志对齐。为 run / group / repeat / session / turn / model-call 建立关联，callback 注册一次、按会话归属分流，禁止并发 worker 反复覆盖全局 callback。

| 指标 | 获取与定义 | 解读限制 |
|---|---|---|
| 业务轮端到端耗时 | 从发起 Runner 调用到完整消费流、得到结果 | 包含网络、推理、工具与调度；不含人工/设定等待 |
| 首个有效正文延迟 | Runner 调用起点到首个非空正文输出 | Agent 可能先调工具；不是引擎 TTFT |
| 每次模型调用首正文/完成耗时 | 官方 Model callback，区分正文、工具参数与其他 chunk | 无正文的工具调用首正文为 N/A；不是 token 级时延 |
| P50 / P95 / P99 | 同负载、并发、轮次类型内的业务成功样本 | 同时报调用成功率和业务校验通过率；不能删失败后只报快样本 |
| 有效业务吞吐 | 调用成功且事实校验通过的轮次 / 批次完整墙钟窗口 | 窗口含暂停/等待/清理；另报等待总时长，不能用调用耗时之和替代窗口 |
| 模型/工具调用数与工具耗时 | 官方 callback，区分业务推理与其他事件 | 异常增加会影响对比解释；管理请求不混入业务轮数 |
| token 与估算输出速率 | 最后一个有效累计 usage，每模型调用计一次，多调用再求和 | 报用量覆盖率；缺失为 null；输出 token / 模型调用耗时叫观测速率，不叫引擎 decode 速率 |
| 缓存 token / 缓存占比 | 当前流式主路径标 N/A；可选外部真实 usage 证据 | SDK 流式解析不读取 cached_tokens；不能把默认零报成零命中 |
| suspend / release 次数、耗时、返回结果 | 宿主显式调用对应公开 future 的边界 | true 仅代表客户端动作成功，不能证明实际移动多少 KV |
| Hint 身份/动作构造记录 | SDK 专用日志，按 session / action 关联，保留原始日志 | `agent_hint sent` 发生于 HTTP 完成前，不能叫服务端执行次数 |
| 自动 prefetch / fail-open | SDK 可取得的日志；有证据时记录，否则 unknown | 不使用反射读取 private runtime 状态，不承诺精确次数、成功率或耗时 |

0.1.17 的 `UsageMetadata` 定义了多种字段，但字段存在不等于当前 provider 会填值：`OpenAIModelClient.parseUsageMetadata` 只解析部分用量，缺失数值会归零；流式路径调用时 `includeCacheTokens=false`。输入/输出 token 要保留 coverage 和 ambiguous 状态，不能用字符数估算 token。`total_latency`、`first_token_time` 等默认字段不作为实测时延。

同一模型调用中的流式累计 usage 不可逐 chunk 累加；缓存率必须是已知缓存 token 总量 / 对应已知输入 token 总量，覆盖不足不输出整组命中率。chunk 间隔不是 ITL/TPOT，`completion_tokens` 也可能包含思考 token。具体零值是否有效无法从 SDK 确定时标 ambiguous，不改造模型客户端去伪造可观测性。

分位数设置样本门槛：成功样本少于 20 不报 P95，少于 100 不报 P99；达到门槛仍明确尾部估计不稳、样本相关。主报告同时展示每重复的差异及范围，不宣称统计显著性。

## 7. 服务端证据与结论规则

客户端不能直接得到 KV 实际占用/迁移字节、prefetch/offload/evict 实际执行量、引擎缓存查询命中、排队/prefill/decode、NPU 利用率/HBM/功耗。README 列出应从兼容网关/调度器日志、引擎 `/metrics`、Prometheus/Grafana、设备 exporter 获取；具体指标名按实际部署版本核对，不预造字段名。

第一期不内建通用 Prometheus 采集器；允许附加部署方导出的证据文件和说明，manifest 记录时间窗口、组件版本、关联 session 与隔离条件。counter 比较窗口增量，不能对累计值直接求组间差；无法过滤其他流量的监控只作参考。

报告独立回答：

1. **业务有效性**：成功、失败、未完成数量；关键事实校验、客户串线、空回答、重复工具调用和超时情况。
2. **Hint 证据**：配置开启、SDK 构造记录、客户端动作结果、服务端执行证据各是什么；无服务端证据明确“未验证实际执行”。
3. **实验可比性**：版本/资源/负载/缓存状态/流量是否受控，是否存在降级、用量缺失或输出长度差异。
4. **性能观察**：哪些延迟下降/上升，吞吐如何变化，恢复成本是多少，重复之间是否一致。

延迟降低率 `(off-on)/off`；吞吐增加率 `(on-off)/off`；正值改善、负值退化，基线为零或缺失则 N/A。恢复延迟降低与 offload 资源收益分别解释，不预设开启必定更快。

结论可为“观察到改善”“观察到退化”“不同指标有取舍”“差异不稳定/样本不足”“仅基线”“实验无效/协议未验证”。存在业务失败、系统性调用轨迹偏差或 Hint 管理失败时，不给肯定的收益归因。只有受控 A/B、业务有效且有服务端执行证据，才讨论本环境 AgentHint 的收益；仍不泛化为其他模型或线上容量承诺。

## 8. 报告与 README 交付

每次运行写入全新目录，不覆盖历史：

- `report.md`：结论置前，随后是可比性、off/on 表、分轮次表、重复波动、缺失指标、证据与限制。
- `requests.jsonl`：逐轮原始记录、真实回答、错误、业务校验、warmup 标志；未完成项显式标记，不填零时延。
- `model-calls.jsonl`：逐模型调用计时、用量覆盖、工具调用关联；帮助解释调用次数变化。
- `actions.jsonl` 与 `sdk.log`：显式动作结果与 SDK 构造记录区分来源；自动动作证据缺失为 unknown。
- `batches.json`：每个重复/配置的测量窗口、等待、清理及未完成项，可复算吞吐。
- `summary.json` / `summary.csv`：分组统计、原始分母、样本数、改善率、coverage。
- `conclusion.json`：确定性规则生成的状态码、原因码、分组指标变化、判定阈值与证据等级；终端和 report.md 使用同一份结论对象。
- `run-manifest.json`：SDK 制品版本、JDK/OS、模型、服务组件版本（未知则写未知）、参数、数据/提示摘要、顺序、超时、缓存控制和外部证据。禁止写 API_KEY；默认不记录请求授权头。

程序配置错误先失败；调用/业务校验失败、批次超时或显式管理动作失败时保留已有记录、生成失败报告并非零退出。只有基线、缺少服务端证据、性能退化并不等于程序执行失败，报告必须说明范围；不得凭零退出码声称 Hint 已执行或已经加速。

样例 README 负责“会用”：场景、版本、目录、前提、开关代码、运行者每步输入/输出、暂停/继续/结束、真实交互记录、UT 命令与边界。

benchmark README 负责“会测、会读”：普通基线、兼容服务 A/B、负载/参数、预热/缓存控制、采集表、公式、报告文件、失败与 N/A 的含义、服务端证据获取及真实报告链接。解读顺序固定为：先看有效性 → 看证据与可比性 → 看 P50/尾延迟/吞吐和恢复成本 → 看波动与调用/token 差异。用真实报告举例，不编造百分比示范为实测。

## 9. Linux 一键运行脚本与使用指导

### 入口与职责

交付 `benchmarks/java_agent_hint/run_benchmark.sh`，Linux Bash 4+，JDK 17+、Maven 3.9+。通过脚本所在目录定位工程，可从任意当前目录调用；路径含空格也可用。不自动安装系统依赖，不要求 Python、jq 或 AI 总结服务。

脚本完成：解析/校验参数→检查依赖→Maven 构建可执行 JAR→以参数数组调用 Java benchmark→打印结果与报告路径。首次构建需要访问依赖仓库，模型请求需要访问配置端点；README 分别解释依赖下载失败与模型连接失败。完整参数解析、统计及结论在 Java 中统一实现，Bash 不再维护另一套统计逻辑。禁止 eval 或把用户参数拼成待执行命令；密钥只从环境读取，脚本不打开命令追踪。

### 参数契约

非敏感配置优先级：命令行 > `--config` JSON 文件 > 环境变量 > 默认值；模型地址与 ID 不提供虚构默认值，缺失则报错。配置文件遇到未知字段报错，避免误拼参数导致静默使用默认值。API_KEY 只走环境，不支持密钥命令行参数或写入示例配置文件。

| 参数 | 环境变量 / 默认值 | 用途 |
|---|---|---|
| `--api-base` | `API_BASE`，必填 | 模型服务地址；README 按实际客户端说明路径规则 |
| `--model-id` | `MODEL_NAME`，必填 | 服务端模型 ID，兼容仓库已有环境变量命名 |
| 无命令行参数 | `API_KEY` | 密钥；无鉴权服务允许未设置，是否接受由实际服务决定 |
| `--config` | 无 | UTF-8 JSON 参数文件，模型地址/ID/非敏感压测参数 |
| `--mode` | `compare` | baseline / compare / report；默认一键 off/on，普通服务需显式 baseline |
| `--profile` | `smoke` | smoke / performance；默认低成本流程检查，不给稳定性能结论 |
| `--workload` | `both` | continuous / idle-resume / both |
| `--concurrency` | `1` | 并发档位，如 `1,4,8`，不可大于 sessions |
| `--sessions` / `--rounds` / `--repeats` | smoke 为 1 / 2 / 1；performance 为 8 / 4 / 5 | 每组、每档位的总会话数、每会话轮数、重复次数 |
| `--warmup-rounds` | `1` | 独立预热会话轮数，不计正式统计 |
| `--idle-seconds` | `5` | 暂停恢复负载的业务等待窗口；不含 offload 调用耗时 |
| `--payload-chars` / `--max-tokens` | `8192` / `512` | 演示档案长度与单次输出预算；字符数不是 token 数 |
| `--request-timeout-seconds` / `--batch-timeout-seconds` | `60` / `1800` | 官方请求超时和 benchmark 批次硬截止时间 |
| `--min-change-percent` | `5` | 性能结论的实际变化阈值，测试开始前固定并记录 |
| `--output` | 工程下新的 `reports/<run-id>/` | 新报告目录；拒绝覆盖既有目录 |
| `--group` / `--experiment-id` / `--repeat-index` | 未指定 / 自动生成 / 未指定 | 受控外部实验的单组运行和配对；单组必须显式指定实验 ID、重复编号 |
| `--inputs` | 无 | report 模式合并单组结果目录，可重复传入；不调用模型、不要求 API 配置 |
| `--help` | 无 | 参数、默认值、运行示例及退出码 |

profile 只设置未显式指定的压测参数；不能覆盖命令行或配置文件。performance 也需校验实际样本与重复数，不能仅因名称就认定样本充分。缓存控制与外部证据沿用前文 manifest 契约；外部声明不会被程序自动当成已验证事实。

### 使用指导必须提供的命令

以下为计划实现的脚本接口示例，不是已经存在或经过实测的命令。README 实现后要替换为实际验证过的命令，并给出依赖准备、模型前提与输出位置。

```bash
# 建议先通过终端安全输入密钥；不需要鉴权时可省略
read -r -s -p 'API key: ' API_KEY
export API_KEY
printf '\n'

# 普通模型：先检查业务负载和报告流程
bash benchmarks/java_agent_hint/run_benchmark.sh \
  --mode baseline --profile smoke \
  --api-base 'http://127.0.0.1:8000/v1' --model-id 'your-model-id'

# 兼容 AgentHint 的服务：一键 off/on 对比
# 下列地址仅说明形态，路径必须按实际服务与 SDK 核对
bash benchmarks/java_agent_hint/run_benchmark.sh \
  --mode compare --profile performance \
  --api-base 'http://hint-server:8000/v1' --model-id 'your-model-id' \
  --concurrency 1,4,8 --sessions 8 --rounds 4 --repeats 5 \
  --workload both --output '/tmp/agent-hint-run-001'

# 复用非敏感配置，也可通过 API_BASE / MODEL_NAME 环境变量配置
bash benchmarks/java_agent_hint/run_benchmark.sh --config '/path/benchmark.json'

# 离线汇总已完成的单组结果，不请求模型
bash benchmarks/java_agent_hint/run_benchmark.sh \
  --mode report --inputs '/tmp/off-run' --inputs '/tmp/on-run' \
  --output '/tmp/agent-hint-merged-001'
```

README 还需提供单组 off→外部缓存准备→单组 on→合并报告的完整示例，重复多次时交替组别顺序。明确一键自动 compare 默认没有清缓存，不自动获得受控实验资格。

### 确定性结论，不调用 AI 总结

模型仅承担被测 Agent 业务回答。统计、校验、性能分类、中文结论模板、终端输出和 Markdown 报告均由普通 Java 代码完成，不调用 LLM 解释报告。相同原始数据和相同规则版本必须得到相同结论；保存 `rule_version`、阈值和 `reason_codes`，并在离线 JUnit 中覆盖边界。

规则顺序：

1. 先判业务调用/事实校验、未完成轮次、显式缓存动作失败及合并配置冲突；不满足则 `INVALID`，不作收益判断。
2. baseline 只有基线，输出 `BASELINE_ONLY`；尚未配齐的单组 compare 输出 `INCOMPLETE_COMPARISON`，等待合并。
3. smoke、正式重复少于 5 次，或某结论分组成功轮次不足 20，则该分组 `INSUFFICIENT_DATA`。尾分位数仍按前文独立样本门槛显示。
4. 对每个“负载×并发×轮次类型”分组，计算 P50 延迟降低率；对每个“负载×并发”计算完整窗口吞吐增加率。idle-resume 另列恢复首轮 P50。每项均保留逐重复的同向变化比例。
5. 指标变化达到预设阈值且至少 80% 的配对重复同向，分类为改善或退化；变化绝对值小于阈值且至少 80% 的重复也在阈值内，分类为无明显变化；其余为波动不一致。百分比基线为零、值缺失或配对重复不完整则该项无法判断。
6. 每个负载/并发的终端小结：同时有改善与退化则 `TRADEOFF`；存在无法判断/波动不一致则 `INCONCLUSIVE`；其余按改善、退化、全部无明显变化输出 `OBSERVED_IMPROVEMENT` / `OBSERVED_REGRESSION` / `NO_MATERIAL_CHANGE`。可用 P95 的退化也要明确提示，不能只挑 P50。多个分组不混算成一个平均提升率，终端列出各组并指出结果是否一致。
7. `performance_status` 与 `evidence_status` 独立。缓存未受控或缺服务端证据时仍可描述客户端观测数字，但结论固定附“不能据此确认 AgentHint 实际执行或归因于 AgentHint”；未知证据不填 true。外部证据附件仅代表已提供，程序不声称自动验证了附件真实性。

阈值和 80% 一致性是工程报告规则，不是统计显著性检验。原始值用于判断，展示时再四舍五入，避免边界误判。单组和完整 compare 的退出码不以性能改善与否决定。

终端结束固定输出以下信息，数值由实测填充；这里仅定义格式，不放虚构实测数字：

```text
执行状态: <完成 / 失败 / 中断>
测试范围: <仅基线 / off-on 完整对比 / 单组待合并>
测试结论: <确定性状态码及中文说明>
分组结果: <负载、并发、关键指标变化、样本量与原因>
证据说明: <缓存控制状态 / 服务端证据状态>
测试报告: <report.md 的绝对路径>
结构化结论: <conclusion.json 的绝对路径>
原始记录目录: <绝对路径>
```

报告地址默认是可打开的文件绝对路径，不伪造 HTTP 链接、不自行启动文件服务器；README 写明远端 Linux 上可下载整个报告目录查看。只在实际文件已写入后输出该报告路径；构建/配置/权限错误在尚无报告时明确“报告未生成”，同时输出可用日志位置，不报不存在的文件。

退出码：0 为本次请求的执行范围成功完成（包括仅基线、成功单组、证据不足或性能退化）；1 为运行、业务校验、管理动作或报告生成失败；2 为参数/配置/环境检查/构建失败；SIGINT/SIGTERM 保持 130/143。正常可处理的失败/中断尽量保存已有记录和报告，强制杀进程无法保证落盘。日志经 tee 时保留 benchmark 的真实退出码，禁止把输出日志成功误当执行成功。

## 10. 实现顺序与验收

1. 再核对发布制品与官方 API，确认实际测试服务是否支持 AgentHint；缺兼容服务不阻碍完成普通模型流程，但不能补写 A/B 收益。
2. 实现单文件交互样例和配置/规则 JUnit，真实模型集成跑多轮查档；兼容服务上验收暂停→继续→释放，保存人工真实交互记录。
3. 实现两个 benchmark 负载、有限 worker、callback 归属与 JSONL；为统计和失败语义写离线 JUnit，不 mock LLM。
4. 实现报告，用真实普通模型验证 smoke/基线；用真实不可达端点验证失败报告及非零退出。验证超时清理、累计 usage、unknown/零值、吞吐分母及并发会话不串线。
   同时实现并在 Linux 验证一键脚本、无密钥配置模板与 README 指导：任意当前目录、含空格路径、命令行/文件/环境变量优先级、帮助和未知参数、依赖缺失、构建失败、错误地址、成功/失败结论和路径、退出码及终端中断。用离线统计数据覆盖确定性结论的所有状态和阈值边界，不伪造模型响应；真实 smoke 仍调用真实服务。
5. 有兼容服务时运行受控 off/on，附服务端证据；没有则明确记录“AgentHint A/B 性能未验证”，保留可复现命令，不冒充完成性能验收。
6. 更新根 README、README-JAVA、FEATURES，仅新增 28 的能力与版本。`run_all_samples.py` 现有逻辑已会发现 `java/pom.xml` 并跑 `-Preal-model test`，优先直接沿用；benchmark 压测不加入日常全量回归。

本轮交付止于此设计文档；实施后“真实业务可跑”和“AgentHint 性能收益已测得”是两个独立验收项。

## 11. 官方依据（本轮读取 v0.1.17）

- [官方 KV cache 示例说明](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.17/examples/kv-cache-demo/README.md)
- [官方 scheduling 示例：配置、Runtime、AgentSession 与释放](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.17/examples/kv-cache-demo/src/main/java/examples/AscendAffinitySchedulingDemo.java)
- [ReActAgentConfig：Agent 开关](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.17/core/src/main/java/com/openjiuwen/core/singleagent/agents/ReActAgentConfig.java)
- [OpenAIModelClient：能力、Hint 编码、管理请求与 usage 解析](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.17/core/src/main/java/com/openjiuwen/core/foundation/llm/modelclients/OpenAIModelClient.java)
- [AgentHint：身份与三类缓存管理动作](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.17/core/src/main/java/com/openjiuwen/core/kvcache/AgentHint.java)
- [KVCacheRuntime：生命周期与 fail-open](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.17/core/src/main/java/com/openjiuwen/core/kvcache/KVCacheRuntime.java)

已确认 tag 内容和该 tag 的版本声明；网页搜索缓存曾返回旧 latest release，因此不以该缓存判断 Maven 最新发布状态，实施前再次核实发布与制品解析。
