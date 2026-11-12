package com.openjiuwen.samples.creditmemory.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 最小会话存储：内存 + data/sessions.json。长期记忆在 SQLite 中，不在这里。
 */
public final class SessionStore {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter CREATED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public static final class Message {
        public String id;
        public String role;
        public String text;
        public String ts;
        public Integer cites;
        public Integer graphCites;
        public Map<String, Object> processed;

        public Message() {
        }

        public Message(String id, String role, String text, String ts) {
            this.id = id;
            this.role = role;
            this.text = text;
            this.ts = ts;
        }
    }

    public static final class Session {
        public String id;
        public String title;
        public String createdAt;
        public List<Message> messages = new ArrayList<>();

        public Session() {
        }
    }

    private final Path storagePath;
    private final List<Session> sessions = new ArrayList<>();
    private final AtomicInteger sequence = new AtomicInteger(0);
    private int sessionCounter = 0;

    public SessionStore(Path storagePath) {
        this.storagePath = storagePath;
        load();
    }

    public synchronized List<Session> list() {
        return new ArrayList<>(sessions);
    }

    public synchronized Session create() {
        sessionCounter += 1;
        Session session = new Session();
        session.id = "s-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-"
                + String.format("%03d", sessionCounter);
        session.title = "新的咨询";
        session.createdAt = LocalDateTime.now().format(CREATED);
        sessions.add(session);
        save();
        return session;
    }

    public synchronized Optional<Session> find(String id) {
        return sessions.stream().filter(session -> session.id.equals(id)).findFirst();
    }

    public synchronized Message appendCustomer(Session session, String text) {
        Message message = new Message(nextMessageId(session), "customer", text, LocalDateTime.now().format(TS));
        session.messages.add(message);
        if ("新的咨询".equals(session.title)) {
            String title = text.replaceAll("\\s+", " ").strip();
            session.title = title.length() > 14 ? title.substring(0, 14) + "…" : title;
        }
        save();
        return message;
    }

    public synchronized Message appendAgent(Session session, String text, Integer cites, Integer graphCites,
                                            Map<String, Object> processed) {
        Message message = new Message(nextMessageId(session), "agent", text, LocalDateTime.now().format(TS));
        message.cites = cites;
        message.graphCites = graphCites;
        message.processed = processed;
        session.messages.add(message);
        save();
        return message;
    }

    public synchronized void save() {
        try {
            if (storagePath.getParent() != null) {
                Files.createDirectories(storagePath.getParent());
            }
            Files.writeString(storagePath, JSON.writeValueAsString(sessions));
        } catch (IOException exception) {
            System.err.println("[sessions] 保存失败: " + exception.getMessage());
        }
    }

    public Map<String, Object> summary(Session session) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", session.id);
        item.put("title", session.title);
        item.put("createdAt", session.createdAt);
        item.put("messageCount", session.messages.size());
        item.put("preview", session.messages.isEmpty() ? "" : session.messages.get(session.messages.size() - 1).text);
        return item;
    }

    private String nextMessageId(Session session) {
        return session.id + "-m" + String.format("%03d", session.messages.size() + 1);
    }

    private void load() {
        if (storagePath == null || !Files.isRegularFile(storagePath)) {
            return;
        }
        try {
            List<Session> loaded = JSON.readValue(Files.readString(storagePath), new TypeReference<List<Session>>() { });
            if (loaded != null) {
                sessions.addAll(loaded);
                sessionCounter = sessions.size();
            }
        } catch (IOException exception) {
            System.err.println("[sessions] 读取失败: " + exception.getMessage());
        }
    }
}
