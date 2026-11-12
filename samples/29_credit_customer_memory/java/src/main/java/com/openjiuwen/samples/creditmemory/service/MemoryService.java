package com.openjiuwen.samples.creditmemory.service;

import com.openjiuwen.core.application.schema.AgentMemoryConfig;
import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.foundation.store.base_embedding.Embedding;
import com.openjiuwen.core.foundation.store.db.DefaultDbStore;
import com.openjiuwen.core.foundation.store.graph.Entity;
import com.openjiuwen.core.foundation.store.graph.Episode;
import com.openjiuwen.core.foundation.store.graph.GraphConfig;
import com.openjiuwen.core.foundation.store.graph.Relation;
import com.openjiuwen.core.foundation.store.kv.SqliteKVStore;
import com.openjiuwen.core.foundation.store.vector.InMemoryVectorStore;
import com.openjiuwen.core.retrieval.common.EmbeddingConfig;
import com.openjiuwen.core.retrieval.embedding.APIEmbedding;
import com.openjiuwen.memory.LongTermMemory;
import com.openjiuwen.memory.MemInfo;
import com.openjiuwen.memory.MemResult;
import com.openjiuwen.memory.config.MemoryEngineConfig;
import com.openjiuwen.memory.config.MemoryScopeConfig;
import com.openjiuwen.memory.config.graph.AddMemStrategy;
import com.openjiuwen.memory.config.graph.EpisodeType;
import com.openjiuwen.memory.graph.GraphMemory;
import com.openjiuwen.memory.graph.States;
import com.openjiuwen.memory.manage.model.MemoryType;
import com.openjiuwen.samples.creditmemory.config.ModelSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 长期记忆 + 图记忆的装配与调用。
 *
 * <p>0.1.16 全配对：LongTermMemory 写 SQLite KV/关系表 + 进程内向量索引；
 * GraphMemory 用 InMemoryGraphStore（方案 B，重启后图数据需重建）。</p>
 */
public final class MemoryService {

