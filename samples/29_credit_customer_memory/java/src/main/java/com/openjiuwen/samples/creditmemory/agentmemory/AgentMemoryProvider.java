package com.openjiuwen.samples.creditmemory.agentmemory;

import com.openjiuwen.core.memory.external.MemoryProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 Agent Memory 的 HTTP API 适配成 openjiuwen Agent Core 0.1.16 的
 * {@link com.openjiuwen.core.memory.external.MemoryProvider}，供
 * {@code com.openjiuwen.memory.harness.rails.ExternalMemoryRail} 驱动。
 *
 * <p>Rail 注入点（0.1.16 实测，见 AGENT-MEMORY-INTEGRATION.md）：</p>
 * <ul>
 *   <li>{@code beforeInvoke} → {@link #initialize(Map)}：只做一次健康检查；</li>
 *   <li>{@code beforeModelCall} → {@link #prefetch(String, Map)}：检索客户长期记忆，
 *       返回 Markdown 记忆块，Rail 包成 {@code <memory-context>} 注入提示词；</li>
 *   <li>{@code afterInvoke} → {@link #syncTurn(String, String, Map)}：把本轮
 *       {@code user: ...\nassistant: ...} 写回服务端，{@code infer=true} 触发真实 LLM 抽取。</li>
 * </ul>
 *
 * <p>Scope 映射是「身份覆盖式」，原因见 {@link AgentMemoryScope}：org/user 用认证身份，
 * agent 用语义 Agent，rail 的 {@code user_id} 下沉到 session 轴承载「客户」维度。
 * {@code scope_id} 缺省承载 agent，{@code session_id} 在 dev 单身份部署下没有独立轴可放，
 * 与官方 Python 适配器 {@code agent_memory_provider.py} 的行为一致（design §6.2-D）。</p>
 */
public final class AgentMemoryProvider implements MemoryProvider {

    private static final Logger LOG = LoggerFactory.getLogger(AgentMemoryProvider.class);

    public static final String NAME = "agent-memory";
    public static final String DEFAULT_AGENT = "credit-agent";
    public static final int DEFAULT_PREFETCH_TOP_K = 5;

    /** Rail 传入的 scope map 里承载「客户/会话」语义的键：user_id（官方映射）或 session_id。 */
    public static final String SESSION_AXIS_USER_ID = "user_id";
    public static final String SESSION_AXIS_SESSION_ID = "session_id";

    private final AgentMemoryClient client;
    private final String agent;
    private final int prefetchTopK;
    private final String sessionAxis;

    private volatile boolean initialized;
    private volatile List<AgentMemoryHit> lastRecalledHits = List.of();
    private volatile String lastRecalledContext = "";
    private volatile String lastMemoryContextBlock = "";
    private volatile List<AgentMemoryUnit> lastSyncedUnits = List.of();
    private volatile String lastSyncContent = "";
    private volatile String lastError = "";

    public AgentMemoryProvider(AgentMemoryClient client) {
        this(client, DEFAULT_AGENT, DEFAULT_PREFETCH_TOP_K, SESSION_AXIS_USER_ID);
    }

    /**
     * @param client       REST 客户端
     * @param agent        语义 Agent（写入 scope.agent），本样例固定 credit-agent
     * @param prefetchTopK 每轮最多召回条数
     * @param sessionAxis  scope map 中承载「客户/会话」的键：{@code user_id}（缺省，匹配官方适配器）
     *                     或 {@code session_id}（生产直映射时使用）
     */
    public AgentMemoryProvider(AgentMemoryClient client, String agent, int prefetchTopK, String sessionAxis) {
        this.client = client;
        this.agent = agent == null || agent.isBlank() ? DEFAULT_AGENT : agent.strip();
        this.prefetchTopK = Math.max(1, prefetchTopK);
        this.sessionAxis = SESSION_AXIS_SESSION_ID.equals(sessionAxis) ? SESSION_AXIS_SESSION_ID : SESSION_AXIS_USER_ID;
    }

    // ------------------------------------------------------------------ MemoryProvider

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isAvailable() {
        return initialized;
    }

    /** 健康检查；失败向上抛，Rail 会把 provider 标记为未初始化并跳过记忆注入。 */
    @Override
    public void initialize(Map<String, Object> scope) throws Exception {
        String health = client.healthz();
        if (health.isBlank()) {
            throw new IllegalStateException("Agent Memory /healthz 返回为空");
        }
        initialized = true;
        lastError = "";
        LOG.info("[agent-memory] provider 初始化完成: {} scope={}", client.config().baseUrl(),
                resolveScope(scope).display());
    }

    @Override
    public List<Map<String, Object>> getToolSchemas() {
        return List.of();
    }

    @Override
    public String handleToolCall(String toolName, Map<String, Object> arguments) throws Exception {
        throw new UnsupportedOperationException("agent-memory provider 只提供 prefetch/syncTurn，未注册工具: " + toolName);
    }

    @Override
    public String prefetch(String query, Map<String, Object> scope) throws Exception {
        lastRecalledHits = List.of();
        lastRecalledContext = "";
        lastMemoryContextBlock = "";
        if (!initialized || query == null || query.isBlank()) {
            return "";
        }
        AgentMemoryScope memoryScope = resolveScope(scope);
        try {
            List<AgentMemoryHit> hits = client.search(query.strip(), memoryScope, prefetchTopK);
            List<String> lines = new ArrayList<>();
            for (AgentMemoryHit hit : hits) {
                String text = hit.displayText();
                if (!text.isBlank()) {
                    lines.add(text.strip());
                }
            }
            lastRecalledHits = List.copyOf(hits);
            if (lines.isEmpty()) {
                lastRecalledContext = "";
                return "";
            }
            String context = "## Agent Memory（客户长期记忆召回）\n"
                    + String.join("\n", lines.stream().map(line -> "- " + line).toList());
            lastRecalledContext = context;
            lastMemoryContextBlock = com.openjiuwen.memory.harness.rails.ExternalMemoryRail
                    .buildMemoryContextBlock(context);
            LOG.info("[agent-memory] prefetch scope={} query={} recalled={}",
                    memoryScope.display(), abbreviate(query), lines.size());
            return context;
        } catch (RuntimeException exception) {
            lastError = exception.getMessage();
            LOG.warn("[agent-memory] prefetch 失败，跳过本轮记忆注入: {}", exception.getMessage());
            return "";
        }
    }

    @Override
    public void syncTurn(String userMessage, String assistantMessage, Map<String, Object> scope) throws Exception {
        lastSyncedUnits = List.of();
        lastSyncContent = "";
        if (!initialized || userMessage == null || userMessage.isBlank()
                || assistantMessage == null || assistantMessage.isBlank()) {
            return;
        }
        AgentMemoryScope memoryScope = resolveScope(scope);
        String content = "user: " + userMessage.strip() + "\nassistant: " + assistantMessage.strip();
        lastSyncContent = content;
        Map<String, Object> systemMetadata = new LinkedHashMap<>();
        systemMetadata.put("infer", "true");
        systemMetadata.put("source_app", "credit-demo");
        systemMetadata.put("agent_label", agent);
        try {
            List<AgentMemoryUnit> units = client.add(content, memoryScope,
                    List.of("conversation"), systemMetadata);
            lastSyncedUnits = List.copyOf(units);
            lastError = "";
            LOG.info("[agent-memory] syncTurn scope={} written={} units={}",
                    memoryScope.display(), content.length(), units.size());
        } catch (RuntimeException exception) {
            // 与官方适配器一致：记忆回写是 opportunistic，失败不打断业务对话。
            lastError = exception.getMessage();
            LOG.warn("[agent-memory] syncTurn 失败，本轮记忆未写入: {}", exception.getMessage());
        }
    }

    /**
     * 静态提示词块：只描述「优先使用客户长期记忆」的业务规则，不含存储/检索/字段名等内部术语。
     */
    @Override
    public String systemPromptBlock() {
        return "回答客户问题时优先使用已沉淀的客户长期记忆（偏好、诉求、已确认事实），保持简洁、专业、像客户经理；"
                + "不要向客户透露记忆是如何存储或召回的，也不要声称“已记录/已记住”。";
    }

    @Override
    public void shutdown() {
        initialized = false;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    // ------------------------------------------------------------------ 观测/测试辅助

    /** Rail 本轮注入前的原始记忆块（Markdown），未召回时为空串。 */
    public String lastRecalledContext() {
        return lastRecalledContext;
    }

    /** Rail 本轮实际注入的完整 &lt;memory-context&gt; 块，未召回时为空串。 */
    public String lastMemoryContextBlock() {
        return lastMemoryContextBlock;
    }

    public List<AgentMemoryHit> lastRecalledHits() {
        return lastRecalledHits;
    }

    public int lastPrefetchCount() {
        return lastRecalledHits.size();
    }

    /** 最近一次 syncTurn 写入服务端后返回的抽取单元（可能多条）。 */
    public List<AgentMemoryUnit> lastSyncedUnits() {
        return lastSyncedUnits;
    }

    /** 最近一次 syncTurn 实际写回的文本形态。 */
    public String lastSyncContent() {
        return lastSyncContent;
    }

    public String lastError() {
        return lastError;
    }

    /** rail scope map → HTTP scope（org/user 用认证身份，agent/session 走语义轴）。 */
    AgentMemoryScope resolveScope(Map<String, Object> scope) {
        Map<String, Object> values = scope == null ? Map.of() : scope;
        String sessionValue = firstNonBlank(
                value(values, sessionAxis),
                value(values, SESSION_AXIS_USER_ID),
                value(values, SESSION_AXIS_SESSION_ID),
                "default");
        String agentValue = firstNonBlank(value(values, "scope_id"), agent);
        return new AgentMemoryScope(client.config().identityOrg(), client.config().identityUser(),
                agentValue, sessionValue);
    }

    private static String value(Map<String, Object> scope, String key) {
        Object value = scope.get(key);
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.strip();
            }
        }
        return "";
    }

    private static String abbreviate(String text) {
        String singleLine = text.replace('\n', ' ').strip();
        return singleLine.length() <= 60 ? singleLine : singleLine.substring(0, 60) + "...";
    }
}
