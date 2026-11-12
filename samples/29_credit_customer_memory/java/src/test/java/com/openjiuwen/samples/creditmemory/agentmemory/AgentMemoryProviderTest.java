package com.openjiuwen.samples.creditmemory.agentmemory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentMemoryProvider 规则单测：验证 prefetch/syncTurn 的 HTTP 请求构造、
 * 记忆块格式化、失败降级语义（不联网，也不依赖 DeepAgent）。
 */
class AgentMemoryProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Object> RAIL_SCOPE = Map.of(
            "user_id", "cust-8801",
            "scope_id", "credit-agent",
            "session_id", "cust-8801-s1");

    private static AgentMemoryConfig config() {
        return new AgentMemoryConfig("http://127.0.0.1:8137", "local", "developer", "", 5_000, 30_000);
    }

    private static RecordingAgentMemoryHttp httpWith(String searchBody) {
        return new RecordingAgentMemoryHttp()
                .enqueue(200, "{\"status\":\"ok\"}")
                .enqueue(200, searchBody);
    }

    private static final String SEARCH_BODY = """
            {"items":[
              {"unit_id":"u1","score":0.9,"content":"客户只接受等额本息","abstract":"等额本息"},
              {"unit_id":"u2","score":0.6,"content":"客户申请120万元经营贷，期限24期"}
            ]}
            """;

    @Test
    void prefetchBeforeInitializeReturnsEmptyWithoutHttpCall() throws Exception {
        RecordingAgentMemoryHttp http = new RecordingAgentMemoryHttp();
        AgentMemoryClient client = new AgentMemoryClient(config(), http);
        AgentMemoryProvider provider = new AgentMemoryProvider(client);

        assertFalse(provider.isInitialized());
        assertEquals("", provider.prefetch("问题", RAIL_SCOPE));
        assertEquals(0, http.callCount());
        assertEquals("", provider.lastRecalledContext());
    }

    @Test
    void prefetchBuildsScopedSearchAndFormatsMemoryBlock() throws Exception {
        RecordingAgentMemoryHttp http = httpWith(SEARCH_BODY);
        AgentMemoryClient client = new AgentMemoryClient(config(), http);
        AgentMemoryProvider provider = new AgentMemoryProvider(client);
        provider.initialize(RAIL_SCOPE);

        String context = provider.prefetch("客户能接受什么还款方式？", RAIL_SCOPE);

        assertTrue(context.startsWith("## Agent Memory"), context);
        assertTrue(context.contains("- 客户只接受等额本息"));
        assertTrue(context.contains("- 客户申请120万元经营贷，期限24期"));
        assertEquals(2, provider.lastPrefetchCount());
        assertEquals(context, provider.lastRecalledContext());
        assertTrue(provider.lastMemoryContextBlock().contains("<memory-context>"));
        assertTrue(provider.lastMemoryContextBlock().contains("客户只接受等额本息"));
        assertFalse(provider.lastRecalledContext().contains("<memory-context>"));

        RecordingAgentMemoryHttp.Call searchCall = http.call(1);
        assertEquals("/v1/search", searchCall.path());
        JsonNode body = JSON.readTree(searchCall.body());
        assertEquals("客户能接受什么还款方式？", body.path("query").asText());
        assertEquals(5, body.path("top_k").asInt());
        JsonNode scope = body.path("context").path("scope");
        // dev auth 身份覆盖式映射：org/user 用认证身份，客户维度下沉到 session 轴。
        assertEquals("local", scope.path("org").asText());
        assertEquals("developer", scope.path("user").asText());
        assertEquals("credit-agent", scope.path("agent").asText());
        assertEquals("cust-8801", scope.path("session").asText());
    }

    @Test
    void syncTurnWritesInferTrueWithUserAssistantContent() throws Exception {
        RecordingAgentMemoryHttp http = new RecordingAgentMemoryHttp()
                .enqueue(200, "{\"status\":\"ok\"}")
                .enqueue(200, "[{\"id\":\"n1\",\"segments\":[{\"content\":\"客户申请120万元经营贷\"}],"
                        + "\"lifecycle\":\"active\"}]");
        AgentMemoryClient client = new AgentMemoryClient(config(), http);
        AgentMemoryProvider provider = new AgentMemoryProvider(client);
        provider.initialize(RAIL_SCOPE);

        provider.syncTurn("我想申请120万元经营贷", "好的，我了解了您的需求。", RAIL_SCOPE);

        assertEquals("", provider.lastError());
        assertEquals(1, provider.lastSyncedUnits().size());
        assertTrue(provider.lastSyncContent().startsWith("user: 我想申请120万元经营贷"));
        assertTrue(provider.lastSyncContent().contains("assistant: 好的，我了解了您的需求。"));

        RecordingAgentMemoryHttp.Call addCall = http.lastCall();
        assertEquals("/v1/add", addCall.path());
        JsonNode body = JSON.readTree(addCall.body());
        assertEquals("true", body.path("system_metadata").path("infer").asText());
        assertEquals("credit-demo", body.path("system_metadata").path("source_app").asText());
        assertEquals("credit-agent", body.path("system_metadata").path("agent_label").asText());
        assertEquals("developer", body.path("scope").path("user").asText());
        assertEquals("cust-8801", body.path("scope").path("session").asText());
        assertEquals("conversation", body.path("tags").get(0).asText());
    }

    @Test
    void sessionAxisCanUseRailSessionIdForProductionMapping() throws Exception {
        RecordingAgentMemoryHttp http = httpWith("{\"items\":[]}");
        AgentMemoryClient client = new AgentMemoryClient(config(), http);
        AgentMemoryProvider provider = new AgentMemoryProvider(client, "credit-agent", 5,
                AgentMemoryProvider.SESSION_AXIS_SESSION_ID);
        provider.initialize(RAIL_SCOPE);

        provider.prefetch("问题", RAIL_SCOPE);

        JsonNode scope = JSON.readTree(http.lastCall().body()).path("context").path("scope");
        assertEquals("cust-8801-s1", scope.path("session").asText());
        assertEquals("credit-agent", scope.path("agent").asText());
    }

    @Test
    void prefetchFailureDegradesWithoutThrowing() throws Exception {
        RecordingAgentMemoryHttp http = new RecordingAgentMemoryHttp()
                .enqueue(200, "{\"status\":\"ok\"}")
                .enqueue(503, "{\"error\":\"BackendError\",\"message\":\"service temporarily unavailable\","
                        + "\"request_id\":\"req-503\",\"retryable\":true}");
        AgentMemoryProvider provider = new AgentMemoryProvider(new AgentMemoryClient(config(), http));
        provider.initialize(RAIL_SCOPE);

        String context = provider.prefetch("问题", RAIL_SCOPE);

        assertEquals("", context);
        assertEquals(0, provider.lastPrefetchCount());
        assertFalse(provider.lastError().isBlank());
    }

    @Test
    void syncTurnFailureDegradesWithoutThrowing() throws Exception {
        RecordingAgentMemoryHttp http = new RecordingAgentMemoryHttp()
                .enqueue(200, "{\"status\":\"ok\"}")
                .enqueue(500, "{\"error\":\"InternalError\",\"message\":\"internal server error\"}");
        AgentMemoryProvider provider = new AgentMemoryProvider(new AgentMemoryClient(config(), http));
        provider.initialize(RAIL_SCOPE);

        provider.syncTurn("客户陈述", "好的", RAIL_SCOPE);

        assertEquals(0, provider.lastSyncedUnits().size());
        assertFalse(provider.lastError().isBlank());
    }

    @Test
    void blankQueryAndBlankAssistantSkipHttp() throws Exception {
        RecordingAgentMemoryHttp http = new RecordingAgentMemoryHttp()
                .enqueue(200, "{\"status\":\"ok\"}");
        AgentMemoryProvider provider = new AgentMemoryProvider(new AgentMemoryClient(config(), http));
        provider.initialize(RAIL_SCOPE);
        int afterInit = http.callCount();

        assertEquals("", provider.prefetch("  ", RAIL_SCOPE));
        provider.syncTurn("客户陈述", "", RAIL_SCOPE);

        assertEquals(afterInit, http.callCount());
    }

    @Test
    void initializeFailsWhenHealthCheckFails() {
        RecordingAgentMemoryHttp http = new RecordingAgentMemoryHttp()
                .enqueue(503, "{\"error\":\"HealthCheckError\",\"retryable\":true}");
        AgentMemoryProvider provider = new AgentMemoryProvider(new AgentMemoryClient(config(), http));

        assertThrows(Exception.class, () -> provider.initialize(RAIL_SCOPE));
        assertFalse(provider.isInitialized());
    }

    @Test
    void systemPromptBlockAvoidsInternalTerms() {
        AgentMemoryProvider provider = new AgentMemoryProvider(
                new AgentMemoryClient(config(), new RecordingAgentMemoryHttp()));

        String prompt = provider.systemPromptBlock();

        assertTrue(prompt.contains("客户长期记忆"));
        for (String forbidden : new String[]{"向量", "数据库", "/v1/", "unit_id", "scope", "scope_id", "embedding"}) {
            assertFalse(prompt.contains(forbidden), "systemPromptBlock 不应含内部术语: " + forbidden);
        }
    }

    @Test
    void providerDoesNotExposeMemoryTools() throws Exception {
        RecordingAgentMemoryHttp http = new RecordingAgentMemoryHttp()
                .enqueue(200, "{\"status\":\"ok\"}");
        AgentMemoryProvider provider = new AgentMemoryProvider(new AgentMemoryClient(config(), http));

        assertTrue(provider.getToolSchemas().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> provider.handleToolCall("agent_memory_search", Map.of()));
    }

    @Test
    void providerNameIsStable() {
        AgentMemoryProvider provider = new AgentMemoryProvider(
                new AgentMemoryClient(config(), new RecordingAgentMemoryHttp()));
        assertEquals(AgentMemoryProvider.NAME, provider.getName());
        assertEquals("agent-memory", provider.getName());
    }

    @Test
    void scopeResolutionFallsBackToDefaults() throws Exception {
        RecordingAgentMemoryHttp http = new RecordingAgentMemoryHttp()
                .enqueue(200, "{\"status\":\"ok\"}");
        AgentMemoryClient client = new AgentMemoryClient(config(), http);
        AgentMemoryProvider provider = new AgentMemoryProvider(client);
        provider.initialize(Map.of());

        AgentMemoryScope scope = provider.resolveScope(Map.of());

        assertEquals("local", scope.org());
        assertEquals("developer", scope.user());
        assertEquals("credit-agent", scope.agent());
        assertEquals("default", scope.session());
    }
}
