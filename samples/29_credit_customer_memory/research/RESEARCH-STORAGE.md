# 信贷客户经理记忆助手 · 本地 SQLite 存储栈调研

> 结论：**「agent-core-java 0.1.16 + agent-core-memory-java 0.1.16 全配对 + 本地 SQLite」实测可行，推荐作为样例方案**；
> core 0.1.17 + memory 0.1.16 编译/运行均不兼容（不可用）；只升级 core 0.1.17（无 memory 引擎）可用 `SimpleMemoryIndex` 记忆底座，但没有消息/变量/LLM 抽取能力。
> SQLite 下必须用 `SqliteKVStore`（0.1.16）或 0.1.17 的 `DbBasedKVStore`，**不能用 0.1.16 的 `DbBasedKVStore`**（写的是 H2 `MERGE INTO`，SQLite 直接语法错误）。

- 调研日期：2026-11-11；JDK `amazon-corretto-17.0.21`；Maven 3.9.9；`settings.xml` 的 localRepository 指向 `.cache/m2repo`。
- probe 源码（gitignored，非交付物）：`.cache/mem-probe/`
  - 方案一（0.1.16 全配对）：`.cache/mem-probe/pom.xml` + `src/main/java/probe/`（`ProbeMain`/`FakeEmbedding`/`FakeState`/`FakeSession`）
  - 方案二（仅 core 0.1.17）：`.cache/mem-probe/core17/`
  - 不兼容复现：`.cache/mem-probe/compat17/`（core 0.1.17 + memory 0.1.16，故意编译失败）
- 证据日志：`.cache/mem-probe/{badkv,write,restart,core17-write,core17-restart,core17-checkpoint,runtime17b}.log`；SQLite 数据：`.cache/mem-probe/data/credit_memory.db`。

## 1. 方案一（推荐）：core 0.1.16 + memory 0.1.16 + SQLite

### 1.1 依赖解析与最小 pom

```xml
<dependency><groupId>com.openjiuwen</groupId><artifactId>agent-core-java</artifactId><version>0.1.16</version></dependency>
<dependency><groupId>com.openjiuwen</groupId><artifactId>agent-core-memory-java</artifactId><version>0.1.16</version></dependency>
```
- `agent-core-java:0.1.16` 的 pom **compile scope 自带 sqlite-jdbc 3.53.2.1 传递依赖**（实测 `dependency:tree`：`+- org.xerial:sqlite-jdbc:jar:3.53.2.1:compile`）；memory pom 里它是 test scope。建议样例仍显式声明 sqlite-jdbc 固定版本。
- 运行期还需 sqlite-jdbc 的 native lib，Maven 会自动解包，无需额外安装。

### 1.2 层 → SDK 组件 → SQLite 对象 → 依赖

| 记忆层 / 能力 | SDK 组件（0.1.16） | SQLite 对象（单文件 `credit_memory.db`） | 键前缀 / 表 |
|---|---|---|---|
| 会话消息 | `LongTermMemory.addMessages(...,genMem=false)` / `getRecentMessages` → `MessageManager` | 表 `user_message`（行） | `message_id` 主键；列 user_id/scope_id/content/session_id/role/timestamp |
| 用户-范围映射 | `registerStore`→`DbModel.createTables` + `ScopeUserMappingManager` | 表 `scope_user_mapping` | `(user_id,scope_id)` 主键 |
| 迁移版本 | `DbModel.createTables` + `memory_meta` | 表 `memory_meta` | 当前迁移注册表为空，建表但 0 行 |
| 变量记忆 | `updateVariables`/`getVariables` → `VariableManager` | 表 `checkpointer_kv_store` | `user_var/<user>/<scope>/<name>`；会话变量 `session_var/…/<session>/<name>`；删除标记 `deleted_user_var/…` |
| 结构化记忆（user_profile / semantic / episodic / summary） | `WriteManager` → `FragmentMemoryManager`/`SummaryManager`（由 `addMessages(genMem=true)` 经 LLM 抽取后调用） | 表 `checkpointer_kv_store` | 记忆 JSON：`UMD/<user>/<scope>/<memId>`；ID 索引：`UMD/<user>/<scope>/ids`、`UMD/<user>/<scope>/<memType>/ids`、`UMD/<user>/<scope>/UPT/ids` |
| 语义向量检索 | memory 内部 `SemanticStore` + core `InMemoryVectorStore` | **不落盘**（进程内集合 `uid_<user>_gid_<scope>_mtype_<memType>`） | 重启丢失，需重建（见 1.5） |
| 会话/工作流 checkpoint | `PersistenceCheckpointer(kvStore)` 或 `CheckpointerFactory.create("persistence",{db_type:sqlite,db_path})` | 表 `checkpointer_kv_store`（可与记忆 KV 同实例同文件） | `<sessionId>:agent:<agentId>:agent_state_blobs(_dump_type)`；`<sessionId>:workflow-graph:<wfId>:checkpoint_data_value/_data_type` |
| 内部分布式锁 | `DistributedLock` | 同 KV 表 | `_lock/user/<userId>`（短生命周期，释放即删） |
| scope 配置 | `setScopeConfig` | 同 KV 表 | `memory_scope_config/<scopeId>` |

