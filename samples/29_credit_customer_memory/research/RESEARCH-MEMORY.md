# RESEARCH-MEMORY · Java SDK 记忆能力事实清单（信贷客户经理记忆助手）

> **一句话结论：0.1.17 release 的 Java SDK 尚未发布 memory 模块；完整分层记忆必须 `agent-core-java:0.1.16 + agent-core-memory-java:0.1.16` 全配对（storage probe 已在 SQLite 上实测可跑），或等 memory 0.1.17 发布 / 用源码构建。`core 0.1.17 + memory 0.1.16` 可以编译，但运行时二进制不兼容（23 个类、119 处符号无法解析），首个 `addMessages(genMem=true)` 即 `NoSuchMethodError`。**

给原型 / README 的固定口径：
> 本样例依赖 `com.openjiuwen:agent-core-java:0.1.16` + `com.openjiuwen:agent-core-memory-java:0.1.16`（成对锁定）。0.1.17 core 虽已发布，但 memory 模块没有 0.1.17；混用会在 LLM 记忆抽取（`Model.invoke`/`JsonOutputParser`）与历史消息读取（`BaseMessage.builder()`）处抛 `NoSuchMethodError`。

标注约定：**【事实】** = jar/源码/字节码直接核实；**【推断】** = 由事实推导、未运行；**【风险】** = 运行时踩坑点，待 §9-10 的运行 probe 复核。

## 1. 依赖 / 模块矩阵【事实】

| 制品 | Maven Central 最新 | 记忆相关类（jar 内实际存在） |
| --- | --- | --- |
| `com.openjiuwen:agent-core-java` | **0.1.17** | 记忆底座：`core.context.context.SessionMemoryManager/SessionMemoryConfig`、`core.session.checkpointer.PersistenceCheckpointer`、`spi.store.*`(BaseKVStore/BaseDbStore/BaseVectorStore)、`core.foundation.store.*`(`SimpleMemoryIndex`/`MemoryDoc`/`BaseMessageStore`/`DbBasedKVStore`/`InMemoryVectorStore`/`Embedding`)、`retrieval.embedding.APIEmbedding/HashEmbedding` |
| `com.openjiuwen:agent-core-memory-java` | **0.1.16** | 分层记忆引擎：`com.openjiuwen.memory.LongTermMemory`、`config.MemoryEngineConfig/MemoryScopeConfig`、`manage.model.MemoryType`、`MemInfo/MemResult`、`integration.agent.OpenJiuwenMemoryRuntime` |
| `com.openjiuwen:agent-core-java` | 0.1.16 | 0.1.16 配套 core：**无** `SimpleMemoryIndex/MemoryDoc/BaseMessageStore`（三者 0.1.17 新增）；含 memory 0.1.16 需要的全部 SPI 底座（见 §8） |

`com.openjiuwen:agent-core-java:0.1.17` 的 jar **不含** `com/openjiuwen/memory/*`（`unzip -l | grep '^com/openjiuwen/memory'` 为空）。

**样例 pom（唯一能跑通完整分层记忆的组合）**：

```xml
<dependency><groupId>com.openjiuwen</groupId><artifactId>agent-core-java</artifactId><version>0.1.16</version></dependency>
<dependency><groupId>com.openjiuwen</groupId><artifactId>agent-core-memory-java</artifactId><version>0.1.16</version></dependency>
<dependency><groupId>org.xerial</groupId><artifactId>sqlite-jdbc</artifactId><version>3.53.2.1</version></dependency>
```

memory 0.1.16 的 pom 把 core 依赖写成 `${project.version}`=0.1.16，并自带 `jackson-databind`、`bcprov-jdk18on`；`sqlite-jdbc` 在 memory pom 里是 test scope（core 0.1.16 pom 会 compile 传递 3.53.2.1，storage probe 实测），样例仍应显式固定版本。**不要**把 core 单独提到 0.1.17：Maven「最近版本优先」会选 0.1.17，直接落入混用坑。

## 2. 混用（core 0.1.17 + memory 0.1.16）为什么必崩【事实】

字节码交叉校验（对 memory 0.1.16 jar 全部 195 个类逐符号解析，命令见 §10）：对 **core 0.1.16 失败 0 处**；对 **core 0.1.17 失败 23 个消费类 / 119 处符号**。关键符号：

