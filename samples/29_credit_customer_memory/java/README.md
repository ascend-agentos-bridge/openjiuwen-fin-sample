# 29 · 信贷客户记忆助手（Java 版）

> **真实 Java 实现已落地并完成 DeepSeek 实测**。`agent-core-java:0.1.16` + `agent-core-memory-java:0.1.16` 全配对；
> 长期记忆落本机 SQLite，图记忆按方案 B 使用 `InMemoryGraphStore`；Milvus 等待 memory 0.1.17 发布后切换
> （`../docker-compose.yml` 已备好）。页面能力对齐 `../prototype/`，但数据全部来自真实 Java 后端和真实模型。

## 场景

客户周砚清与信贷智能体的对话。每轮对话后，应用在后台异步触发两件事：

1. `LongTermMemory.addMessages(..., gen_mem=true)`：用**记忆模型**从最近会话上下文里做 LLM 记忆分析、
   摘要生成、用户画像/语义/情景记忆抽取与冲突合并，写入本机 SQLite；向量索引在进程内；
2. `GraphMemory.addMemory(...)`：用**记忆模型**抽取实体/关系/情节，合并去重后写入 `InMemoryGraphStore`。

下一轮回答前，宿主层把长期记忆（向量 + KV 变量）与图记忆（实体/关系/情节）做多路召回、融合，
再把融合结果作为上下文交给**对话模型**生成回复。右侧页面展示：长期记忆、图记忆可视化、加工流水线
（输入上下文 → LLM 提炼 → 产出）、本轮召回来源。

## 版本与限制（必读）

| 项 | 取值 |
|---|---|
| Java | 17 |
| `agent-core-java` | 0.1.16（必须与 memory 0.1.16 成对） |
| `agent-core-memory-java` | 0.1.16（Maven Central 尚未发布 0.1.17） |
| 长期记忆存储 | 本机 SQLite `data/credit_memory.db`（KV + 关系表 + 进程内向量索引） |
| 图记忆存储 | `InMemoryGraphStore`（方案 B；进程重启后图数据丢失，需要重建） |
| 图关系抽取 | 0.1.16 原始 GraphMemory 有 prompt/parser 字段不一致问题；样例用 `GraphRelationCompatModel` 做临时字段适配，实测关系边已出现。这是兼容层，上游修复后应删除 |
| Milvus | 未启用；`../docker-compose.yml` 为 0.1.17 后的升级目标 |
| 模型服务 | 任意 OpenAI 兼容端点（对话 + Embedding）；本机验收使用 LM Studio |

- `memory 0.1.16` 的 `addMessages` / `GraphMemory.addMemory` 是**同步调用**；样例在对话返回后用
  单线程 executor 异步调用，所以页面表现为「离线加工」。
- 本地小模型（如 gemma-4-e4b）推理很慢：一轮对话回复约 1–2 分钟，3 次 LLM 的长期记忆抽取约 6 分钟，
  图抽取还会更久。真实部署请换更快的模型；页面会轮询显示加工进度。
- 重启后：会话消息（`data/sessions.json`）和长期记忆（SQLite）保留；图记忆在 InMemoryStore，重启后
  为空，需要从对话重建。向量索引也在进程内，重启后语义检索会退化，样例为保持演示简单未做重建。

## 目录

```text
java/
├── README.md                本文件
├── API.md                   前后端 JSON 契约
├── pom.xml                  0.1.16 全配对 + SQLite JDBC + JUnit
├── config/model.example.json 两组模型配置示例（复制为 model.local.json，gitignored）
└── src/
    ├── main/
    │   ├── java/com/openjiuwen/samples/creditmemory/
    │   │   ├── CreditMemoryAssistant.java  main：配置、装配、启动 HTTP
    │   │   ├── config/ModelSettings.java   对话模型 / 记忆模型两组配置
    │   │   ├── service/ChatService.java    对话模型 + 召回上下文
    │   │   ├── service/MemoryService.java  LongTermMemory + GraphMemory + 多路召回 + 异步加工
    │   │   ├── service/Pipeline.java       加工流水线运行态
    │   │   ├── service/SessionStore.java   会话历史（data/sessions.json）
    │   │   └── web/WebServer.java          静态页面 + /api/*
    │   └── resources/web/                  原生 HTML/CSS/JS 页面（无构建）
    └── test/java/                          规则单测 + @Tag("integration") 真实模型测试
```

## 运行

### 1. 准备模型服务

需要两个能力：**对话/记忆抽取的 Chat Completion** 与 **Embedding**。下面以本机 LM Studio 为例：

```bash
# LM Studio 加载一个 chat 模型 + 一个 embedding 模型，OpenAI 兼容端点默认 1234
export API_BASE=http://127.0.0.1:1234/v1
export API_KEY=lm-studio
export CHAT_MODEL=google/gemma-4-e4b
export MEMORY_MODEL=google/gemma-4-e4b
export EMBED_API_BASE=http://127.0.0.1:1234/v1/embeddings
export EMBED_MODEL=text-embedding-nomic-embed-text-v1.5
```

也可以用任意远程 OpenAI 兼容服务，例如：