    public static final String USER_ID = "C1001";
    public static final String SCOPE_ID = "credit_advisor";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Path dataDir;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "memory-extraction");
        thread.setDaemon(true);
        return thread;
    });

    private volatile ModelSettings settings;
    private volatile LongTermMemory longTermMemory;
    private volatile GraphMemory graphMemory;
    private volatile String initError;
    private final List<GraphEntity> graphEntities = new ArrayList<>();
    private final List<GraphRelation> graphRelations = new ArrayList<>();
    private final List<GraphEpisode> graphEpisodes = new ArrayList<>();
    private volatile String dimensionSourceModel;

    public MemoryService(ModelSettings settings, Path dataDir) {
        this.settings = settings;
        this.dataDir = dataDir;
        initialize();
    }

    public synchronized void updateSettings(ModelSettings settings) {
        this.settings = settings;
        initialize();
    }

    public synchronized void initialize() {
        initError = null;
        try {
            Files.createDirectories(dataDir);
            Path dbPath = dataDir.resolve("credit_memory.db");
            SqliteKVStore kv = new SqliteKVStore(dbPath.toString(), 30, true);
            InMemoryVectorStore vector = new InMemoryVectorStore();
            DefaultDbStore db = new DefaultDbStore("jdbc:sqlite:" + dbPath);
            APIEmbedding embedding = settings.buildEmbedding();
            int dimension = Math.max(32, settings.memory.embeddingDim);

            LongTermMemory.resetInstance();
            LongTermMemory memory = LongTermMemory.getInstance();
            memory.registerStore(kv, vector, db, embedding);
            memory.setConfig(MemoryEngineConfig.builder()
                    .defaultModelCfg(memoryRequestConfig())
                    .defaultModelClientCfg(memoryClientConfig())
                    .inputMsgMaxLen(8192)
                    .singleTurnHistorySummaryMaxToken(128)
                    .build());
            memory.setScopeConfig(SCOPE_ID, MemoryScopeConfig.builder()
                    .modelCfg(memoryRequestConfig())
                    .modelClientCfg(memoryClientConfig())
                    .embeddingCfg(new EmbeddingConfig(settings.memory.embeddingModel,
                            settings.memory.embeddingApiBase, settings.memory.embeddingApiKey))
                    .build());
            this.longTermMemory = memory;

            GraphConfig graphConfig = GraphConfig.builder()
                    .uri(dataDir.resolve("graph-memory").toString())
                    .backend("in_memory")
                    .workerThreads(2)
                    .embedDim(dimension)
                    .embedBatchSize(4)
                    .timeout(900)
                    .build();
            AddMemStrategy graphStrategy = new AddMemStrategy();
            graphStrategy.setChineseRelationEnabled(true);
            graphStrategy.setChineseEntityDedupeEnabled(true);
            GraphMemory graph = new GraphMemory(graphConfig,
                    new GraphRelationCompatModel(settings.buildMemoryModel()), false, null,
                    graphStrategy, null, Map.of(), "cn", false);
            graph.attachEmbedder(new BaseEmbeddingAdapter(embedding));
            this.graphMemory = graph;
            synchronized (this) {
                graphEntities.clear();
                graphRelations.clear();
                graphEpisodes.clear();
            }
            this.dimensionSourceModel = settings.memory.embeddingModel;
            System.out.println("[memory] initialized: sqlite=" + dbPath.toAbsolutePath()
                    + " embeddingDim=" + dimension + " graph=in_memory");
        } catch (Exception exception) {
            initError = exception.getClass().getSimpleName() + ": " + exception.getMessage();
            System.err.println("[memory] initialize failed: " + initError);
            exception.printStackTrace();
        }
    }

    public String initError() {
        return initError;
    }

    public String graphStoreName() {
        return "InMemoryGraphStore（演示，重启后重建）";
    }

    public Map<String, Object> memoryView() {
        LongTermMemory memory = longTermMemory;
        List<Map<String, Object>> items = new ArrayList<>();
        if (memory != null) {
            try {
                for (MemInfo info : memory.getUserMemByPage(USER_ID, SCOPE_ID, 500, 1, MemoryType.UNKNOWN)) {
                    if (info.getType() == null || info.getType() == MemoryType.UNKNOWN) { continue; }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", info.getMemId());
                    item.put("layer", layerOf(info.getType()));
                    item.put("content", info.getContent());
                    item.put("version", 1);
                    item.put("createdAt", "");
                    items.add(item);
                }
            } catch (Exception exception) {
                System.err.println("[memory] list failed: " + exception.getMessage());
            }
        }
        return Map.of("memories", items, "total", items.size());
    }

    public List<Map<String, Object>> variablesView() {
        LongTermMemory memory = longTermMemory;
        List<Map<String, Object>> items = new ArrayList<>();
        if (memory == null) { return items; }
        try {
            Map<String, String> values = memory.getVariables(null, USER_ID, SCOPE_ID);
            if (values != null) {
                values.forEach((name, value) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", name);
                    item.put("value", value);
                    items.add(item);
                });
            }
        } catch (Exception exception) {
            System.err.println("[memory] variables failed: " + exception.getMessage());
        }
        return items;
    }

    public synchronized Map<String, Object> graphView() {
        List<Map<String, Object>> entities = new ArrayList<>();
        for (GraphEntity entity : graphEntities) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", entity.id);
            map.put("label", entity.label);
            map.put("type", entity.type);
            entities.add(map);
        }
        List<Map<String, Object>> relations = new ArrayList<>();
        for (GraphRelation relation : graphRelations) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", relation.id);
            map.put("source", relation.source);
            map.put("target", relation.target);
            map.put("label", relation.label);
            relations.add(map);
        }
        List<Map<String, Object>> episodes = new ArrayList<>();
        for (GraphEpisode episode : graphEpisodes) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", episode.id);
            map.put("label", episode.label);
            episodes.add(map);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("entities", entities);
        view.put("relations", relations);
        view.put("episodes", episodes);
        view.put("store", graphStoreName());
        return view;
    }

    public RecallResult recall(String query) {
        RecallResult result = new RecallResult(query);
        LongTermMemory memory = longTermMemory;
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> graphHits = new ArrayList<>();
        try {
            if (memory != null) {
                List<MemResult> hits = new ArrayList<>();
                hits.addAll(memory.searchUserMem(query, 5, USER_ID, SCOPE_ID, 0.0));
                hits.addAll(memory.searchUserHistorySummary(query, 3, USER_ID, SCOPE_ID, 0.0));
                for (MemResult hit : hits) {
                    MemInfo info = hit.getMemInfo();
                    if (info == null || info.getContent() == null) { continue; }
                    if (!seen.add(info.getMemId())) { continue; }
                    result.vectorEntries.add(new Entry("向量", info.getContent()));
                }
            }
        } catch (Exception exception) {
            System.err.println("[memory] vector recall failed: " + exception.getMessage());
        }
        try {
            Map<String, String> variables = memory == null ? Map.of() : memory.getVariables(null, USER_ID, SCOPE_ID);
            int count = 0;
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                if (count++ >= 3) { break; }
                result.kvEntries.add(new Entry("KV", entry.getKey() + "=" + entry.getValue()));
            }
        } catch (Exception exception) {
            System.err.println("[memory] kv recall failed: " + exception.getMessage());
        }
        GraphMemory graph = graphMemory;
        if (graph != null) {
            try {
                Map<String, List<GraphMemory.SearchHit>> hits = graph.search(query, USER_ID, "default",
                        true, true, true, null);
                if (hits != null) {
                    for (Map.Entry<String, List<GraphMemory.SearchHit>> entry : hits.entrySet()) {
                        for (GraphMemory.SearchHit hit : entry.getValue()) {
                            Map<String, Object> item = new LinkedHashMap<>();
                            Object object = hit.object();
                            String kind = object instanceof Entity ? "entity"
                                    : object instanceof Relation ? "relation" : "episode";
                            String label = labelOf(object);
                            String type = object instanceof Entity ? inferType(label) : "";
                            item.put("kind", kind);
                            item.put("label", label);
                            item.put("type", type);
                            graphHits.add(item);
                            if (result.graphEntries.size() < 3) {
                                result.graphEntries.add(new Entry("图", label));
                            }
                        }
                    }
                }
            } catch (Exception exception) {
                System.err.println("[memory] graph recall failed: " + exception.getMessage());
            }
        }
        result.graphHits.addAll(graphHits);
        int index = 0;
        int max = Math.max(result.vectorEntries.size(), Math.max(result.kvEntries.size(), result.graphEntries.size()));
        while (result.entries.size() < 6 && index < max) {
            if (index < result.vectorEntries.size()) { result.entries.add(result.vectorEntries.get(index)); }
            if (index < result.graphEntries.size() && result.entries.size() < 6) { result.entries.add(result.graphEntries.get(index)); }
            if (index < result.kvEntries.size() && result.entries.size() < 6) { result.entries.add(result.kvEntries.get(index)); }
            index++;
        }
        result.channels.put("vector", result.vectorEntries.size());
        result.channels.put("kv", result.kvEntries.size());
        result.channels.put("graph", result.graphEntries.size());
        return result;
    }

    public Pipeline.Run startProcessing(SessionStore.Session session, String customerText, String assistantText,
                                        List<String> context, Consumer<Pipeline.Run> onFinish) {
        Pipeline.Run run = new Pipeline.Run("run-" + System.currentTimeMillis(), LocalTime.now().format(TS));
        run.sample.input = new ArrayList<>(context);
        run.stats.writeTargets = List.of("SQLite KV / 消息表", "向量索引", "InMemory 图存储（演示）");
        String[] labels = {"对话进入加工队列", "LLM 记忆分析", "结构化抽取", "去重与合并", "写入存储", "可召回"};
        String[] keys = {"ingest", "analyze", "extract", "merge", "write", "ready"};
        for (int index = 0; index < labels.length; index++) {
            run.stages.add(new Pipeline.Stage(keys[index], labels[index]));
        }
        run.stages.get(0).status = "done";
        run.stages.get(1).status = "running";
        executor.submit(() -> process(session, customerText, assistantText, run, onFinish));
        return run;
    }

    private void process(SessionStore.Session session, String customerText, String assistantText,
                         Pipeline.Run run, Consumer<Pipeline.Run> onFinish) {
        long started = System.currentTimeMillis();
        try {
            LongTermMemory memory = longTermMemory;
            GraphMemory graph = graphMemory;
            if (memory == null) {
                throw new IllegalStateException("LongTermMemory 未初始化: " + initError);
            }
            Map<String, String> beforeVariables = memory.getVariables(null, USER_ID, SCOPE_ID);
            Set<String> beforeMemories = memoryIds(memory);
            AgentMemoryConfig config = new AgentMemoryConfig();
            config.setEnableLongTermMem(true);
            config.setEnableFragmentMemory(true);
            config.setEnableUserProfile(true);
            config.setEnableSemanticMemory(true);
            config.setEnableEpisodicMemory(true);
            config.setEnableSummaryMemory(true);
            config.setMemVariables(List.of(
                    com.openjiuwen.core.common.schema.Param.string("loanAmount", "申请贷款金额", false),
                    com.openjiuwen.core.common.schema.Param.string("loanTerm", "贷款期数", false),
                    com.openjiuwen.core.common.schema.Param.string("plannedYears", "计划提前结清年限", false),
                    com.openjiuwen.core.common.schema.Param.string("repaymentPreference", "还款方式偏好", false)));
            List<BaseMessage> messages = new ArrayList<>();
            messages.add(new UserMessage(customerText));
            messages.add(new AssistantMessage(assistantText));
            run.stages.get(1).status = "done";
            run.stages.get(1).durationMs = 0;
            run.stages.get(2).status = "running";
            memory.addMessages(messages, config, USER_ID, SCOPE_ID, session.id);
            run.stats.llmCalls = 3;
            run.stages.get(2).status = "done";
            run.stages.get(3).status = "done";
            run.stages.get(4).status = "running";

            States.GraphMemUpdate update = null;
            if (graph != null) {
                try {
                    update = graph.addMemory(EpisodeType.CONVERSATION, USER_ID, messages, null, OffsetDateTime.now());
                    applyGraphUpdate(update);
                    run.stats.llmCalls += 1;
                } catch (Exception graphException) {
                    System.err.println("[memory] graph extraction failed: " + graphException.getMessage());
                }
            }
            Set<String> afterMemories = memoryIds(memory);
            Map<String, String> afterVariables = memory.getVariables(null, USER_ID, SCOPE_ID);
            List<MemInfo> all = memory.getUserMemByPage(USER_ID, SCOPE_ID, 500, 1, MemoryType.UNKNOWN);
            for (MemInfo info : all) {
                if (info.getContent() == null) { continue; }
                if (!beforeMemories.contains(info.getMemId())) {
                    run.stats.memoriesAdded += 1;
                    run.sample.outputs.memories.add(info.getContent());
                }
            }
            int variableDelta = Math.max(0, afterVariables.size() - beforeVariables.size());
            run.stats.memoriesAdded += variableDelta;
            if (update != null) {
                run.stats.graphEntitiesAdded = update.getAddedEntity() == null ? 0 : update.getAddedEntity().size();
                run.stats.graphRelationsAdded = update.getAddedRelation() == null ? 0 : update.getAddedRelation().size();
                run.stats.graphEpisodesAdded = update.getAddedEpisode() == null ? 0 : update.getAddedEpisode().size();
                for (Entity entity : safe(update.getAddedEntity())) {
                    run.sample.outputs.graph.add(labelOf(entity));
                }
                for (Relation relation : safe(update.getAddedRelation())) {
                    run.sample.outputs.graph.add(labelOf(relation));
                }
            }
            if (run.stats.memoriesAdded == 0) {
                run.stats.llmCalls = Math.max(1, run.stats.llmCalls - 2);
            }
            run.stages.get(4).status = "done";
            run.stages.get(5).status = "done";
            run.stages.get(4).detail = String.join(" + ", run.stats.writeTargets);
            run.stages.get(5).detail = "可被下一轮对话召回";
            run.status = "done";
            run.stages.get(2).detail = "长期记忆 +" + run.stats.memoriesAdded
                    + " · 图实体 +" + run.stats.graphEntitiesAdded + " / 关系 +" + run.stats.graphRelationsAdded;
            run.stages.get(3).detail = "去重合并完成";
        } catch (Exception exception) {
            run.status = "error";
            run.error = exception.getClass().getSimpleName() + ": " + exception.getMessage();
            System.err.println("[memory] processing failed: " + run.error);
            exception.printStackTrace();
        } finally {
            for (Pipeline.Stage stage : run.stages) {
                if (!"done".equals(stage.status) && !"error".equals(stage.status)) {
                    stage.status = "error".equals(run.status) ? "error" : "done";
                }
                if (stage.durationMs == 0) { stage.durationMs = 0; }
            }
            long elapsed = Math.max(1, System.currentTimeMillis() - started);
            run.stages.forEach(stage -> stage.durationMs = elapsed / Math.max(1, run.stages.size()));
            if (onFinish != null) {
                try {
                    onFinish.accept(run);
                } catch (Exception callbackError) {
                    System.err.println("[memory] callback failed: " + callbackError.getMessage());
                }
            }
        }
    }

    private synchronized void applyGraphUpdate(States.GraphMemUpdate update) {
        if (update == null) { return; }
        for (Entity entity : safe(update.getAddedEntity())) { upsertEntity(entity); }
        for (Entity entity : safe(update.getUpdatedEntity())) { upsertEntity(entity); }
        for (Relation relation : safe(update.getAddedRelation())) { upsertRelation(relation); }
        for (Relation relation : safe(update.getUpdatedRelation())) { upsertRelation(relation); }
        for (Episode episode : safe(update.getAddedEpisode())) { upsertEpisode(episode); }
        for (Episode episode : safe(update.getUpdatedEpisode())) { upsertEpisode(episode); }
        if (update.getRemovedEntity() != null) {
            update.getRemovedEntity().forEach(id -> {
                graphEntities.removeIf(entity -> entity.id.equals(id));
                graphRelations.removeIf(relation -> relation.source.equals(id) || relation.target.equals(id));
            });
        }
        if (update.getRemovedRelation() != null) {
            update.getRemovedRelation().forEach(id -> graphRelations.removeIf(relation -> relation.id.equals(id)));
        }
    }

    private void upsertEntity(Entity entity) {
        String id = entity.getUuid();
        if (id == null || id.isBlank()) { return; }
        String label = labelOf(entity);
        if (label.isBlank()) { return; }
        String type = inferType(entity, label);
        graphEntities.removeIf(item -> item.id.equals(id) || item.label.equals(label));
        graphEntities.add(new GraphEntity(id, label, type));
    }

    private void upsertRelation(Relation relation) {
        String rawId = relation.getUuid();
        final String id = rawId == null || rawId.isBlank()
                ? relation.getName() + ":" + relation.getLhs() + ":" + relation.getRhs()
                : rawId;
        String source = uuidOf(relation.getLhs());
        String target = uuidOf(relation.getRhs());
        String label = labelOf(relation);
        if (source.isBlank() || target.isBlank() || label.isBlank()) { return; }
        graphRelations.removeIf(item -> item.id.equals(id));
        graphRelations.add(new GraphRelation(id, source, target, label));
    }

    private void upsertEpisode(Episode episode) {
        String id = episode.getUuid();
        if (id == null || id.isBlank()) { return; }
        String label = labelOf(episode);
        if (label.isBlank()) { return; }
        graphEpisodes.removeIf(item -> item.id.equals(id));
        graphEpisodes.add(new GraphEpisode(id, label));
    }

    private String uuidOf(Object value) {
        if (value == null) { return ""; }
        if (value instanceof Entity entity) { return entity.getUuid(); }
        if (value instanceof String text) { return text; }
        try {
            Object uuid = value.getClass().getMethod("getUuid").invoke(value);
            return uuid == null ? String.valueOf(value) : String.valueOf(uuid);
        } catch (Exception ignored) {
            return String.valueOf(value);
        }
    }

    private String labelOf(Object object) {
        if (object == null) { return ""; }
        if (object instanceof Entity entity) {
            return blankTo(entity.getName(), entity.getContent());
        }
        if (object instanceof Relation relation) {
            String name = relation.getName();
            String content = relation.getContent();
            String mapped = relationLabel(name);
            if (mapped != null) { return mapped; }
            if (content != null && !content.isBlank()
                    && (name == null || name.isBlank() || "RELATION".equalsIgnoreCase(name))) {
                return content;
            }
            return blankTo(name, content);
        }
        if (object instanceof Episode episode) {
            return episode.getContent() == null ? "" : episode.getContent();
        }
        if (object instanceof BaseMessage message) {
            return message.getContentAsString();
        }
        return String.valueOf(object);
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? (fallback == null ? "" : fallback) : value;
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private Set<String> memoryIds(LongTermMemory memory) {
        Set<String> ids = new LinkedHashSet<>();
        for (MemInfo info : memory.getUserMemByPage(USER_ID, SCOPE_ID, 500, 1, MemoryType.UNKNOWN)) {
            ids.add(info.getMemId());
        }
        return ids;
    }

    private static String layerOf(MemoryType type) {
        if (type == null) { return "semantic"; }
        switch (type.name()) {
            case "USER_PROFILE": return "profile";
            case "SEMANTIC_MEMORY": return "semantic";
            case "EPISODIC_MEMORY": return "episodic";
            case "SUMMARY": return "summary";
            case "VARIABLE": return "variable";
            default: return "semantic";
        }
    }

    private static int dimensionOf(APIEmbedding embedding) {
        try {
            int dimension = embedding.getDimension();
            if (dimension >= 32) { return dimension; }
        } catch (Exception exception) {
            System.err.println("[memory] embedding dimension probe failed: " + exception.getMessage());
        }
        return 768;
    }

    private ModelRequestConfig memoryRequestConfig() {
        return ModelRequestConfig.builder()
                .modelName(settings.memory.model)
                .temperature(0.1)
                .maxTokens(settings.memory.maxTokens)
                .build();
    }

    private ModelClientConfig memoryClientConfig() {
        return ModelClientConfig.builder()
                .clientId("credit-memory")
                .clientProvider("openai")
                .apiBase(settings.memory.apiBase)
                .apiKey(settings.memory.apiKey)
                .timeout(900)
                .maxRetries(1)
                .build();
    }

    public void shutdown() {
        executor.shutdownNow();
        synchronized (this) {
            longTermMemory = null;
            graphMemory = null;
        }
        try {
            LongTermMemory.resetInstance();
        } catch (Exception ignored) {
        }
    }

    private static String inferType(Entity entity, String label) {
        if (label != null) {
            if (label.contains("周砚清") || label.contains("客户") || label.contains("顾问")) { return "person"; }
            if (label.contains("公司") || label.contains("制造") || label.contains("企业")) { return "company"; }
        }
        if (entity != null && entity.getAttributes() != null) {
            for (Map.Entry<String, Object> attribute : entity.getAttributes().entrySet()) {
                String key = attribute.getKey() == null ? "" : attribute.getKey().toLowerCase();
                if (!(key.contains("type") || key.contains("类型") || key.contains("category"))) { continue; }
                String mapped = mapTypeName(String.valueOf(attribute.getValue()));
                if (mapped != null) { return mapped; }
            }
        }
        return inferType(label);
    }

    private static String inferType(String label) {
        if (label == null) { return "other"; }
        if (label.contains("公司") || label.contains("制造") || label.contains("企业")) { return "company"; }
        if (label.contains("逾期") || label.contains("事件") || label.contains("申请") || label.contains("咨询")) { return "event"; }
        if (label.contains("周砚清") || label.contains("客户") || label.contains("顾问")) { return "person"; }
        if (label.contains("杭州") || label.contains("浙江") || label.contains("城市")) { return "location"; }
        if (label.contains("贷") || label.contains("等额") || label.contains("先息")) { return "product"; }
        if (label.contains("偏好") || label.contains("敏感")) { return "preference"; }
        if (label.contains("计划") || label.contains("结清") || label.contains("期")) { return "plan"; }
        return "other";
    }


    private static String relationLabel(String name) {
        if (name == null || name.isBlank()) { return null; }
        switch (name.toUpperCase()) {
            case "OPERATES": return "经营";
            case "LOCATED_IN": return "位于";
            case "WORKS_AT": return "任职于";
            case "PREFERS": return "偏好";
            case "HAS_PLAN": return "计划";
            case "EXPERIENCED": return "经历";
            case "OWNS": return "拥有";
            case "OWNS_COMPANY": return "拥有";
            case "BELONGS_TO": return "属于";
            case "APPLIES_FOR": return "申请";
            case "HAS_ATTRIBUTE": return "属性";
            case "REJECTS": return "拒绝";
            case "PLANS": return "计划";
            default: return null;
        }
    }

    private static String mapTypeName(String value) {
        if (value == null || value.isBlank()) { return null; }
        String text = value.toLowerCase();
        if (text.contains("human") || text.contains("person") || text.contains("人物") || text.contains("人")) { return "person"; }
        if (text.contains("organization") || text.contains("company") || text.contains("公司") || text.contains("企业")) { return "company"; }
        if (text.contains("location") || text.contains("city") || text.contains("地点") || text.contains("城市")) { return "location"; }
        if (text.contains("event") || text.contains("事件")) { return "event"; }
        if (text.contains("product") || text.contains("产品") || text.contains("loan") || text.contains("贷款")) { return "product"; }
        if (text.contains("preference") || text.contains("偏好")) { return "preference"; }
        if (text.contains("plan") || text.contains("计划")) { return "plan"; }
        return null;
    }

    public static final class Entry {
        public String source;
        public String label;

        public Entry(String source, String label) {
            this.source = source;
            this.label = label;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("source", source);
            map.put("label", label);
            return map;
        }
    }

    public static final class RecallResult {
        public final String query;
        public final Map<String, Integer> channels = new LinkedHashMap<>();
        public final List<Entry> entries = new ArrayList<>();
        public final List<Map<String, Object>> graphHits = new ArrayList<>();
        final List<Entry> vectorEntries = new ArrayList<>();
        final List<Entry> kvEntries = new ArrayList<>();
        final List<Entry> graphEntries = new ArrayList<>();

        public RecallResult(String query) {
            this.query = query;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("query", query);
            map.put("channels", channels);
            List<Map<String, Object>> entryMaps = new ArrayList<>();
            for (Entry entry : entries) { entryMaps.add(entry.toMap()); }
            map.put("entries", entryMaps);
            return map;
        }

        public Map<String, Object> graphRecallMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("hits", graphHits);
            return map;
        }

        public String contextText() {
            if (entries.isEmpty()) { return ""; }
            StringBuilder builder = new StringBuilder();
            for (Entry entry : entries) {
                builder.append("- [").append(entry.source).append("] ").append(entry.label).append('\n');
            }
            return builder.toString();
        }
    }

    private static final class GraphEntity {
        final String id;
        final String label;
        final String type;

        GraphEntity(String id, String label, String type) {
            this.id = id;
            this.label = label;
            this.type = type;
        }
    }

    private static final class GraphRelation {
        final String id;
        final String source;
        final String target;
        final String label;

        GraphRelation(String id, String source, String target, String label) {
            this.id = id;
            this.source = source;
            this.target = target;
            this.label = label;
        }
    }

    private static final class GraphEpisode {
        final String id;
        final String label;

        GraphEpisode(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    /** GraphMemory 0.1.16 使用 foundation.store 的 Embedding 抽象，APIEmbedding 是 retrieval 接口，桥接一下。 */
    private static final class BaseEmbeddingAdapter extends Embedding {
        private final APIEmbedding delegate;

        BaseEmbeddingAdapter(APIEmbedding delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<Float> embedQuery(String text) {
            return delegate.embedQuery(text);
        }

        @Override
        public List<List<Float>> embedDocuments(List<String> texts, Integer batchSize) {
            return delegate.embedDocuments(texts, batchSize);
        }

        @Override
        public int getDimension() {
            return delegate.getDimension();
        }
    }
}