| memory 0.1.16 编译期符号 | core 0.1.17 现状 | 触发点 |
| --- | --- | --- |
| `Model.invoke(Object,Object,Float,Float,String,Integer,String,BaseOutputParser,Float,Map)`（同步） | 改为 `invoke(List,List,Float,Float,Integer,String,String,BaseOutputParser,Float,Map)`（异步，`CompletionStage`） | `MemoryAnalyzer.java:131`、`LongTermMemoryExtractor.java:90`、`MemUpdateChecker.java:99` → `addMessages(genMem=true)` 必崩 |
| `JsonOutputParser.parse(Object)→Object` | 返回 `CompletableFuture` | 同上三处 |
| `AgentMemoryConfig.isMemoryTypeEnabled(String)`（`core.application.schema`） | 方法删除；0.1.17 该子类无方法，父类只有 `isEnableFragmentMemory()` | `Generator.java:219` |
| `BaseMessage.builder()→BaseMessage$Builder`（以及 `UserMessage$Builder`/`SystemMessage$Builder`/`AssistantMessage$Builder`/`PromptTemplate$Builder`） | 嵌套 builder 改名 `$BaseMessageBuilder` 等 | `MessageManager.java:124,175`（读历史消息）、`PromptApplier` |
| `retrieval.embedding.Embedding`（接口、同步 `List embedQuery`） | 变 abstract class、方法返回 `CompletableFuture` | `SemanticStore` 向量读写 |
| `retrieval.embedding.APIEmbedding(retrieval.common.EmbeddingConfig)` | 0.1.17 构造参数是 `foundation.store.EmbeddingConfig` | `LongTermMemory.getScopeEmbeddingModel` |
| `BaseMessage(String,String)` | 0.1.17 只有 `(String,Object)`/`(String,String,String)` | `MemoryWriteInput`；`OpenJiuwenMemoryProvider` 另引用已删除的 `AgentMemoryConfig$AgentMemoryConfigBuilder` |

**运行时反射 probe**（同一个 classpath 分别换 core jar，输出原文）：

| 探测项 | core 0.1.16 | core 0.1.17 |
| --- | --- | --- |
| `BaseMessage.builder()` 返回类型 | `BaseMessage$Builder` | `BaseMessage$BaseMessageBuilder` |
| `AgentMemoryConfig.isMemoryTypeEnabled(String)` | PRESENT | MISSING（`NoSuchMethodException`） |
| `Model.invoke(旧 10 参)` | PRESENT（同步、`throws Exception`） | MISSING |
| `JsonOutputParser.parse(Object)` 返回 | `Object` | `CompletableFuture` |

`NoSuchMethodError` 是 `Error`：`addMessages` 只 `try/catch` 了 `writeManager.addMemories`（`LongTermMemory.java:471`），抽取阶段的崩会原样抛给调用方。

## 3. 0.1.16 全配对：LongTermMemory 生命周期【事实】

| API（0.1.16 jar 精确签名） | 语义 / 约束（源码行号指 memory 0.1.16 sources jar） |
| --- | --- |
| `getInstance()` / `resetInstance()` | 全 JVM 单例（`LongTermMemory.java:162/178`）；`resetInstance` 仅测试用，会 close 所有 scope embedding |
| `registerStore(BaseKVStore, BaseVectorStore, BaseDbStore<?>, Embedding)` | **4 参**；`kvStore` 必填（null → `MEMORY_REGISTER_STORE_EXECUTION_ERROR`）；vector/db 可传 null **但** 方法末尾自动执行 `setConfig(new MemoryEngineConfig())`，而 `setConfig` 要求 kv+db+vector **三者都非 null**（`:198,234`）→ 实际三者都必须注册；`Embedding` 可 null |
| `setConfig(MemoryEngineConfig)` | 必须在 registerStore 之后；`cryptoKey` 必须为空或 32 字节；只有 `defaultModelCfg` 与 `defaultModelClientCfg` **同时非空**才创建 `baseLlm`（`:267`）；会重建全部 manager/generator |
| `setScopeConfig(String, MemoryScopeConfig)` | scopeId 必须非空、不含 `/`、≤128（`:1105`）；配置（含 `model_cfg/model_client_cfg/embedding_cfg`）序列化后写 KV key `memory_scope_config/<scopeId>`，api_key 加密；失败返回 `false` 不抛 |
| `setConfig` 之外的 scope 级配置 | `MemoryScopeConfig` 只有 3 个字段：`modelCfg`、`modelClientCfg`、`embeddingCfg`（类型 `core.retrieval.common.EmbeddingConfig`）；没有 prompt 自定义字段 |

最小接线（均为 0.1.16 jar 已核实类型；构造器/Builder 已 javap）：