必需依赖：**只需 SQLite JDBC，无 Redis/Milvus/Chroma/Postgres 服务**。语义向量走进程内 `InMemoryVectorStore`（重启重建），关系/消息/KV/checkpoint 全部落在同一个本地 SQLite 文件。

### 1.3 建表、DDL 与幂等（实测通过）

`registerStore(new SqliteKVStore(db, 30, true), new InMemoryVectorStore(), new DefaultDbStore("jdbc:sqlite:"+db), embedding)`（签名顺序：kv, vector, dbStore, embedding）
- `registerStore` 连续调用两次均成功（`[WRITE] registerStore #2 OK (幂等)`）；`CREATE TABLE IF NOT EXISTS`，无 DDL/迁移报错。
- 独立 `sqlite3` CLI 核验（`sqlite3 .cache/mem-probe/data/credit_memory.db ".tables"`）：
  `checkpointer_kv_store  memory_meta  scope_user_mapping  user_message`
- 行数：`user_message=2`、`scope_user_mapping=1`、`memory_meta=0`、`checkpointer_kv_store=11`；文件 36864 bytes。
- 实际 DDL（与 memory 0.1.16 源码 `DbModel` 一致；0.1.17 core 不含这些记忆表建表器）：
  - `user_message(message_id VARCHAR(64) PRIMARY KEY, user_id VARCHAR(256) NOT NULL, scope_id VARCHAR(128) NOT NULL, content VARCHAR(4096) NOT NULL, session_id VARCHAR(2048), role VARCHAR(32), timestamp VARCHAR(64))`
  - `scope_user_mapping(user_id VARCHAR(64) NOT NULL, scope_id VARCHAR(64) NOT NULL, PRIMARY KEY(user_id,scope_id))`
  - `memory_meta(table_name VARCHAR(64) PRIMARY KEY, schema_version VARCHAR(64) NOT NULL)`
  - `checkpointer_kv_store(k TEXT PRIMARY KEY NOT NULL, v BLOB NOT NULL, value_kind INTEGER NOT NULL, expires_at INTEGER)`（`SqliteKVStore`：`ON CONFLICT DO UPDATE` 幂等写、`busy_timeout=30s`、`journal_mode=WAL`）
- SQLite 兼容性：`VARCHAR(n)` / `CREATE TABLE IF NOT EXISTS` / `LIMIT ?` / `ON CONFLICT` 均被 SQLite 接受；`SqlDbStore` 的 INSERT/SELECT/UPDATE/DELETE 无 H2 专有方言。
- **单文件承载可行**：`DefaultDbStore` 每次操作新建 JDBC 连接，`SqliteKVStore` 独占一条 WAL 连接，二者都与同一个 `.db` 文件配合工作（probe 串行调用实测通过）。checkpointer 直接复用同一个 `SqliteKVStore` 实例（同文件同表）安全，实测 session/graph 数据与记忆 KV 共存。注意：打开期间存在 `-wal`/`-shm` 文件；大量并发写需自行串行化（SQLite 单写者）。