```bash
export API_BASE=https://api.deepseek.com
export API_KEY=sk-...
export CHAT_MODEL=deepseek-chat
export MEMORY_MODEL=deepseek-chat
export EMBED_API_BASE=https://api.openai.com/v1/embeddings
export EMBED_API_KEY=sk-...
export EMBED_MODEL=text-embedding-3-small
```

页面右上角「模型设置」可以分别修改**对话模型**和**记忆模型**（含 Embedding），保存到
`config/model.local.json`；API Key 只存本机、不提交。也可复制：
`cp config/model.example.json config/model.local.json` 再编辑。

### 2. 启动

```bash
cd samples/29_credit_customer_memory/java
mvn -q -DskipTests compile
mvn -q exec:java
# 浏览器打开 http://127.0.0.1:8099/
```

端口可用 `PORT` 环境变量覆盖。首次启动会创建 `data/credit_memory.db` 和 `data/sessions.json`。

### 3. 演示脚本（真实抽取 / 召回，不是造数）

**准备**：先按上面的模型配置启动；建议清掉本机运行时数据，从「空记忆」开始观察：

```bash
rm -rf data/          # 只清本机运行数据，不会删除代码
mvn -q exec:java
```

打开页面后，右侧长期记忆是空的、图记忆是空的。这个空状态很重要：下面的记忆**不是仓库里预置的**。

**三步演示**

| 步骤 | 运行者输入（原样复制） | 预期记忆/召回变化（语义级） |
|---|---|---|
| 1 | 「我想申请经营贷。我是周砚清，在杭州经营精密制造公司，想贷80万分36期，偏好等额本息，计划5年内提前结清；2023年有过一次信用卡逾期但已结清，不接受先息后本。」 | 助手直接回应业务，不复述「我记下了」。加工流水线出现；完成后长期记忆出现用户画像/语义/情景/摘要；变量出现 `loanAmount=80万`、`loanTerm=36期`、`plannedYears=5年内`、`repaymentPreference=等额本息，不接受先息后本`；图实体出现周砚清/公司/逾期事件，关系出现「经营」「经历」等；情节出现 1 条。 |
| 2 | 点「＋」开新会话，输入「我们上次聊到哪了？我的还款方式是什么？」 | 回答前先多路召回；右侧「本轮召回」同时出现**向量 / KV / 图记忆**来源；助手自然概括「80万、36期、等额本息、不接受先息后本」，不会提“记忆库/召回/条目”等内部术语。 |
| 3 | 「计划改成3年内提前结清，其他不变。」 | 变量 `plannedYears` 从 `5年内` 改为 `3年`；追问本轮再次出现三路召回；图记忆可能新增计划实体/关系，情节增加；加工流水线记录本轮新增。 |
| 4（可选） | 重启服务后再问同上问题 | 会话与长期记忆仍在 SQLite；向量索引和 `InMemoryGraphStore` 是进程内的，重启后图召回/语义召回会变少。这是方案 B 的已知限制，不是造数或清空记忆。 |

**本次真实运行记录（2026-11-12，DeepSeek + LM Studio Embedding）**

第 1 步实际回复（节选）：

> 周总您好，我是您的信贷顾问。您的情况我大致了解了：杭州精密制造企业，融资80万、36期、等额本息，
> 计划5年内提前结清，不接受先息后本。先跟您确认几个关键点，方便我判断方案：……

第 1 步加工完成后（`GET /api/sessions/{id}/state` 实测）：

- 长期记忆 8 条：用户画像 4 / 语义 1 / 情景 2 / 摘要 1；
- 变量 4 个：`80万`、`36期`、`5年内`、`等额本息，不接受先息后本`；
- 图记忆：实体 3 个（周砚清、杭州精密制造公司、2023年信用卡逾期事件）、关系 2 条、情节 1 条；
- 流水线最近一次：`llmCalls=4`、`memoriesAdded=12`、图实体 +3、关系 +2、情节 +1。

第 2 步实际回复（节选）：

> 上次咱们聊的是经营贷方案，金额80万、期限36期。还款方式按你的偏好走等额本息，
> 先息后本那种你不接受，就不考虑了。要不要我接着把等额本息下的月供给你算一下？

第 2 步实际召回：`vector=6 / kv=3 / graph=3`，融合条目包括
`向量：用户偏好等额本息`、`KV：loanAmount=80万`、`图：周砚清`、`KV：loanTerm=36期` 等；
加工后长期记忆 9 条、图实体 6 个、关系 4 条、情节 2 条。

第 3 步实际回复（节选）：

> 明白，提前结清时间从5年调整为3年，其他条件不变。按80万、36期等额本息来算，月供大概在2.4万左右；
> 3年内结清的话，总利息会比原计划少不少。要不要我按3年结清给你做一版更精确的还款测算？

第 3 步实际召回：`vector=7 / kv=3 / graph=3`；变量 `plannedYears` 已变为 `3年`；
长期记忆 10 条、图实体 7 个、关系 4 条、情节 3 条；本轮流水线 `memoriesAdded=2`、图实体 +1、情节 +1。