```java
BaseKVStore kv   = KVStoreFactory.create("sqlite",                    // SQLite 必须用 SqliteKVStore；0.1.16 的 DbBasedKVStore 是 H2 方言（§9.10）
        Map.of("db_path", "data/memory.db", "db_timeout", 30, "db_enable_wal", true));
BaseVectorStore vector = VectorStoreFactory.create("memory");           // = foundation.store.InMemoryVectorStore
BaseDbStore<?> db = new DefaultDbStore("jdbc:sqlite:data/memory.db");   // 需 sqlite-jdbc
Embedding embed = new HashEmbedding();                                  // 本地确定向量，无网络依赖
LongTermMemory mem = LongTermMemory.getInstance();
mem.registerStore(kv, vector, db, embed);
mem.setConfig(MemoryEngineConfig.builder()
    .defaultModelCfg(ModelRequestConfig.builder().modelName(MODEL).temperature(0.2).build())
    .defaultModelClientCfg(ModelClientConfig.builder()
        .apiKey(API_KEY).apiBase(API_BASE).clientProvider("openai").build())
    .inputMsgMaxLen(8192).singleTurnHistorySummaryMaxToken(128).build());
mem.setScopeConfig("credit_memory", MemoryScopeConfig.builder()   // 可选；不设则用上面的默认 LLM/embedding
    .modelCfg(ModelRequestConfig.builder().modelName(MODEL).build())
    .modelClientCfg(ModelClientConfig.builder().apiKey(API_KEY).apiBase(API_BASE).clientProvider("openai").build())
    .embeddingCfg(new EmbeddingConfig("text-embedding-3-small", EMB_BASE, EMB_KEY)).build());
```

## 4. 分层语义：AgentMemoryConfig + 生成链【事实】

`addMessages` 接收的是 **`com.openjiuwen.core.application.schema.AgentMemoryConfig`**（不是 `core.memory.config.AgentMemoryConfig`，后者 0.1.16 core 里不存在）。6 个开关（core 0.1.16 该类默认全 `true`，`AgentMemoryConfig.java:34-60`）：

| 开关 / 字段 | 控制什么（0.1.16 事实） |
| --- | --- |
| `enableLongTermMem` | 总开关。`false`：变量抽完即 return，不生成 summary/fragment（`Generator.java:97`） |
| `enableSummaryMemory` | 是否生成「单轮历史摘要」层（需 `enableLongTermMem=true`） |
| `enableFragmentMemory` | 片段记忆总开关；`false` 时 user_profile/semantic/episodic 全部不落库 |
| `enableUserProfile` / `enableSemanticMemory` / `enableEpisodicMemory` | 分别控制三个 fragment 子类型（经 `isMemoryTypeEnabled`，core 0.1.16 `:68`） |
| `memVariables: List<Param>` | 变量抽取 schema（`Param.name` = 变量 key，`Param.description` = 抽取说明）；空列表 = 不抽变量 |

`MemoryType`（`com.openjiuwen.memory.manage.model.MemoryType`）值：`USER_PROFILE("user_profile")`、`SEMANTIC_MEMORY("semantic_memory")`、`EPISODIC_MEMORY("episodic_memory")`、`VARIABLE("variable")`、`SUMMARY("summary")`、`UNKNOWN("unknown")`。

**一次 `addMessages(messages, cfg, userId, scopeId, sessionId)`（默认 `genMem=true, genMemWithHistoryMsgNum=2`，`LongTermMemory.java:491`）的执行链**：

1. 校验 scopeId → `getScopeLlm`（取不到 LLM 只 log 返回，不抛）→ 拿分布式锁 `user/<userId>`（kvStore `exclusiveSet`）→ 读最近 2 条历史（`MessageManager.get`，走 SQL）→ 写本次原始消息（逐条 `MessageManager.add`，时间戳 +1ms）。
2. `genMem=false` 到此返回（只有消息层）。
3. 没有 `role="user"` 的消息 → 直接返回（`:450`）。
4. **LLM #1**：`MemoryAnalyzer.analyze`（prompt `memory_analysis_prompt`，最多 3 次重试）→ 变量 + 本轮 summary + `has_key_information`。
5. 变量先落库（KV），然后 `enableSummaryMemory` 时 summary 写 KV + 向量（向量失败**直接抛** `MEMORY_ADD_MEMORY_EXECUTION_ERROR`，`SummaryManager.java:60/267`）。
6. `has_key_information && enableFragmentMemory` → **LLM #2** `LongTermMemoryExtractor`（prompt 抽取三类 fragment）→ 用 `isMemoryTypeEnabled` 过滤 → 写 KV + 向量。
7. 若存在相关旧记忆 → **LLM #3** `MemUpdateChecker` 做冲突/去重合并（`FragmentMemoryManager.java:88`）。
   **一次交互最多 3 次 LLM 调用**，且 `addMessages` 是同步阻塞（0.1.16）。

