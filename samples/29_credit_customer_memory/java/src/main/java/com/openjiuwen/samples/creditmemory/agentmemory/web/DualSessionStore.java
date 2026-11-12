package com.openjiuwen.samples.creditmemory.agentmemory.web;

import com.openjiuwen.samples.creditmemory.agentmemory.DualAnswerRunner;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** 双回答页面的会话存储（仅页面展示；真正的长期记忆在 Agent Memory 服务）。 */
public final class DualSessionStore {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm");
    private final List<Session> sessions = new ArrayList<>();
    private final AtomicInteger seq = new AtomicInteger();

    public static final class Message {
        public String id;
        public String role;
        public String text;
        public String ts;
        public String withMemoryAnswer;
        public String baselineAnswer;
        public int prefetchCount;
        public List<Map<String, Object>> recalledHits = new ArrayList<>();
        public List<Map<String, Object>> extractedUnits = new ArrayList<>();
    }

    public static final class Session {
        public String id;
        public String title;
        public String createdAt;
        public List<Message> messages = new ArrayList<>();
    }

    public synchronized void reset() {
        sessions.clear();
        seq.set(0);
        create();
    }

    public synchronized Session create() {
        Session session = new Session();
        session.id = "s-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-" + String.format("%03d", seq.incrementAndGet());
        session.title = "新的咨询";
        session.createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        sessions.add(session);
        return session;
    }

    public synchronized List<Session> list() {
        return new ArrayList<>(sessions);
    }

    public synchronized Optional<Session> find(String id) {
        return sessions.stream().filter(item -> item.id.equals(id)).findFirst();
    }

    public synchronized Message appendCustomer(Session session, String text) {
        Message message = new Message();
        message.id = session.id + "-m" + (session.messages.size() + 1);
        message.role = "customer";
        message.text = text;
        message.ts = LocalDateTime.now().format(TS);
        session.messages.add(message);
        if ("新的咨询".equals(session.title)) {
            String title = text.replaceAll("\\s+", " ").strip();
            session.title = title.length() > 16 ? title.substring(0, 16) + "…" : title;
        }
        return message;
    }

    public synchronized Message appendDual(Session session, DualAnswerRunner.DualAnswer answer) {
        Message message = new Message();
        message.id = session.id + "-m" + (session.messages.size() + 1);
        message.role = "agent";
        message.text = answer.withMemoryAnswer();
        message.ts = LocalDateTime.now().format(TS);
        message.withMemoryAnswer = answer.withMemoryAnswer();
        message.baselineAnswer = answer.baselineAnswer();
        message.prefetchCount = answer.prefetchCount();
        for (var hit : answer.recalledHits()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("unitId", hit.unitId());
            item.put("score", hit.score());
            item.put("content", hit.displayText());
            message.recalledHits.add(item);
        }
        session.messages.add(message);
        return message;
    }

    public synchronized Map<String, Object> summary(Session session) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", session.id);
        map.put("title", session.title);
        map.put("createdAt", session.createdAt);
        map.put("messageCount", session.messages.size());
        map.put("preview", session.messages.isEmpty() ? "" : session.messages.get(session.messages.size() - 1).text);
        return map;
    }
}