### 1.4 消息 / 变量 / 记忆写入实测

- `addMessages(msgs, null, user, scope, session, now, false, 2)` → `getRecentMessages=2`（user/assistant 两条，落 `user_message`）。**genMem=false 只写消息行，不产生记忆 ID**；记忆抽取需 `genMem=true` + 真实 LLM。
- `updateVariables(Map.of("risk_level","R3","max_drawdown","10%","preferred_product","税贷"))` → `getVariables` 读回三值；实际 KV 键（sqlite3 查询）：
  `user_var/u_chen_mgr/credit_advisor/risk_level`、`…/max_drawdown`、`…/preferred_product`。
- 记忆文档（probe 夹具）：真实链路是 `addMessages(genMem=true)` 用 LLM 抽取后由 `WriteManager` 写；probe 为不调 LLM，用 memory 模块公开类 `FragmentMemoryManager` 写**单条** fragment 记忆（单条+空库分支不触发 LLM），实测产生：
  `UMD/u_chen_mgr/credit_advisor/01a1caa591b55eed8126b6b1` + 三个 `ids` 索引键；`getUserMemByPage`/`userMemTotalNum=1` 可读回。**该夹具只存在于 probe，样例必须走真实 LLM。**
- 语义检索（假 Embedding，8 维字符 hash，仅 probe 夹具）：
  - `SemanticStore.search(query, "uid_u_chen_mgr_gid_credit_advisor_mtype_user_profile", 5)` → 1 hit `@0.8043`；
  - `LongTermMemory.searchUserMem(query,3,...,0.0)` → 1 条 `score=0.9091`（注意：最终分 = max(向量分, `FragmentMemoryManager` 的 KV+字面兜底分)）；
  - `searchUserHistorySummary(...)` → 0（summary 记忆需 LLM 生成，库里本来没有）。

### 1.5 重启语义（进程重启后再检索）

新 JVM 重开同一文件 + 新建空 `InMemoryVectorStore` 后实测：
- `getVariables` 三值仍在；`getRecentMessages=2`；`userMemTotalNum=1`、`getUserMemByPage` 能读到记忆原文；
- 纯向量路径：`SemanticStore.search(...) hits=0`（向量索引随进程丢失，SQLite 里没有任何向量数据）；
- `searchUserMem` 仍返回 1 条 `score=0.9091` —— 因为 0.1.16 的 `FragmentMemoryManager.search` 在向量为空时用 **KV 全量记忆 + 字面相似度** 兜底（`Math.max(vectorScore, lexicalSimilarity)`，字面分下限 0.3），并非向量命中；`searchUserHistorySummary` 仍为 0。
- 重建（最小、只用公开 API）实测成功：`getUserMemByPage` 取回记忆 → `updateMemById` 重新 embedding 并写回向量库：

```java
List<MemInfo> page = ltm.getUserMemByPage(user, scope, 100, 1, MemoryType.UNKNOWN);
for (MemInfo mi : page) ltm.updateMemById(mi.getMemId(), mi.getContent(), user, scope);
```
  实测 1 条记忆：`SemanticStore.search` 从 0 → `@0.8043`；embedding 调用 = 每条记忆 1 次 `embedDocuments(1 条文本)`（重建循环内 documents 调用 +1、texts +1，无逐字符多次调用）。
- probe 假 Embedding 是字符 hash，字面兜底与它是同源信号，所以本用例 `searchUserMem` 分数重建前后都是 0.9091（看不出差别）；真实 embedding 下重建后语义召回才会体现。这是 probe 局限，不是方案问题。

### 1.6 会话/工作流 checkpoint（实测通过）