## 5. 各层能力 → SDK 方法与存储位置（6 层 + 原始消息，0.1.16 全配对）

| 层 | 产生方式 | 读取 / 检索 API | 存储位置 |
| --- | --- | --- | --- |
| 会话 session（上下文层，非 LongTermMemory） | core `SessionMemoryManager.maybeScheduleUpdate(...)`，需 harness `ContextProcessorRail(..., isSessionMemoryEnabled=true)` 装配（0.1.16 该类 extends `DeepAgentRail`，普通 ReActAgent 不开） | `SessionMemoryManager.getSessionMemoryRuntime(...)`（session state 中的 memory_path）；运行时文件 | markdown 文件 `<workspace>/context/<sessionId>_context/session_memory/session_context.md`（core16 `SessionMemoryManager.java:498`） |
| 原始消息 message | `addMessages(..., genMem=false)` | `getRecentMessages(user, scope, session, num)`、`getMessageById(msgId)` | SQL dbStore 的 message 表；返回 `MessageRecord(message, timestamp)` |
| 变量 variable | `memVariables` + LLM #1 | `getVariables(Object names, user, scope)`（null=全部、String、List）、`updateVariables(Map, user, scope)`、`deleteVariables(List, user, scope)` | KV（VariableManager，内容可加密） |
| 用户画像 user_profile | LLM #2 | `searchUserMem` / `getUserMemByPage(..., MemoryType.USER_PROFILE)` | KV（UserMemStore）+ 向量集合 |
| 语义记忆 semantic_memory | LLM #2 | 同上（`MemoryType.SEMANTIC_MEMORY`） | KV + 向量 |
| 情景记忆 episodic_memory | LLM #2 | 同上（`MemoryType.EPISODIC_MEMORY`） | KV + 向量 |
| 历史摘要 summary | LLM #1 产出的 summary | `searchUserHistorySummary(...)` / `getUserMemByPage(..., MemoryType.SUMMARY)` | KV + 向量（**无词面兜底**） |
| 删除 / 统计 | — | `deleteMemById`、`deleteMemByUserId`、`deleteMemByScope`、`deleteMessagesByUserAndScope`、`deleteScopeConfig`、`userMemTotalNum`（仅 3 类 fragment 计数） | — |

## 6. 检索与 embedding【事实】

- `searchUserMem(query, num, userId, scopeId, threshold)`：对 user_profile/semantic/episodic 三个向量集合检索，合并后按 score 降序取前 num，`score = max(向量分, 词面相似度)`（`FragmentMemoryManager.search`），最后按 `score >= threshold` 过滤（threshold 传 0 表示不过滤）。
- `searchUserHistorySummary(query, num, userId, scopeId, threshold)`：只查 summary 向量集合，**没有 KV 词面兜底** → `InMemoryVectorStore` 重启丢索引后必返回空。
- embedding 解析优先级（`resolveEmbeddingModel`，`LongTermMemory.java:1012`）：scope 的 `embeddingCfg`（`new APIEmbedding(cfg)`）→ `registerStore` 传入的 `baseEmbed` → 都没有则为空。**无 embedding 时静默降级**：`searchUserMem` 直接返回 `List.of()`；summary/fragment 写入会抛错。
- 分页：`getUserMemByPage(userId, scopeId, pageSize, pageIdx, MemoryType)`，`pageIdx` **从 1 开始**（`UserMemStore.getInRange` 取 `pageSize*(pageIdx-1)..pageSize*pageIdx`）；`MemoryType.UNKNOWN` 不过滤类型；`VARIABLE` 类型走独立 KV，查不到。
- `MemResult` = `{ MemInfo memInfo, double score }`；`MemInfo` = `{ String memId, String content, MemoryType type }`（`MemResult.java` / `MemInfo.java`）。

## 7. 0.1.17 core-only 可支撑哪些「层」【事实 + 推断】

`SimpleMemoryIndex`（`core.foundation.store.index.SimpleMemoryIndex`，0.1.17 新增，公开 API 已 javap）：

