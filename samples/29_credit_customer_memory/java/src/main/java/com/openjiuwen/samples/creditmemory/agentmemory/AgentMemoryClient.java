package com.openjiuwen.samples.creditmemory.agentmemory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent Memory（openJiuwen agent-memory 服务）的 Java REST 客户端。
 *
 * <p>只封装实际用到的 HTTP 表面，请求/响应字段严格按 S02 契约：</p>
 *
 * <table>
 *   <caption>端点</caption>
 *   <tr><th>方法</th><th>端点</th><th>返回</th></tr>
 *   <tr><td>{@link #healthz()}</td><td>GET /healthz</td><td>原始 JSON</td></tr>
 *   <tr><td>{@link #launchPortal()}</td><td>POST /portal/api/launch</td><td>portal_url</td></tr>
 *   <tr><td>{@link #search}</td><td>POST /v1/search</td><td>{@code items[].unit_id/score/content}</td></tr>
 *   <tr><td>{@link #add}</td><td>POST /v1/add</td><td>顶层数组（抽取后的记忆单元）</td></tr>
 *   <tr><td>{@link #list}</td><td>POST /v1/list</td><td>{@code items}</td></tr>
 *   <tr><td>{@link #update}</td><td>POST /v1/update</td><td>新版本单元（{@code supersedes} 指向旧版）</td></tr>
 *   <tr><td>{@link #evolve}</td><td>POST /v1/evolve</td><td>任务 ID</td></tr>
 * </table>
 *
 * <p>失败统一抛 {@link AgentMemoryException}：4xx/5xx 保留服务端错误信封，
 * 网络/解析失败给出可读原因；不把异常吞掉，是否降级由 {@link AgentMemoryProvider} 决定。</p>
 */
public final class AgentMemoryClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentMemoryConfig config;
    private final AgentMemoryHttp http;

    public AgentMemoryClient(AgentMemoryConfig config) {
        this(config, new JdkAgentMemoryHttp(config));
    }

    /** 测试用构造：注入离线 {@link AgentMemoryHttp}。 */
    public AgentMemoryClient(AgentMemoryConfig config, AgentMemoryHttp http) {
        this.config = config;
        this.http = http;
    }

    public AgentMemoryConfig config() {
        return config;
    }

    // ------------------------------------------------------------------ 探活/Portal

    /** GET /healthz，返回原始 JSON 文本（例如 {@code {"status":"ok","profile":"credit-memory-demo"}}）。 */
    public String healthz() {
        JsonNode node = exchange("健康检查", "GET", "/healthz", null);
        return node == null ? "" : node.toString();
    }

    /** POST /portal/api/launch，返回 portal_url（浏览器打开后可查看/纠正记忆）。 */
    public String launchPortal() {
        JsonNode node = exchange("获取 Portal 链接", "POST", "/portal/api/launch", Map.of());
        String url = text(node, "portal_url");
        if (url.isBlank()) {
            throw AgentMemoryException.protocol("获取 Portal 链接", "缺少 portal_url", node.toString());
        }
        return url;
    }

    // ------------------------------------------------------------------ 记忆检索

    /** 语义检索。 */
    public List<AgentMemoryHit> search(String query, AgentMemoryScope scope, int topK) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("scope", scope.toMap());
        context.put("extensions", Map.of());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        payload.put("context", context);
        payload.put("top_k", Math.max(1, topK));
        JsonNode node = exchange("记忆检索", "POST", "/v1/search", payload);
        JsonNode items = node == null ? null : node.get("items");
        if (items == null || !items.isArray()) {
            throw AgentMemoryException.protocol("记忆检索", "缺少 items 数组", String.valueOf(node));
        }
        List<AgentMemoryHit> hits = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            hits.add(parseHit(item));
        }
        return hits;
    }

    // ------------------------------------------------------------------ 记忆写入

    /**
     * 写入一段文本。{@code system_metadata.infer=true} 时服务端同步调用真实 LLM
     * 抽取派生记忆单元（derived units），一次写入可能返回多条。
     */
    public List<AgentMemoryUnit> add(String content, AgentMemoryScope scope, List<String> tags,
                                     Map<String, Object> systemMetadata) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", content);
        payload.put("scope", scope.toMap());
        if (tags != null && !tags.isEmpty()) {
            payload.put("tags", tags);
        }
        if (systemMetadata != null && !systemMetadata.isEmpty()) {
            payload.put("system_metadata", systemMetadata);
        }
        JsonNode node = exchange("写入记忆", "POST", "/v1/add", payload);
        if (node == null) {
            throw AgentMemoryException.protocol("写入记忆", "响应为空", "");
        }
        if (!node.isArray()) {
            // 兼容返回单个单元或 {items:[...]} 的实现。
            JsonNode items = node.get("items");
            if (items != null && items.isArray()) {
                node = items;
            } else {
                return List.of(parseUnit(node));
            }
        }
        List<AgentMemoryUnit> units = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            units.add(parseUnit(item));
        }
        return units;
    }

    // ------------------------------------------------------------------ 列表/更新/演进

    /** 按 scope 列出记忆（包含 superseded 历史版本）。 */
    public List<AgentMemoryUnit> list(AgentMemoryScope scope, int offset, int limit) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scope", scope.toMap());
        payload.put("offset", Math.max(0, offset));
        payload.put("limit", Math.max(1, limit));
        JsonNode node = exchange("记忆列表", "POST", "/v1/list", payload);
        JsonNode items = node == null ? null : node.get("items");
        if (items == null || !items.isArray()) {
            throw AgentMemoryException.protocol("记忆列表", "缺少 items 数组", String.valueOf(node));
        }
        List<AgentMemoryUnit> units = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            units.add(parseUnit(item));
        }
        return units;
    }

    /**
     * 纠正一条记忆：服务端以 SUPERSEDE 语义生成新版本，旧版本 lifecycle 变为 superseded。
     *
     * @return 新版本单元（{@code supersedes} 指向 {@code unitId}）
     */
    public AgentMemoryUnit update(AgentMemoryScope scope, String unitId, String newContent) {
        if (unitId == null || unitId.isBlank()) {
            throw new IllegalArgumentException("unitId 不能为空");
        }
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("content", newContent);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scope", scope.toMap());
        payload.put("unit_id", unitId);
        payload.put("patch", patch);
        JsonNode node = exchange("纠正记忆", "POST", "/v1/update", payload);
        if (node == null || node.isNull()) {
            throw AgentMemoryException.protocol("纠正记忆", "响应为空", "");
        }
        return parseUnit(node);
    }

    /** POST /v1/delete：按 unit id 遗忘一条记忆（非破坏式，保留治理记录）。 */
    public void delete(AgentMemoryScope scope, String unitId) {
        if (unitId == null || unitId.isBlank()) {
            throw new IllegalArgumentException("unitId 不能为空");
        }
        Map<String, Object> selector = new LinkedHashMap<>();
        selector.put("scope", scope.toMap());
        selector.put("unit_ids", List.of(unitId));
        selector.put("mode", "forget");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("selector", selector);
        exchange("遗忘记忆", "POST", "/v1/delete", payload);
    }

    /** 列出当前认证身份可见的全部个人记忆（跨 agent/session；Portal 同款 personal 模式）。 */
    public List<AgentMemoryUnit> listPersonal(int offset, int limit) {
        return listPersonal(offset, limit, null);
    }

    /** 同上，但可按 lifecycle 拉取 active / superseded / forgotten / archived 历史版本。 */
    public List<AgentMemoryUnit> listPersonal(int offset, int limit, String lifecycle) {
        Map<String, Object> personal = new LinkedHashMap<>();
        if (lifecycle != null && !lifecycle.isBlank()) {
            personal.put("lifecycle", lifecycle);
        }
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("personal", personal);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("offset", Math.max(0, offset));
        payload.put("limit", Math.max(1, limit));
        payload.put("extensions", extensions);
        JsonNode node = exchange("个人记忆列表", "POST", "/v1/list", payload);
        JsonNode items = node == null ? null : node.get("items");
        if (items == null || !items.isArray()) {
            throw AgentMemoryException.protocol("个人记忆列表", "缺少 items 数组", String.valueOf(node));
        }
        List<AgentMemoryUnit> units = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            units.add(parseUnit(item));
        }
        return units;
    }

    /**
     * 清空当前 Agent Memory 用户的全部个人记忆（跨 agent/session），用于“一键重开演示”。
     * 按每条记忆真实 scope 分组，再按 unit_ids 分批 purge，避免只清当前会话。
     *
     * @return 实际提交删除的单元数
     */
    public int clearAll() {
        List<AgentMemoryUnit> units = new ArrayList<>();
        for (String lifecycle : List.of("active", "superseded", "forgotten", "archived")) {
            units.addAll(listPersonal(0, 100, lifecycle));
        }
        Map<String, Map<String, Object>> scopes = new LinkedHashMap<>();
        Map<String, List<String>> idsByScope = new LinkedHashMap<>();
        for (AgentMemoryUnit unit : units) {
            Object rawScope = unit.systemMetadata().get("_scope");
            if (!(rawScope instanceof Map<?, ?> scopeMap)) { continue; }
            @SuppressWarnings("unchecked")
            Map<String, Object> scope = (Map<String, Object>) scopeMap;
            String key = scope.toString();
            scopes.putIfAbsent(key, scope);
            idsByScope.computeIfAbsent(key, ignored -> new ArrayList<>()).add(unit.unitId());
        }
        int removed = 0;
        for (Map.Entry<String, List<String>> entry : idsByScope.entrySet()) {
            Map<String, Object> scope = scopes.get(entry.getKey());
            List<String> ids = entry.getValue();
            for (int from = 0; from < ids.size(); from += 100) {
                List<String> batch = ids.subList(from, Math.min(from + 100, ids.size()));
                Map<String, Object> selector = new LinkedHashMap<>();
                selector.put("scope", scope);
                selector.put("unit_ids", batch);
                selector.put("mode", "purge");
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("selector", selector);
                exchange("清空全部记忆", "POST", "/v1/delete", payload);
                removed += batch.size();
            }
        }
        return removed;
    }

    /**
     * 清空当前 scope 的全部记忆（purge，物理删除真源与派生索引；用于演示重置）。
     *
     * <p>Agent Memory 的 DeleteSelector 不接受“只有 scope”的选择器，必须显式给出
     * unit_ids/tags/before/filters 之一。因此先 list 当前 scope 的全部 unit，再按 unit_ids 分批 purge。</p>
     */
    public int clear(AgentMemoryScope scope) {
        List<AgentMemoryUnit> units = list(scope, 0, 500);
        List<String> ids = units.stream()
                .map(AgentMemoryUnit::unitId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
        if (ids.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (int from = 0; from < ids.size(); from += 100) {
            List<String> batch = ids.subList(from, Math.min(from + 100, ids.size()));
            Map<String, Object> selector = new LinkedHashMap<>();
            selector.put("scope", scope.toMap());
            selector.put("unit_ids", batch);
            selector.put("mode", "purge");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("selector", selector);
            exchange("清空记忆", "POST", "/v1/delete", payload);
            removed += batch.size();
        }
        return removed;
    }

    /**
     * 触发记忆演进（{@code mode=consolidate} 合并去重），返回异步任务 ID。
     */
    public String evolve(AgentMemoryScope scope, String mode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scope", scope.toMap());
        payload.put("mode", mode == null || mode.isBlank() ? "consolidate" : mode);
        JsonNode node = exchange("记忆演进", "POST", "/v1/evolve", payload);
        if (node == null || node.isNull()) {
            throw AgentMemoryException.protocol("记忆演进", "响应为空", "");
        }
        if (node.isTextual()) {
            return node.asText();
        }
        for (String field : List.of("job_id", "task_id", "id", "operation_id")) {
            String value = text(node, field);
            if (!value.isBlank()) {
                return value;
            }
        }
        return node.toString();
    }

    // ------------------------------------------------------------------ 内部：传输 + 解析

    private JsonNode exchange(String operation, String method, String path, Object payload) {
        String body = null;
        if (payload != null) {
            try {
                body = JSON.writeValueAsString(payload);
            } catch (IOException exception) {
                throw new AgentMemoryException("Agent Memory " + operation + " 请求体序列化失败: "
                        + exception.getMessage(), -1, "", "", false, exception);
            }
        }
        AgentMemoryHttp.Response response;
        try {
            response = http.exchange(method, path, body);
        } catch (IOException exception) {
            throw AgentMemoryException.transport(operation, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw AgentMemoryException.transport(operation, exception);
        }
        if (response == null) {
            throw AgentMemoryException.transport(operation, new IllegalStateException("transport returned null"));
        }
        if (response.status() < 200 || response.status() >= 300) {
            throw httpError(operation, response);
        }
        String raw = response.body() == null ? "" : response.body().strip();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return JSON.readTree(raw);
        } catch (IOException exception) {
            throw AgentMemoryException.protocol(operation, "响应不是合法 JSON", raw);
        }
    }

    private AgentMemoryException httpError(String operation, AgentMemoryHttp.Response response) {
        String errorClass = "";
        String serverMessage = "";
        String requestId = "";
        boolean retryable = response.status() >= 500 || response.status() == 429;
        String raw = response.body() == null ? "" : response.body().strip();
        if (!raw.isEmpty()) {
            try {
                JsonNode node = JSON.readTree(raw);
                errorClass = text(node, "error");
                serverMessage = text(node, "message");
                requestId = text(node, "request_id");
                JsonNode retry = node.get("retryable");
                if (retry != null && retry.isBoolean()) {
                    retryable = retry.asBoolean();
                }
            } catch (IOException ignored) {
                serverMessage = raw.length() > 200 ? raw.substring(0, 200) + "..." : raw;
            }
        }
        return AgentMemoryException.http(operation, response.status(), errorClass,
                serverMessage, requestId, retryable);
    }

    private AgentMemoryHit parseHit(JsonNode item) {
        return new AgentMemoryHit(
                text(item, "unit_id"),
                item.path("score").asDouble(0.0),
                text(item, "content"),
                firstNonBlank(text(item, "abstract"), text(item, "overview")),
                text(item, "level"),
                metadata(item.get("system_metadata")));
    }

    private AgentMemoryUnit parseUnit(JsonNode node) {
        String content = text(node, "content");
        if (content.isBlank()) {
            JsonNode segments = node.get("segments");
            if (segments != null && segments.isArray() && !segments.isEmpty()) {
                content = text(segments.get(0), "content");
            }
        }
        if (content.isBlank()) {
            content = firstNonBlank(text(node, "abstract"), text(node, "overview"));
        }
        Map<String, Object> metadata = new LinkedHashMap<>(metadata(node.get("system_metadata")));
        JsonNode tierNode = node.get("tier");
        if (tierNode != null && tierNode.isTextual() && !tierNode.asText().isBlank()) {
            metadata.put("_tier", tierNode.asText());
        }
        JsonNode scopeNode = node.get("scope");
        if (scopeNode != null && scopeNode.isObject()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> scopeValues = JSON.convertValue(scopeNode, LinkedHashMap.class);
                metadata.put("_scope", scopeValues);
            } catch (IllegalArgumentException ignored) {
                // scope 是展示增强，失败不影响记忆本体。
            }
        }
        return new AgentMemoryUnit(
                firstNonBlank(text(node, "id"), text(node, "unit_id")),
                content,
                text(node, "lifecycle"),
                text(node, "supersedes"),
                scopeDisplay(node.get("scope")),
                metadata);
    }

    private String scopeDisplay(JsonNode scope) {
        if (scope == null || !scope.isObject()) {
            return "";
        }
        return "org=" + text(scope, "org") + ", user=" + text(scope, "user")
                + ", agent=" + text(scope, "agent") + ", session=" + text(scope, "session");
    }

    private Map<String, Object> metadata(JsonNode node) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> values = JSON.convertValue(node, LinkedHashMap.class);
            return values;
        } catch (IllegalArgumentException exception) {
            return Map.of();
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.isValueNode() ? value.asText() : value.toString();
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : (second == null ? "" : second);
    }
}
