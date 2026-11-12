package com.openjiuwen.samples.creditmemory.agentmemory.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.samples.creditmemory.agentmemory.AgentMemoryClient;
import com.openjiuwen.samples.creditmemory.agentmemory.web.PortalUrls;
import com.openjiuwen.samples.creditmemory.agentmemory.AgentMemoryUnit;
import com.openjiuwen.samples.creditmemory.agentmemory.DualAnswerRunner;
import com.openjiuwen.samples.creditmemory.agentmemory.AgentMemoryScope;
import com.openjiuwen.samples.creditmemory.config.ModelSettings;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 双回答演示页的 HTTP 层：静态页面 + Agent Memory REST 代理 + DeepAgent 双回答。 */
public final class DualWebServer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "application/javascript; charset=utf-8");

    private final int port;
    private final ModelSettings settings;
    private final AgentMemoryClient client;
    private final DualAnswerRunner runner;
    private final DualSessionStore store = new DualSessionStore();
    private HttpServer server;
    private volatile String portalUrl = "";

    public DualWebServer(int port, ModelSettings settings, AgentMemoryClient client, DualAnswerRunner runner) {
        this.port = port;
        this.settings = settings;
        this.client = client;
        this.runner = runner;
        store.create();
    }

    public void start() throws IOException {
        refreshPortal();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api", this::api);
        server.createContext("/", this::staticFile);
        server.setExecutor(Executors.newFixedThreadPool(6));
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public String portalUrl() {
        return portalUrl;
    }

    private void refreshPortal() {
        try {
            portalUrl = PortalUrls.publicUrl(client.launchPortal());
        } catch (Exception exception) {
            portalUrl = "";
            System.err.println("[agent-memory] 获取 Portal 地址失败: " + exception.getMessage());
        }
    }

    private void api(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("GET".equals(method) && "/api/bootstrap".equals(path)) {
                send(exchange, 200, bootstrap());
                return;
            }
            if ("POST".equals(method) && "/api/sessions".equals(path)) {
                var session = store.create();
                send(exchange, 200, Map.of("sessionId", session.id, "title", session.title));
                return;
            }
            if ("GET".equals(method) && path.startsWith("/api/sessions/")) {
                String id = path.substring("/api/sessions/".length());
                send(exchange, 200, sessionState(id));
                return;
            }
            if ("GET".equals(method) && "/api/memories".equals(path)) {
                send(exchange, 200, Map.of("items", memoryItems()));
                return;
            }
            if ("GET".equals(method) && "/api/portal".equals(path)) {
                refreshPortal();
                send(exchange, 200, Map.of("portalUrl", portalUrl));
                return;
            }
            if ("POST".equals(method) && "/api/demo/recall".equals(path)) {
                send(exchange, 200, demoRecall(exchange));
                return;
            }
            if ("POST".equals(method) && "/api/chat".equals(path)) {
                chat(exchange);
                return;
            }
            if ("POST".equals(method) && "/api/memory/correct".equals(path)) {
                correct(exchange);
                return;
            }
            if ("POST".equals(method) && "/api/memory/evolve".equals(path)) {
                JsonNode body = JSON.readTree(read(exchange));
                String mode = body.path("mode").asText("consolidate");
                if (!java.util.Set.of("consolidate", "associate", "extract", "forget").contains(mode)) {
                    throw new IllegalArgumentException("不支持的演进模式: " + mode);
                }
                String jobId = client.evolve(runner.memoryScope(), mode);
                send(exchange, 200, Map.of("jobId", jobId, "mode", mode));
                return;
            }
            if ("POST".equals(method) && "/api/memory/forget".equals(path)) {
                JsonNode body = JSON.readTree(read(exchange));
                client.delete(runner.memoryScope(), body.path("unitId").asText());
                send(exchange, 200, Map.of("ok", true, "items", memoryItems()));
                return;
            }
            if ("POST".equals(method) && "/api/settings".equals(path)) {
                saveSettings(exchange);
                return;
            }
            if ("POST".equals(method) && "/api/reset".equals(path)) {
                client.clearAll();
                store.reset();
                Thread.sleep(500L);
                send(exchange, 200, bootstrap());
                return;
            }
            if ("GET".equals(method) && "/api/health".equals(path)) {
                send(exchange, 200, Map.of("ok", true, "agentMemory", client.healthz()));
                return;
            }
            send(exchange, 404, Map.of("error", "not found"));
        } catch (IllegalArgumentException exception) {
            send(exchange, 400, Map.of("error", exception.getMessage()));
        } catch (Exception exception) {
            exception.printStackTrace();
            send(exchange, 500, Map.of("error", exception.getClass().getSimpleName() + ": " + exception.getMessage()));
        }
    }

    private Map<String, Object> bootstrap() {
        refreshPortal();
        List<Map<String, Object>> sessions = new ArrayList<>();
        for (var session : store.list()) {
            sessions.add(store.summary(session));
        }
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("baseUrl", client.config().baseUrl());
        memory.put("portalUrl", portalUrl);
        try {
            memory.put("health", client.healthz());
            memory.put("reachable", true);
        } catch (Exception exception) {
            memory.put("health", exception.getMessage());
            memory.put("reachable", false);
        }
        Map<String, Object> chat = new LinkedHashMap<>();
        chat.put("model", settings.chat.model);
        chat.put("apiBase", settings.chat.apiBase);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentMemory", memory);
        result.put("chatModel", chat);
        result.put("graphStore", "Agent Memory 服务内（SQLite 真源 + 进程内索引）");
        result.put("settings", settingsView());
        result.put("sessions", sessions);
        result.put("activeSessionId", sessions.isEmpty() ? null : sessions.get(sessions.size() - 1).get("id"));
        return result;
    }

    private Map<String, Object> sessionState(String id) {
        var session = store.find(id).orElseThrow(() -> new IllegalArgumentException("session not found: " + id));
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("session", store.summary(session));
        state.put("messages", session.messages);
        state.put("memories", memoryItems());
        return state;
    }

    /**
     * 自动召回演示：先用真实 Agent/MemoryRail 写入背景记忆，再问一个需要长期记忆的问题，
     * 返回「有记忆 vs 无记忆基线」双回答；不包含任何 update/evolve 硬编码。
     */
    private Map<String, Object> demoRecall(HttpExchange exchange) throws IOException {
        JsonNode body = JSON.readTree(read(exchange));
        String seed = body.path("seed").asText().strip();
        String question = body.path("question").asText().strip();
        if (seed.isBlank() || question.isBlank()) {
            throw new IllegalArgumentException("seed/question 不能为空");
        }
        // 召回演示需要稳定的背景事实，直接作为示例夹具写入 Agent Memory（不用于演示演进）。
        client.add(seed, runner.memoryScope(), List.of("demo-seed"), Map.of("source_app", "credit-demo-recall"));
        DualAnswerRunner.DualAnswer answer = runner.ask(question);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("seed", seed);
        result.put("seedAnswer", "");
        result.put("answer", answer.toMap());
        result.put("prefetchCount", answer.prefetchCount());
        result.put("memories", memoryItems());
        return result;
    }

    private void chat(HttpExchange exchange) throws IOException {
        JsonNode body = JSON.readTree(read(exchange));
        String sessionId = body.path("sessionId").asText();
        String message = body.path("message").asText("").strip();
        if (message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        var session = store.find(sessionId).orElseThrow(() -> new IllegalArgumentException("session not found: " + sessionId));
        store.appendCustomer(session, message);
        DualAnswerRunner.DualAnswer answer = runner.ask(message);
        store.appendDual(session, answer);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sessionId", sessionId);
        response.put("answer", answer.toMap());
        response.put("memories", memoryItems());
        send(exchange, 200, response);
    }

    private void correct(HttpExchange exchange) throws IOException {
        JsonNode body = JSON.readTree(read(exchange));
        String unitId = body.path("unitId").asText();
        String content = body.path("content").asText("").strip();
        AgentMemoryUnit updated = client.update(runner.memoryScope(), unitId, content);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("unitId", updated.unitId());
        item.put("content", updated.content());
        item.put("lifecycle", updated.lifecycle());
        item.put("supersedes", updated.supersedes());
        send(exchange, 200, Map.of("unit", item, "items", memoryItems()));
    }

    private List<Map<String, Object>> memoryItems() {
        List<Map<String, Object>> items = new ArrayList<>();
        try {
            for (AgentMemoryUnit unit : client.list(runner.memoryScope(), 0, 100)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("unitId", unit.unitId());
                item.put("content", unit.content());
                item.put("lifecycle", unit.lifecycle());
                item.put("supersedes", unit.supersedes());
                item.put("title", unit.mdTitle());
                item.put("systemMetadata", unit.systemMetadata());
                items.add(item);
            }
        } catch (Exception exception) {
            System.err.println("[agent-memory] list 失败: " + exception.getMessage());
        }
        return items;
    }

    private Map<String, Object> settingsView() {
        Map<String, Object> chat = new LinkedHashMap<>();
        chat.put("apiBase", settings.chat.apiBase);
        chat.put("model", settings.chat.model);
        chat.put("temperature", settings.chat.temperature);
        chat.put("apiKeyConfigured", settings.chat.apiKey != null && !settings.chat.apiKey.isBlank());
        Map<String, Object> am = new LinkedHashMap<>();
        am.put("llmBaseUrl", env("LLM_BASE_URL", "https://api.deepseek.com"));
        am.put("llmModel", env("LLM_MODEL", "deepseek-flash"));
        am.put("embedderBaseUrl", env("EMBEDDER_BASE_URL", "http://127.0.0.1:1234/v1"));
        am.put("embedderModel", env("EMBEDDER_MODEL", "text-embedding-nomic-embed-text-v1.5"));
        am.put("apiKeyConfigured", !env("LLM_API_KEY", "").isBlank());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("chat", chat);
        view.put("agentMemory", am);
        view.put("restartRequired", true);
        return view;
    }

    private void saveSettings(HttpExchange exchange) throws IOException {
        JsonNode body = JSON.readTree(read(exchange));
        JsonNode chat = body.path("chat");
        JsonNode am = body.path("agentMemory");
        Path base = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path chatConfig = base.resolve("config/model.local.json");
        Files.createDirectories(chatConfig.getParent());
        Map<String, Object> chatRoot = new LinkedHashMap<>();
        Map<String, Object> chatNode = new LinkedHashMap<>();
        chatNode.put("apiBase", chat.path("apiBase").asText(settings.chat.apiBase));
        chatNode.put("model", chat.path("model").asText(settings.chat.model));
        if (chat.has("temperature")) { chatNode.put("temperature", chat.path("temperature").asDouble()); }
        String chatKey = chat.path("apiKey").asText("");
        if (!chatKey.isBlank() && !"******".equals(chatKey)) { chatNode.put("apiKey", chatKey); }
        chatRoot.put("chat", chatNode);
        JSON.writerWithDefaultPrettyPrinter().writeValue(chatConfig.toFile(), chatRoot);

        Path envFile = base.getParent().resolve("memory-service/model.local.env");
        Files.createDirectories(envFile.getParent());
        StringBuilder envText = new StringBuilder("# 由业务页面模型设置写入；重启 Agent Memory 后生效，勿提交\n");
        envText.append("LLM_BASE_URL=").append(am.path("llmBaseUrl").asText()).append('\n');
        envText.append("LLM_MODEL=").append(am.path("llmModel").asText()).append('\n');
        String llmKey = am.path("llmApiKey").asText("");
        if (!llmKey.isBlank() && !"******".equals(llmKey)) { envText.append("LLM_API_KEY=").append(llmKey).append('\n'); }
        envText.append("EMBEDDER_BASE_URL=").append(am.path("embedderBaseUrl").asText()).append('\n');
        envText.append("EMBEDDER_MODEL=").append(am.path("embedderModel").asText()).append('\n');
        String embedKey = am.path("embedderApiKey").asText("");
        if (!embedKey.isBlank() && !"******".equals(embedKey)) { envText.append("EMBEDDER_API_KEY=").append(embedKey).append('\n'); }
        Files.writeString(envFile, envText.toString());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("restartRequired", true);
        response.put("message", "已保存本机配置；重启 Java 进程与 Agent Memory 服务后生效。");
        send(exchange, 200, response);
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private void staticFile(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/".equals(path) || path.isEmpty()) {
            path = "/index.html";
        }
        if (path.contains("..")) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        try (InputStream input = DualWebServer.class.getResourceAsStream("/web-dual" + path)) {
            if (input == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] bytes = input.readAllBytes();
            String ext = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : "";
            exchange.getResponseHeaders().set("Content-Type", TYPES.getOrDefault(ext, "application/octet-stream"));
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
    }

    private static String read(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