```java
new SimpleMemoryIndex(BaseKVStore kv, BaseVectorStore vector);        // 或 (kv, vector, Embedding)
void setEmbeddingModel(Embedding);
CompletableFuture<Void> addMemories(String userId, String scopeId, List<MemoryDoc> docs);
CompletableFuture<List<MemorySearchResult>> search(String userId, String scopeId, String query,
        List<String> memTypes, int topK);                            // MemorySearchResult = record(MemoryDoc, double)
CompletableFuture<List<MemoryDoc>> listMemories(String userId, String scopeId, int offset, int limit, List<String> types);
// 另有 update/deleteMemories、deleteByUser/Scope、getById、listUserScopes、createBackup/restoreBackup
```

- `MemoryDoc(String id, String text, String type, ZonedDateTime timestamp, Map<String,Object> fields)`：`type` 是**自由字符串**，索引按 `uid_<userId>_gid_<scopeId>_mtype_<type>` 建向量集合 → 可以直接用 `user_profile`/`semantic_memory`/`episodic_memory`/`summary`/`variable` 作为层名，并用 `search(..., List.of("user_profile"), k)` 按层检索、`listMemories(..., types)` 按层分页。
- **写入/检索强依赖 Embedding**：`addMemories` 在 embedding 为 null 时返回 failed future（`MEMORY_ADD_MEMORY_EXECUTION_ERROR`）；`search` 在无 embedding 时返回空 List（`SimpleMemoryIndex.java:107,144`）。core 0.1.17 自带两个实现：`APIEmbedding(foundation.store.EmbeddingConfig)` 与本地的 `HashEmbedding`（SHA-256 确定向量，默认 32 维，无网络，但**不是语义向量**，检索质量仅够演示）。
- 持久化：KV 可用 SQLite（`new DbBasedKVStore(DataSource)`，0.1.17 已无 `foundation.store.kv.SqliteKVStore`，0.1.16 才有）；`InMemoryVectorStore` 进程内，重启丢索引。`DefaultDbStore(String jdbcUrl)` 可做业务 SQL 存储。
- **判断**：严格用 0.1.17 时，样例可以展示 6 层的「**存储 + 向量检索 + 分页 + 删除**」底座（层名由 `MemoryDoc.type` 自定义），但**没有 LLM 自动抽取/摘要/冲突合并的生成层**（该能力只在 memory 模块的 `LongTermMemory` 里）；session 层可用 core 的 `SessionMemoryManager`（markdown 文件，不写索引）。若要「对话后自动生成 6 层记忆」，0.1.17 core-only 做不到；在业务代码里自写抽取 = 在样例里重造框架能力，违反仓库约束。

`SessionMemoryManager`（0.1.17）：上下文引擎的会话笔记层，与 LongTermMemory 无共享存储。触发条件：首次 `currentTokens >= triggerTokens`（默认 10000）；之后增量 `>= triggerAddTokens`（默认 5000）**且**工具调用增量 `>= toolMin`（默认 3）；后台异步抽取（`SessionMemoryManager.java:147-189`）。启用方式：harness rail `ContextProcessorRail`——0.1.17 构造 `(List<ProcessorSpec>, boolean preset, SessionMemoryConfig sessionMemory)`，**传非 null SessionMemoryConfig 即启用**（extends `AgentRail`）；0.1.16 构造 `(isPreset, processorKeys, isSessionMemoryEnabled)`（extends `DeepAgentRail`）。不是普通 AgentConfig 布尔开关。

`PersistenceCheckpointer`（0.1.17）：`new PersistenceCheckpointer(BaseKVStore)` 或 `(BaseKVStore, actualStorePath)`；`CheckpointerConfig("persistence", Map.of("db_type","sqlite","db_path","data/checkpointer"))`，也接受直接传 `kv_store`/`db_client`（BaseKVStore 或 DataSource）；`db_path` 默认 `checkpointer`、自动补 `.db`、支持 `jdbc:sqlite:` 前缀（`PersistenceCheckpointer.java:84-115,326`）。恢复入口是 Agent/Workflow 生命周期调用的 `preAgentExecute/postAgentExecute`（`Checkpointer` 基类）。

## 8. 0.1.16 全配对底座清单（LongTermMemory 依赖满足性）【事实】

