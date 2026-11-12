package com.openjiuwen.samples.creditmemory.agentmemory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentMemoryClient 规则单测：验证 HTTP 请求构造与响应解析（不联网）。
 */
class AgentMemoryClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AgentMemoryScope SCOPE =
            new AgentMemoryScope("local", "developer", "credit-agent", "cust-8801");

    private static AgentMemoryConfig config() {
        return new AgentMemoryConfig("http://127.0.0.1:8137", "local", "developer", "", 5_000, 30_000);
    }

    @Test
    void healthzUsesGetAndReturnsRawBody() {
        RecordingAgentMemoryHttp http = new RecordingAgentMemoryHttp()
                .enqueue(200, "{\"status\": \"ok\", \"profile\": \"credit-memory-demo\"}");
        AgentMemoryClient client = new AgentMemoryClient(config(), http);

        String health = client.healthz();

        assertEquals("GET", http.lastCall().method());
        assertEquals("/healthz", http.lastCall().path());
        assertTrue(health.contains("ok"), health);
    }

    @Test
    void searchBuildsScopedQueryAndParsesHits() throws IOException {
        RecordingAgentMemoryHttp http = new RecordingAgentMemoryHttp().enqueue(200, """
                {"items":[
                  {"unit_id":"u1","score":0.91,"content":"客户只接受等额本息",
                   "abstract":"等额本息","level":"l0","system_metadata":{"source_app":"credit-demo"}},
                  {"unit_id":"u2","score":0.42,"content":"客户申请120万经营贷"}
                ],"scores_comparable":false}
                """);
        AgentMemoryClient client = new AgentMemoryClient(config(), http);

        List<AgentMemoryHit> hits = client.search("客户能接受什么还款方式", SCOPE, 5);

        assertEquals(2, hits.size());
        assertEquals("u1", hits.get(0).unitId());
        assertEquals(0.91, hits.get(0).score(), 1e-9);
        assertEquals("客户只接受等额本息", hits.get(0).displayText());
        assertEquals("credit-demo", hits.get(0).systemMetadata().get("source_app"));

        JsonNode body = JSON.readTree(http.lastCall().body());
        assertEquals("POST", http.lastCall().method());
        assertEquals("/v1/search", http.lastCall().path());
        assertEquals("客户能接受什么还款方式", body.path("query").asText());
        assertEquals(5, body.path("top_k").asInt());
        assertEquals("local", body.path("context").path("scope").path("org").asText());
        assertEquals("developer", body.path("context").path("scope").path("user").asText());
        assertEquals("credit-agent", body.path("context").path("scope").path("agent").asText());
        assertEquals("cust-8801", body.path("context").path("scope").path("session").asText());
        assertTrue(body.path("context").path("extensions").isObject());
    }

    @Test
    void addPostsInferMetadataAndParsesTopLevelArray() throws IOException {
        RecordingAgentMemoryHttp http = new RecordingAgentMemoryHttp().enqueue(200, """
                [{"id":"n1","segments":[{"content":"客户申请120万元经营贷，期限24期。"}],
                  "lifecycle":"active","supersedes":"",
                  "scope":{"org":"local","user":"developer","agent":"credit-agent","session":"cust-8801"},
                  "system_metadata":{"md_title":"经营贷申请","origin":"inferred"}}]
                """);
        AgentMemoryClient client = new AgentMemoryClient(config(), http);

        List<AgentMemoryUnit> units = client.add("user: 想贷120万\nassistant: 好的", SCOPE,
                List.of("conversation"),
                Map.of("infer", "true", "source_app", "credit-demo"));

        assertEquals(1, units.size());
        assertEquals("n1", units.get(0).unitId());
        assertEquals("客户申请120万元经营贷，期限24期。", units.get(0).content());
        assertTrue(units.get(0).active());
        assertEquals("经营贷申请", units.get(0).mdTitle());
        assertTrue(units.get(0).scopeDisplay().contains("session=cust-8801"));

        JsonNode body = JSON.readTree(http.lastCall().body());
        assertEquals("/v1/add", http.lastCall().path());
        assertEquals("true", body.path("system_metadata").path("infer").asText());
        assertEquals("credit-demo", body.path("system_metadata").path("source_app").asText());
        assertEquals("conversation", body.path("tags").get(0).asText());
    }

    @Test
    void listSendsScopeOffsetAndLimit() throws IOException {
        RecordingAgentMemoryHttp http = new RecordingAgentMemoryHttp().enqueue(200, """
                {"items":[{"id":"n1","segments":[{"content":"历史记忆"}],"lifecycle":"superseded",
                  "supersedes":"","scope":{"org":"local","user":"developer","agent":"credit-agent","session":"cust-8801"}}]}
                """);
        AgentMemoryClient client = new AgentMemoryClient(config(), http);

        List<AgentMemoryUnit> units = client.list(SCOPE, 3, 10);

        assertEquals(1, units.size());
        assertEquals("superseded", units.get(0).lifecycle());
        assertFalse(units.get(0).active());
        JsonNode body = JSON.readTree(http.lastCall().body());
        assertEquals("/v1/list", http.lastCall().path());
        assertEquals(3, body.path("offset").asInt());
        assertEquals(10, body.path("limit").asInt());
        assertEquals("cust-8801", body.path("scope").path("session").asText());
    }

    @Test
    void updateReturnsNewVersionWithSupersedes() throws IOException {
        RecordingAgentMemoryHttp http = new RecordingAgentMemoryHttp().enqueue(200, """
                {"id":"new-1","segments":[{"content":"客户改选先息后本"}],
                 "lifecycle":"active","supersedes":"old-1",
                 "scope":{"org":"local","user":"developer","agent":"credit-agent","session":"cust-8801"}}
                """);
        AgentMemoryClient client = new AgentMemoryClient(config(), http);

        AgentMemoryUnit updated = client.update(SCOPE, "old-1", "客户改选先息后本");

        assertEquals("new-1", updated.unitId());
        assertEquals("old-1", updated.supersedes());
        JsonNode body = JSON.readTree(http.lastCall().body());
        assertEquals("/v1/update", http.lastCall().path());
        assertEquals("old-1", body.path("unit_id").asText());
        assertEquals("客户改选先息后本", body.path("patch").path("content").asText());
        assertEquals("local", body.path("scope").path("org").asText());
    }

    @Test
    void evolveReturnsJobId() {
        RecordingAgentMemoryHttp http = new RecordingAgentMemoryHttp().enqueue(200, "\"job-123\"");
        AgentMemoryClient client = new AgentMemoryClient(config(), http);

        assertEquals("job-123", client.evolve(SCOPE, "consolidate"));
        assertEquals("/v1/evolve", http.lastCall().path());
        assertTrue(http.lastCall().body().contains("\"mode\":\"consolidate\""));
    }

    @Test
    void launchPortalReturnsUrl() {
        RecordingAgentMemoryHttp http = new RecordingAgentMemoryHttp()
                .enqueue(200, "{\"portal_url\":\"http://127.0.0.1:8137/portal/#access=token\"}");
        AgentMemoryClient client = new AgentMemoryClient(config(), http);

        assertEquals("http://127.0.0.1:8137/portal/#access=token", client.launchPortal());
        assertEquals("/portal/api/launch", http.lastCall().path());
        assertEquals("POST", http.lastCall().method());
    }

    @Test
    void httpErrorSurfacesServerEnvelope() {
        RecordingAgentMemoryHttp http = new RecordingAgentMemoryHttp().enqueue(403,
                "{\"error\":\"PermissionDeniedError\",\"message\":\"permission denied\","
                        + "\"request_id\":\"req-1\",\"retryable\":false}");
        AgentMemoryClient client = new AgentMemoryClient(config(), http);

        AgentMemoryException exception = assertThrows(AgentMemoryException.class,
                () -> client.add("内容", SCOPE, null, null));

        assertEquals(403, exception.statusCode());
        assertEquals("PermissionDeniedError", exception.errorClass());
        assertEquals("req-1", exception.requestId());
        assertFalse(exception.retryable());
        assertFalse(exception.getMessage().contains("Bearer"));
    }

    @Test
    void transportFailureIsWrappedAndRetryable() {
        RecordingAgentMemoryHttp http = new RecordingAgentMemoryHttp()
                .failNextWith(new IOException("connection refused"));
        AgentMemoryClient client = new AgentMemoryClient(config(), http);

        AgentMemoryException exception = assertThrows(AgentMemoryException.class,
                () -> client.search("q", SCOPE, 3));

        assertEquals(-1, exception.statusCode());
        assertTrue(exception.retryable());
        assertTrue(exception.getMessage().contains("传输失败"));
    }

    @Test
    void missingItemsIsProtocolError() {
        RecordingAgentMemoryHttp http = new RecordingAgentMemoryHttp().enqueue(200, "{}");
        AgentMemoryClient client = new AgentMemoryClient(config(), http);

        AgentMemoryException exception = assertThrows(AgentMemoryException.class,
                () -> client.search("q", SCOPE, 3));

        assertTrue(exception.getMessage().contains("items"));
        assertFalse(exception.retryable());
    }

    @Test
    void emptyUnitIdIsRejectedBeforeHttpCall() {
        RecordingAgentMemoryHttp http = new RecordingAgentMemoryHttp();
        AgentMemoryClient client = new AgentMemoryClient(config(), http);

        assertThrows(IllegalArgumentException.class,
                () -> client.update(SCOPE, " ", "新内容"));
        assertEquals(0, http.callCount());
    }
}