- `new PersistenceCheckpointer(kvStore)` + 自建最小 `BaseSession`：`postAgentExecute` 写入后 `sessionExists("sess-credit-001")=true`；新 JVM `preAgentExecute` 恢复 `state={step=await_risk_answer, pending_question=请补充客户风险等级}`。
- `graphStore().save(session, "workflow-credit", GraphStoreState.create(..., step=3, ...))` → 新 JVM `graphStore().get(...)` 返回 step=3。
- `CheckpointerFactory.create("persistence", Map.of("db_type","sqlite","db_path", path))` 等价 API 可用（返回 `PersistenceCheckpointer`，独立文件，`sessionExists(unknown)=false`）。0.1.16 没有 0.1.17 的 `PersistenceCheckpointer.createFromConfig`（package-private）；走 provider 工厂即可。
- 会话恢复骨架：`Checkpointer cp = CheckpointerFactory.create("persistence", {db_type:sqlite, db_path})` → 交给 Agent/Workflow 运行器；中断/恢复时框架内部调 `postAgentExecute`/`preAgentExecute`，业务代码只需把同一个 checkpointer 传给 Runner。

### 1.7 重要坑：0.1.16 `DbBasedKVStore` 在 SQLite 上不可用

按任务要求实测 `new DbBasedKVStore(defaultDbStore)` 传给 `registerStore`：
- `registerStore` + `setConfig` **能过**（该阶段不写 KV）；
- 之后任何写操作（`updateVariables`、锁、记忆写）抛：
  `java.lang.IllegalStateException: Failed to set KV value ← org.sqlite.SQLiteException: [SQLITE_ERROR] SQL error or missing database (near "MERGE": syntax error)`
  根因：0.1.16 `DbBasedKVStore` 用 H2 方言 `MERGE INTO kv_store (k,v) KEY(k) VALUES (?,?)`；表名 `kv_store` 由它自建。
- **规避**：SQLite 后端改用 `new SqliteKVStore(dbPath, timeoutSec, walEnabled)`（0.1.14+，SQLite 原生 `ON CONFLICT`、WAL、busy_timeout；表 `checkpointer_kv_store`）。样例不得用 `DbBasedKVStore` 配 SQLite。

## 2. 方案二（降级）：仅 agent-core-java 0.1.17（无 memory 引擎）

在 `.cache/mem-probe/core17/` 实跑通过（core 0.1.17 + sqlite-jdbc，无 memory jar）：
- 底座：`SimpleMemoryIndex` + `MemoryDoc` + `DbBasedKVStore(org.sqlite.SQLiteDataSource)` + `core.retrieval.vector_store.InMemoryVectorStore` + 异步 `Embedding`。
- **0.1.17 的 `DbBasedKVStore` 可以配 SQLite**（已重写为通用 SQL：`UPDATE` 失败再 `INSERT`，表 `kv_store(key VARCHAR(255) PRIMARY KEY, value VARCHAR(4096))`），与 0.1.16 同名类不同实现。
- `MemoryDoc(type=user_profile|semantic_memory|episodic_memory|summary)` 四类写入 + 检索实测命中 4 条：`user_profile@0.9057, semantic_memory@0.8542, summary@0.7369, episodic_memory@0.7208`；`listMemories=4`、`getById` 正常。
- 重启后：KV（`UMD/u_chen_mgr/credit_advisor/<id>` + `…/<memType>/ids`）仍在，`listMemories=4`；向量检索 0；`listMemories` 拿回 doc 再 `addMemories` 重建（按 4 个 memType 各 1 次 `embedDocuments`，共 4 条文本）后恢复同样 4 条命中。
- checkpoint：`CheckpointerFactory.create("persistence", Map.of("db_type","sqlite","db_path", path))` → `PersistenceCheckpointer` over `DbBasedKVStore`；`kvStore().set/get`、`graphStore().save/get step=3`、关闭后重开数据仍在；表 `kv_store`，键 `<sessionId>:workflow-graph:<wfId>:checkpoint_data_value/_data_type`。
- **能力缺口**：没有 `LongTermMemory` 的 `addMessages`/消息表/`scope_user_mapping`/变量层/LLM 抽取/summary 生成；只有「分层 KV + 向量检索 + checkpoint」底座。若样例严格锁 core 0.1.17，需要在业务代码里自己调 LLM 抽取并构造 `MemoryDoc`（工作量与合规风险都高，不推荐）。