| 底座类 | core 0.1.16 | LongTermMemory 是否直接使用 |
| --- | --- | --- |
| `SessionMemoryManager` / `SessionMemoryConfig` | ✅（`context.context`） | ✗（context 层，独立装配） |
| `SqliteKVStore(String path,int timeout,boolean wal)` | ✅（extends `spi.store.BaseKVStore`） | ✅ registerStore 的 kv 推荐实现 |
| `DbBasedKVStore(spi BaseDbStore<?>)` | ✅（extends `spi.store.BaseKVStore`） | ⚠️ **SQLite 下不可用**（H2 方言 `MERGE INTO`，storage probe 实测语法错误），仅 H2/其它 DB |
| `DefaultDbStore(String jdbcUrl[,user,pwd])` | ✅（extends `spi.store.BaseDbStore<DataSource>`） | ✅ registerStore 的 dbStore |
| `PersistenceCheckpointer(spi BaseKVStore)` | ✅ | ✗（会话恢复用） |
| `APIEmbedding` / `HashEmbedding`（`retrieval.embedding.Embedding` 接口） | ✅ | ✅ registerStore 的 embedding |
| `foundation.store.vector.InMemoryVectorStore` | ✅（implements `spi.store.vector.BaseVectorStore`） | ✅ registerStore 的 vectorStore |
| `SimpleMemoryIndex` / `MemoryDoc` / `BaseMessageStore` | ❌（0.1.17 新增） | ✗ 不依赖，不影响 |
| 工厂：`KVStoreFactory.create("sqlite", conf)`、`VectorStoreFactory.create("memory")`、`StoreFactory.createVectorStore("memory")` | ✅ | ✅ 推荐接线方式 |

依赖完整性证据：memory 0.1.16 全部 195 个类对 core 0.1.16+全依赖解析 **0 失败**；对 core 0.1.17 **23 类/119 处失败** → 0.1.16 全配对自洽完整，混用不是。

## 9. 风险与踩坑

1. **【风险】静默不报错的行为**（只打 log 返回）：scopeId 非法、LLM 未配置、无 `role="user"` 消息、`genMem=false`（`LongTermMemory.java:408-411/421/445/450`）。样例要显式判断日志/返回值，否则误以为「记忆已写入」。
2. **【风险】无 embedding 时**：summary/fragment 写入抛 `MEMORY_ADD_MEMORY_EXECUTION_ERROR`；`searchUserMem` 直接空列表。样例必须传 `HashEmbedding`/`APIEmbedding` 或 scope `embeddingCfg`。
3. **【风险】向量层不持久**：0.1.16 core jar 内置向量只有 `InMemoryVectorStore`，进程重启后 fragment 检索靠 KV 词面兜底（有结果但质量差），summary 检索直接空 —— 右侧「分层记忆」面板建议用 `getUserMemByPage`（读 KV/SQL）而非只靠 `searchUserMem`。
4. **【风险】LLM 抽取 JSON 不稳定**：`MemoryAnalyzer` 内部重试 3 次后可能返回空/部分结果 → 表现为「这一轮没生成记忆」，不报错；prompt 模板在 memory jar 内（`memory_analysis_prompt` 等），字段格式改动需升级 jar。
5. **【风险】成本/时延**：一次 `addMessages(genMem=true)` 最多 3 次 LLM；样例交互应允许显式 `genMem=false`（只演示原始消息层）或降低调用频率。
6. **【风险】变量层不受记忆开关约束**：LLM #1 的变量抽取发生在 `enableLongTermMem` 判断之前（`Generator.java:91-95` 变量抽取早于 `:97` 的 long-term 开关判断），即使所有记忆开关全关，只要 genMem=true 仍会调用一次 LLM。
7. **【风险】cryptoKey**：必须空或 32 字节；一旦设置，KV 内容为密文，换 key 后旧数据读不出；加密对嵌入向量无影响（用明文算向量、存密文）。
8. **【风险】单例/多次配置**：`LongTermMemory` 全 JVM 单例，重复 `registerStore/setConfig` 会重建 manager 并重新迁移（迁移有分布式锁语义），测试间需 `resetInstance()`。
9. **【风险】混用无法通过调用方式绕过**：`genMem=false` 只写消息时，第二次 `addMessages` 读历史仍会走旧 builder（历史表有行即崩）；唯一可靠解是版本成对锁定。
10. **【实测：storage probe 已复核】** 0.1.16 全配对 + SQLite 可跑：`SqliteKVStore` 建表/迁移成功、KV/SQL 重启可读、fragment 检索靠 KV 词面兜底（实测 score=0.9091）、summary 重启后检索为空、`CheckpointerFactory.create("persistence", Map.of("db_type","sqlite","db_path",...))` 可用。**仍待实测**：真实 LLM 的 `genMem=true` 抽取链路（耗时/层数/JSON 稳定性）。

