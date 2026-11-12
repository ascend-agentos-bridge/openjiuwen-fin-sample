package com.openjiuwen.samples.creditmemory.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openjiuwen.samples.creditmemory.config.ModelSettings;
import com.openjiuwen.samples.creditmemory.service.ChatService;
import com.openjiuwen.samples.creditmemory.service.MemoryService;
import com.openjiuwen.samples.creditmemory.service.Pipeline;
import com.openjiuwen.samples.creditmemory.service.SessionStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * 样例的 HTTP 层：静态页面 + JSON API。
 */
public final class WebServer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "application/javascript; charset=utf-8",
            "json", "application/json; charset=utf-8",
            "svg", "image/svg+xml"
    );

    private final int port;
    private final ModelSettings settings;
    private final Path configPath;
    private final SessionStore sessionStore;
    private final ChatService chatService;
    private final MemoryService memoryService;
    private final Map<String, RuntimeState> runtimes = new ConcurrentHashMap<>();
    private HttpServer server;

    public WebServer(int port, ModelSettings settings, Path configPath, SessionStore sessionStore,
                     ChatService chatService, MemoryService memoryService) {
        this.port = port;
        this.settings = settings;
        this.configPath = configPath;
        this.sessionStore = sessionStore;
        this.chatService = chatService;
        this.memoryService = memoryService;
        sessionStore.list().forEach(session -> runtimes.computeIfAbsent(session.id, key -> new RuntimeState()));
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api", this::handleApi);
        server.createContext("/", this::handleStatic);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handleApi(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("GET".equals(method) && "/api/bootstrap".equals(path)) {
                sendJson(exchange, 200, bootstrap());
                return;
            }
            if ("POST".equals(method) && "/api/sessions".equals(path)) {
                SessionStore.Session session = sessionStore.create();
                runtimes.put(session.id, new RuntimeState());
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("sessionId", session.id);
                response.put("title", session.title);
                sendJson(exchange, 200, response);
                return;
            }
            if ("POST".equals(method) && "/api/chat".equals(path)) {
                handleChat(exchange);
                return;
            }
            if ("POST".equals(method) && "/api/settings".equals(path)) {
                handleSettings(exchange);
                return;
            }
            if ("GET".equals(method) && path.startsWith("/api/sessions/") && path.endsWith("/state")) {
                String sessionId = path.substring("/api/sessions/".length(), path.length() - "/state".length());
                sendJson(exchange, 200, state(sessionId));
                return;
            }
            if ("GET".equals(method) && "/api/health".equals(path)) {
                sendJson(exchange, 200, Map.of("ok", true, "memoryError", String.valueOf(memoryService.initError())));
                return;
            }
            sendJson(exchange, 404, Map.of("error", "not found: " + method + " " + path));
        } catch (IllegalArgumentException exception) {
            sendJson(exchange, 400, Map.of("error", exception.getMessage()));
        } catch (Exception exception) {
            exception.printStackTrace();
            sendJson(exchange, 500, Map.of("error", exception.getClass().getSimpleName() + ": " + exception.getMessage()));
        }
    }

    private Map<String, Object> bootstrap() {
        List<SessionStore.Session> sessions = sessionStore.list();
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (SessionStore.Session session : sessions) {
            summaries.add(sessionStore.summary(session));
        }
        String activeSessionId = sessions.isEmpty() ? null : sessions.get(sessions.size() - 1).id;
        Map<String, Object> response = new LinkedHashMap<>();
        Map<String, Object> config = new LinkedHashMap<>(settings.publicView());
        response.put("configured", settings.isConfigured());
        response.put("chat", config.get("chat"));
        response.put("memory", config.get("memory"));
        response.put("graphStore", memoryService.graphStoreName());
        response.put("activeSessionId", activeSessionId);
        response.put("sessions", summaries);
        response.put("memoryError", memoryService.initError());
        return response;
    }

    private void handleChat(HttpExchange exchange) throws IOException {
        JsonNode body = JSON.readTree(readBody(exchange));
        String sessionId = body.path("sessionId").asText();
        String message = body.path("message").asText("").strip();
        if (sessionId.isBlank() || message.isBlank()) {
            sendJson(exchange, 400, Map.of("error", "sessionId 和 message 不能为空"));
            return;
        }
        SessionStore.Session session = requireSession(sessionId);
        RuntimeState runtime = runtimes.computeIfAbsent(sessionId, key -> new RuntimeState());
        if (runtime.running) {
            sendJson(exchange, 409, Map.of("error", "上一条消息的记忆还在加工中，请稍后再发"));
            return;
        }
        sessionStore.appendCustomer(session, message);
        MemoryService.RecallResult recall = memoryService.recall(message);
        String reply;
        try {
            reply = chatService.answer(session.messages, recall);
        } catch (Exception exception) {
            exception.printStackTrace();
            sendJson(exchange, 502, Map.of("error", "对话模型调用失败: " + exception.getMessage()));
            return;
        }
        int cites = recall.channels.getOrDefault("vector", 0) + recall.channels.getOrDefault("kv", 0);
        int graphCites = recall.graphHits.size();
        SessionStore.Message agentMessage = sessionStore.appendAgent(session, reply, cites, graphCites, null);
        runtime.recall = recall;
        runtime.running = true;

        List<String> context = new ArrayList<>();
        List<SessionStore.Message> messages = session.messages;
        int from = Math.max(0, messages.size() - 6);
        for (int index = from; index < messages.size(); index++) {
            SessionStore.Message item = messages.get(index);
            context.add(("customer".equals(item.role) ? "客户：" : "智能体：") + item.text);
        }
        Pipeline.Run run = memoryService.startProcessing(session, message, reply, context, finished -> {
            runtime.running = false;
            if (!runtime.runs.contains(finished)) {
                runtime.runs.add(0, finished);
                while (runtime.runs.size() > 8) {
                    runtime.runs.remove(runtime.runs.size() - 1);
                }
            }
            Map<String, Object> processed = new LinkedHashMap<>();
            processed.put("added", finished.stats.memoriesAdded);
            processed.put("updated", finished.stats.memoriesUpdated);
            processed.put("deleted", finished.stats.memoriesDeleted);
            processed.put("graph", finished.stats.graphEntitiesAdded + finished.stats.graphRelationsAdded);
            agentMessage.processed = processed;
            sessionStore.save();
        });
        runtime.runs.add(0, run);
        while (runtime.runs.size() > 8) {
            runtime.runs.remove(runtime.runs.size() - 1);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sessionId", sessionId);
        response.put("reply", reply);
        response.put("recall", recall.toMap());
        response.put("processing", true);
        sendJson(exchange, 200, response);
    }

    private void handleSettings(HttpExchange exchange) throws IOException {
        JsonNode body = JSON.readTree(readBody(exchange));
        JsonNode chat = body.path("chat");
        JsonNode memory = body.path("memory");
        if (chat.isObject()) {
            settings.chat.provider = text(chat, "provider", settings.chat.provider);
            settings.chat.apiBase = text(chat, "apiBase", settings.chat.apiBase);
            String apiKey = text(chat, "apiKey", "");
            if (!apiKey.isBlank() && !"******".equals(apiKey)) { settings.chat.apiKey = apiKey; }
            settings.chat.model = text(chat, "model", settings.chat.model);
            if (chat.has("temperature")) { settings.chat.temperature = chat.path("temperature").asDouble(); }
            if (chat.has("maxTokens")) { settings.chat.maxTokens = chat.path("maxTokens").asInt(); }
        }
        if (memory.isObject()) {
            settings.memory.provider = text(memory, "provider", settings.memory.provider);
            settings.memory.apiBase = text(memory, "apiBase", settings.memory.apiBase);
            String apiKey = text(memory, "apiKey", "");
            if (!apiKey.isBlank() && !"******".equals(apiKey)) { settings.memory.apiKey = apiKey; }
            settings.memory.model = text(memory, "model", settings.memory.model);
            settings.memory.embeddingApiBase = text(memory, "embeddingApiBase", settings.memory.embeddingApiBase);
            String embeddingKey = text(memory, "embeddingApiKey", "");
            if (!embeddingKey.isBlank() && !"******".equals(embeddingKey)) { settings.memory.embeddingApiKey = embeddingKey; }
            settings.memory.embeddingModel = text(memory, "embeddingModel", settings.memory.embeddingModel);
            if (memory.has("maxTokens")) { settings.memory.maxTokens = memory.path("maxTokens").asInt(); }
        }
        settings.save(configPath);
        chatService.reload(settings);
        memoryService.updateSettings(settings);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("configured", settings.isConfigured());
        response.putAll(settings.publicView());
        response.put("graphStore", memoryService.graphStoreName());
        response.put("memoryError", memoryService.initError());
        sendJson(exchange, 200, response);
    }

    private Map<String, Object> state(String sessionId) {
        SessionStore.Session session = requireSession(sessionId);
        RuntimeState runtime = runtimes.computeIfAbsent(sessionId, key -> new RuntimeState());
        Map<String, Object> sessionView = new LinkedHashMap<>();
        sessionView.put("id", session.id);
        sessionView.put("title", session.title);
        sessionView.put("createdAt", session.createdAt);

        Map<String, Object> memoryView = memoryService.memoryView();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("session", sessionView);
        response.put("messages", session.messages);
        response.put("memories", memoryView.get("memories"));
        response.put("variables", memoryService.variablesView());
        response.put("graph", memoryService.graphView());
        response.put("recall", runtime.recall == null ? null : runtime.recall.toMap());
        response.put("graphRecall", runtime.recall == null ? null : runtime.recall.graphRecallMap());
        List<Map<String, Object>> runs = new ArrayList<>();
        for (Pipeline.Run run : runtime.runs) {
            runs.add(run.toMap());
        }
        Map<String, Object> pipeline = new LinkedHashMap<>();
        pipeline.put("running", runtime.running);
        pipeline.put("runs", runs);
        response.put("pipeline", pipeline);
        response.put("memoryError", memoryService.initError());
        return response;
    }

    private SessionStore.Session requireSession(String sessionId) {
        Optional<SessionStore.Session> session = sessionStore.find(sessionId);
        if (session.isEmpty()) {
            throw new IllegalArgumentException("session not found: " + sessionId);
        }
        return session.get();
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/".equals(path) || path.isEmpty()) {
            path = "/index.html";
        }
        if (path.contains("..")) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        try (InputStream input = WebServer.class.getResourceAsStream("/web" + path)) {
            if (input == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] bytes = input.readAllBytes();
            String extension = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : "";
            exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPES.getOrDefault(extension, "application/octet-stream"));
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("");
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static void sendJson(HttpExchange exchange, int status, Object body) {
        try {
            byte[] bytes = JSON.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        } catch (IOException ignored) {
            // 前端超时/取消时连接可能已断开，不影响后台记忆加工
        }
    }

    private static final class RuntimeState {
        private volatile MemoryService.RecallResult recall;
        private volatile boolean running;
        private final List<Pipeline.Run> runs = Collections.synchronizedList(new ArrayList<>());
    }
}