> 说明：上述条数是本次真实运行值；换模型、换措辞、重跑都会有小幅波动（LLM 抽取不是固定模板）。
> **语义判断是稳定的**：金额、期数、还款偏好、结清计划、逾期事件、客户与公司关系会被抽取并跨会话召回。

**怎么验证不是造数**

1. **初始为空**：`rm -rf data/` 后首次打开，长期记忆/图记忆都是空列表；只有真正发消息并等流水线完成后才出现内容。
2. **代码里没有记忆文案**：
   ```bash
   grep -R "用户的名字是周砚清\|loanAmount=80万" src/main/java || echo "没有预置记忆"
   ```
   记忆内容是 `LongTermMemory.addMessages(..., gen_mem=true)` 内部调用 LLM 从对话中抽取的，不是 sample 写死的。
3. **看加工输入/输出**：右侧「加工流程」tab 的「这次加工」里，`输入` 是最近真实对话，`产出` 是对应的长期记忆/图对象；
   这些字段来自 `MemoryService.process()` 调用 `addMessages` / `addMemory` 之后的结果。
4. **看 SQLite 落盘**：长期记忆在 `data/credit_memory.db`；可用 SQLite 工具打开看 `memory_meta` 等表，重启服务后内容仍在。
5. **自动化证据**：`RealModelIntegrationTest` 使用真实模型、临时空目录，断言抽取后长期记忆和实体非空；
   `mvn test` 规则单测不依赖模型。原型 `prototype/` 是只读 mock，**Java 页面全部走真实 `/api/*`**。

**回答风格**：`ChatService` 的 system prompt 明确要求不要提“我记下了/已记录/已记住/系统已保存”等记忆动作，
不要复述客户刚提供的全部信息。上面的三步实测回复已不再出现这些句子；若换模型后仍出现，可在「模型设置」换模型
或加强该 prompt。

## 页面截图（真实 Java 后端 + DeepSeek）

| 截图 | 看什么 |
|---|---|
| [longterm.png](screenshots/longterm.png) | 长期记忆 + 多路召回（向量 6 / KV 3 / 图 3） |
| [graph-tab.png](screenshots/graph-tab.png) | 图记忆实体/关系连线/情节可视化（InMemoryGraphStore） |
| [pipeline-tab.png](screenshots/pipeline-tab.png) | 加工流水线：stages/stats/输入上下文→产出 |
| [settings.png](screenshots/settings.png) | 对话模型 + 记忆模型两组配置 |

## 验收

规则单测（不调模型）：

```bash
mvn -q test
```

真实模型集成测试（需要上面的模型服务）：

```bash
mvn -q -Preal-model -Dtest=RealModelIntegrationTest test
```

测试内容：对话模型返回非空；`LongTermMemory` 抽取后长期记忆列表非空；`GraphMemory` 抽取后实体列表非空；
多路召回可执行。真实运行输出见下节。

## 代码要点

- **两组模型配置**：`ModelSettings.chat` 用于回答，`ModelSettings.memory` 用于记忆抽取与 Embedding；
  `POST /api/settings` 更新后 `ChatService.reload` + `MemoryService.updateSettings` 重建。
- **记忆初始化**：`LongTermMemory.registerStore(SqliteKVStore, InMemoryVectorStore, DefaultDbStore, APIEmbedding)`
  + `setConfig(MemoryEngineConfig)` + `setScopeConfig("credit_advisor", MemoryScopeConfig)`。
- **图记忆**：`GraphConfig(backend=in_memory)` + `GraphMemory`；`GraphMemUpdate` 的 added/updated 实体/关系/情节
  累积成页面用的图快照；召回走 `GraphMemory.search`。`GraphRelationCompatModel` 临时修关系字段名，
  效果与风险见上。
- **多路召回**：`searchUserMem`（向量 + KV 词面兜底）、`searchUserHistorySummary`、`getVariables`、
  `GraphMemory.search` 各自召回，宿主层按来源交错融合（最多 6 条）。
- **离线加工**：对话返回后 `executor` 异步跑 LTM + GraphMemory；`Pipeline.Run` 更新阶段/统计/样本供页面轮询。
- **会话**：`SessionStore` 持久化到 `data/sessions.json`，长期记忆跨会话共享；历史会话只读展示。

## 注意点

- 必须保持 `agent-core-java` 与 `agent-core-memory-java` **同为 0.1.16**；混用 0.1.17 core 会
  `NoSuchMethodError`（详见 `../research/RESEARCH-MEMORY.md`）。
- `GraphMemory` 在 0.1.16 只有 `InMemoryGraphStore`，Milvus 后端不存在；方案 B 下重启会丢图数据。
- 0.1.16 图关系抽取有已知 prompt/parser 字段不匹配；样例用 `GraphRelationCompatModel` 临时适配，
  不应把该适配器当成框架能力。上游修复后请删除。
- 向量索引 `InMemoryVectorStore` 不持久化，重启后语义搜索会变弱；长期记忆原文仍在 SQLite。
- 本地推理模型输出可能带 `reasoning_content`；本项目把 maxTokens 设为 2048，若仍为空请换模型或调大。
- 不要提交 `config/model.local.json`、`data/`、`logs/`、`target/`。