## 3. core 0.1.17 + memory 0.1.16：不可行（证据链）

1. 依赖解析（`.cache/mem-probe/compat17`，core 直接声明 0.1.17）：`dependency:tree` 显示 `agent-core-java:0.1.17:compile` + `agent-core-memory-java:0.1.16:compile`，core 被正确固定为 0.1.17。
2. `mvn compile` 失败：`Compat17Probe.java:[82,30] 不兼容的类型: com.openjiuwen.core.foundation.store.kv.DbBasedKVStore无法转换为com.openjiuwen.spi.store.BaseKVStore`。根因：0.1.17 把 store 基类从 `com.openjiuwen.spi.store.*`（同步）迁到 `com.openjiuwen.core.foundation.store.*`（异步 CompletableFuture），memory 0.1.16 的 `registerStore` 仍要求旧 SPI 类型。
3. 强行运行期覆盖（classpath 用 0.1.17 core 替换 0.1.16 core，跑 0.1.16 编译产物）：
   - 首次（probe 假 Embedding 实现 0.1.16 的 `Embedding` interface）→ `IncompatibleClassChangeError: class probe.FakeEmbedding can not implement com.openjiuwen.core.retrieval.embedding.Embedding, because it is not an interface`（0.1.17 已把该 interface 改成抽象类）；
   - 改用官方 `HashEmbedding` 避开夹具后 → 在 `registerStore` 调用点 `VerifyError: Bad type on operand stack ... Type 'com/openjiuwen/core/foundation/store/db/DefaultDbStore' is not assignable to 'com/openjiuwen/spi/store/BaseDbStore'`（0.1.17 的 DefaultDbStore 已不实现旧 SPI 基类）。
4. 静态分析交叉验证（memory-api 队友）：memory 0.1.16 还引用 core 0.1.16 的 `Model.invoke(...)`、`JsonOutputParser.parse(Object)`、`AgentMemoryConfig.isMemoryTypeEnabled(String)`、`BaseMessage$Builder` 等等旧签名，0.1.17 已删除/改签名，21 个消费类/92 处 unresolved；`addMessages` 等主路径必然 `NoSuchMethodError`。
5. 结论：**不要混用**。要么全 0.1.16（方案一），要么只用 0.1.17 底座（方案二）；memory 0.1.17 目前未发布（配置的 aliyun 镜像仓库无该 artifact，`dependency:get` 失败；如需再确认可直连 Maven Central 复查）。

## 4. 实测通过 / 未通过清单

| 项 | 方案一 0.1.16 | 方案二 0.1.17 core-only |
|---|---|---|
| 依赖解析 / 编译 | ✅ | ✅ |
| SQLite 关系表创建（user_message 等） | ✅（幂等） | ➖ 无该层 |
| DbBasedKVStore + SQLite | ❌ H2 MERGE 语法错误 | ✅（通用 SQL） |
| SqliteKVStore + SQLite 读写 | ✅ | ➖ 组件不存在 |
| addMessages(genMem=false) → 消息落表 | ✅ | ➖ 无该 API |
| updateVariables/getVariables 落 KV | ✅ 重启可读 | ➖ 无该 API |
| 假 Embedding 记忆写入 + searchUserMem 命中 | ✅ 0.9091（向量 0.8043） | ✅ 四类 MemoryDoc 命中 4 条 |
| 重启持久化（KV/消息/记忆原文） | ✅ | ✅ |
| 重启后向量索引 | ❌ 丢失（预期，见重建） | ❌ 丢失（预期，可重建） |
| 向量索引重建 | ✅ getUserMemByPage+updateMemById | ✅ listMemories+addMemories |
| PersistenceCheckpointer + SQLite | ✅ agent/graph 均恢复 | ✅ graph + KV 恢复 |
| genMem=true LLM 抽取 | ⚠️ 未测（需真实 LLM） | ➖ 不存在 |
| 真实 Embedding / 真实模型 | ⚠️ 未测（probe 用假 Embedding） | ⚠️ 未测 |