## 10. 证据命令（可复现）

```bash
JAVAP=.cache/tools/amazon-corretto-17.jdk/Contents/Home/bin/javap
M=.cache/m2repo/com/openjiuwen/agent-core-memory-java/0.1.16/agent-core-memory-java-0.1.16.jar
C16=.cache/m2repo/com/openjiuwen/agent-core-java/0.1.16/agent-core-java-0.1.16.jar
C17=.cache/m2repo/com/openjiuwen/agent-core-java/0.1.17/agent-core-java-0.1.17.jar
$JAVAP -p -classpath $M com.openjiuwen.memory.LongTermMemory            # 全部公开签名
$JAVAP -p -classpath $C16 com.openjiuwen.core.application.schema.AgentMemoryConfig    # 6 开关 + builder
$JAVAP -p -classpath $C17 com.openjiuwen.core.application.schema.AgentMemoryConfig    # 0.1.17 空壳子类
$JAVAP -p -classpath $C17 com.openjiuwen.core.foundation.store.index.SimpleMemoryIndex
$JAVAP -p -classpath $C17 com.openjiuwen.core.foundation.store.MemoryDoc
unzip -l $C16 | grep -cE 'SimpleMemoryIndex|MemoryDoc|BaseMessageStore'   # 0（0.1.17 新增）
$JAVAP -c -p -classpath $M com.openjiuwen.memory.process.extract.MemoryAnalyzer | grep -n 'Model.invoke'
```

**sources jar 都在 `.cache/m2repo/com/openjiuwen/`**（memory/core 0.1.16、core 0.1.17），本文行号均出自解包源码；混用结论除字节码解析外，另有反射 probe（§2 表）双重验证。

## 11. 图记忆（Graph Memory）现状（2026-11-12 核实）

**结论：支持图结构的记忆写入与召回，但已发布 Java 组合的图存储后端只有进程内 `in_memory`；持久化图存储目前依赖外部 Milvus，官方没有 SQLite/本地文件型的图存储后端。**

### Java 发布物事实

- `agent-core-memory-java:0.1.16` jar 内确实包含图记忆引擎：
  `com.openjiuwen.memory.graph.GraphMemory`（不是 0.1.17 develop 里重构后的 `...graph.graph_memory.GraphMemory`）。
  公开 API：
  - `addMemory(EpisodeType, userId, content, contentFmtKwargs, referenceTime)`：LLM 抽取实体/关系/情节并合并去重后写入图存储；
  - `search(query, userId, strategyName, entity, relation, episode, queryEmbedding)`：
    返回 `Map<collection, List<SearchHit>>`，集合为 `ENTITY_COLLECTION` / `RELATION_COLLECTION` / `EPISODE_COLLECTION`，
    `SearchHit(score, BaseGraphObject)`；
  - `attachEmbedder(Embedding)` / `attachReranker(Reranker)` / `registerSearchStrategy(...)`。
- `agent-core-java:0.1.16` 提供 `com.openjiuwen.core.foundation.store.graph.GraphStore`、`GraphConfig`、
  `InMemoryGraphStore`；`GraphStoreFactory` 注册的本地后端是 `in_memory`。
  注意 `GraphConfig` 默认 `backend="milvus"`，要用内存后端必须显式 `backend("in_memory")`。
- `agent-core-java:0.1.17` 新增 `com.openjiuwen.core.foundation.store.graph.milvus.MilvusGraphStore`，
  但配套的 `agent-core-memory-java:0.1.17` 尚未发布；把 0.1.16 memory 与 0.1.17 core 混用仍然不可行（同 §2）。
- **最小运行 probe（0.1.16 全配对）**：`GraphConfig(backend=in_memory)` → `new GraphMemory(config)` →
  `attachEmbedder(FakeEmbedding)` → `search(...)`，成功返回三个集合 `[ENTITY_COLLECTION, RELATION_COLLECTION,
  EPISODE_COLLECTION]`（空库 size 均为 0）。说明图记忆的构造、图存储装配与召回调用链在发布版可运行；
  `addMemory` 需要真实 LLM 做抽取，未在本 probe 中执行。probe 源码：`.cache/mem-probe/src/main/java/probe/GraphProbe.java`。

### 召回机制