## 5. 对 29 样例的落地建议

1. **版本**：样例 pom 锁 `agent-core-java:0.1.16` + `agent-core-memory-java:0.1.16`，并在 README 顶部标注「memory 0.1.17 未发布，0.1.17 core 与 memory 0.1.16 二进制不兼容，故锁定 0.1.16 全配对」。
2. **存储**：一个本地文件（如 `data/credit_memory.db`）+ `DefaultDbStore("jdbc:sqlite:…")` + `SqliteKVStore(path, 30, true)` + `InMemoryVectorStore`；checkpointer 复用同一个 `SqliteKVStore` 实例。**禁止** `DbBasedKVStore`+SQLite。
3. **必须真实模型**：`addMessages` 记忆抽取与 summary 需要 API_BASE/API_KEY/MODEL（`MemoryEngineConfig.defaultModelCfg/defaultModelClientCfg`）；检索需要真实 Embedding（`MemoryScopeConfig.embeddingCfg`），probe 的假 Embedding 只用于调研。若离线演示可退而用官方 `HashEmbedding`（确定性，但语义质量差），README 需注明。
4. **启动重建**：进程启动时若检测到「KV 里有记忆、向量库为空」，用 `getUserMemByPage` + `updateMemById` 重建；代价 = 每条记忆 1 次 embedding 批调用。
5. **页面/README 展示**：按第 1.2 节迁移「层 → 表 → 键前缀」表格直接展示给开发者；强调语义向量不落 SQLite 是 SDK 0.1.16 的设计（`InMemoryVectorStore`）。

## 6. 复跑命令（工作目录=仓库根）

```bash
export JAVA_HOME=$PWD/.cache/tools/amazon-corretto-17.jdk/Contents/Home
MVN=.cache/apache-maven-3.9.9/bin/mvn
S="-s .cache/maven-settings.xml"

# 方案一：编译 + 跑两阶段（write 会重建 db 文件）
$MVN -q $S -f .cache/mem-probe/pom.xml -DskipTests compile
$MVN -q $S -f .cache/mem-probe/pom.xml dependency:build-classpath -Dmdep.outputFile=cp.txt
CP=".cache/mem-probe/target/classes:$(cat .cache/mem-probe/cp.txt)"
$JAVA_HOME/bin/java -cp "$CP" probe.ProbeMain write   .cache/mem-probe/data
$JAVA_HOME/bin/java -cp "$CP" probe.ProbeMain restart .cache/mem-probe/data
$JAVA_HOME/bin/java -cp "$CP" probe.ProbeMain badkv   .cache/mem-probe/data    # H2 MERGE → SQLite 报错复现
sqlite3 .cache/mem-probe/data/credit_memory.db ".tables"          # 独立核验表
sqlite3 .cache/mem-probe/data/credit_memory.db "SELECT k FROM checkpointer_kv_store ORDER BY k;"

# 方案二：仅 core 0.1.17
$MVN -q $S -f .cache/mem-probe/core17/pom.xml -DskipTests compile
$MVN -q $S -f .cache/mem-probe/core17/pom.xml dependency:build-classpath -Dmdep.outputFile=cp.txt
CP17=".cache/mem-probe/core17/target/classes:$(cat .cache/mem-probe/core17/cp.txt)"
$JAVA_HOME/bin/java -cp "$CP17" core17.CoreOnlyProbe write      .cache/mem-probe/data17
$JAVA_HOME/bin/java -cp "$CP17" core17.CoreOnlyProbe restart    .cache/mem-probe/data17
$JAVA_HOME/bin/java -cp "$CP17" core17.CoreOnlyProbe checkpoint .cache/mem-probe/data17

# 不兼容复现（预期 BUILD FAILURE）
$MVN -s $S -f .cache/mem-probe/compat17/pom.xml dependency:tree   # core=0.1.17 + memory=0.1.16
$MVN -s $S -f .cache/mem-probe/compat17/pom.xml -DskipTests compile  # 预期类型不兼容错误
```