图记忆维护 **实体（Entity）/ 关系（Relation）/ 情节（Episode）** 三类图对象；写入时由 LLM 抽取实体与关系、
与已有图数据合并去重；召回时按可注册的 `SearchConfig` 策略分别检索实体/关系/情节，支持稠密向量 + 稀疏全文的
混合排序（`RRFRankConfig` / `WeightedRankConfig`）与可选 reranker，结果带 score 按集合返回。
它和 `LongTermMemory` 的 6 层（用户画像/语义/情景/摘要/变量）是**两个独立组件**：GraphMemory 需要自己的
`GraphConfig`、LLM、Embedding 和 GraphStore，不共享 LongTermMemory 的 SQLite/KV 存储。

### 对本地数据库样例的影响

- 若坚持「本机 SQLite、零外部服务」：当前发布版无法持久化图记忆。`InMemoryGraphStore` 是进程内 Map，
  重启即丢；Milvus 后端是外部服务，违背本地化目标。
- 可选折中：把「图记忆」作为演示层使用 `in_memory`，启动时从对话/其他存储重建（成本高）；
  或接受 Milvus 作为图存储（不是零依赖）。
- 若必须用 SQLite 持久化图：需要实现自定义 `GraphStore` 并通过 `GraphStoreFactory.registerBackend(...)` 注册，
  这属于框架扩展；按本仓库约束应优先给 agent-core 提 Issue/PR，而不是把实现藏在样例里。


### 11.1 本样例的基础设施与产品原型（2026-11-12）

- 样例根目录新增 `docker-compose.yml`：etcd + MinIO + Milvus standalone，`docker compose up -d` 后
  Milvus 暴露 `localhost:19530`；已用 `docker compose config -q` 校验，本机未预拉镜像、未启动容器。
- 原型右侧新增「图记忆」tab：把实体/关系/情节画成 SVG，当前按方案 B 标注「图记忆 · InMemory（演示）」；
  存储卡片显示「长期记忆 → 本机 SQLite、图记忆 → 进程内」。Docker Compose Milvus 作为 0.1.17 后的升级目标保留。
- 原型右侧新增「加工流程」tab：展示 6 步加工流水线（进入队列 → LLM 分析 → 结构化抽取 → 去重合并 →
  写入 SQLite/向量/InMemory 图 → 可召回）、输入上下文→LLM 提炼→长期记忆的加工样本与最近一次抽取指标。注意：这不是引擎后台自动调度；0.1.16 的
  `addMessages` / `GraphMemory.addMemory` 都是同步调用，样例在对话结束后由应用侧异步触发。
- 顶部新增「记忆模型服务」配置弹层：Provider / API Base / API Key / 对话模型 / Embedding / Reranker /
  温度 / Milvus 地址；原型只保存在页面内存，真实样例应从环境变量或本机配置读取。
- **当前选择方案 B**：0.1.16 全配对 + `InMemoryGraphStore` 演示图记忆（重启丢失，需重建）；Milvus 持久化
  待 memory 0.1.17 发布后切换（`MilvusGraphStore` 仅 0.1.17 core 有、memory 0.1.17 未发布），Docker Compose 已备好。


### 11.2 0.1.16 GraphMemory 关系抽取实测问题（2026-11-12，DeepSeek 实测）

- 样例用 `GraphMemory.addMemory(EpisodeType.CONVERSATION, ...)` 实测两轮：实体和情节正常写入，
  `GraphMemUpdate.getAddedEntity()` 非空、`getAddedEpisode()` 非空，但 `getAddedRelation()` 恒为空。
- 用 LoggingModel（probe 临时类）截获 GraphMemory 实际 LLM 请求/响应：
  - 关系 prompt 的输出定义只写了 `extractedRelations: array`，没有给字段 schema；
  - 模型实际返回 `{"extractedRelations":[{"subject_id":1,"subject_name":"周砚清","object_id":2,...}]}`；
  - 0.1.16 `ParseLlmResponse` 的关系解析读取 `source_id` / `target_id`（以及 `sourceId` / `targetId`），
    因此 `normalizeRelationList` 得到空列表，关系被丢弃，不报错。
- 这是 **0.1.16 jar 内 prompt 与 parser 字段不一致**，不是样例漏调 API，也不是存储层问题；实体/情节/召回
  不受影响。样例加了一个极小的 `GraphRelationCompatModel`（`Model` 子类）只对关系响应做字段改名
  （subject_id→source_id、object_id→target_id、relation_name/relation→name、content→fact），实测
  `graphRelationsAdded=2` 并出现关系边；该适配器是临时兼容层，等 agent-core 0.1.17 或上游 PR 修复后删除，
  不应视为框架能力。
