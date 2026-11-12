package com.openjiuwen.samples.creditmemory;

import com.openjiuwen.samples.creditmemory.service.SessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsSessionsMessagesAndTitle() {
        Path file = tempDir.resolve("sessions.json");
        SessionStore store = new SessionStore(file);
        SessionStore.Session session = store.create();
        store.appendCustomer(session, "我想申请一笔经营贷，额度80万");
        store.appendAgent(session, "好的，我记下了。", 2, 3, Map.of("added", 2, "graph", 3));

        Map<String, Object> summary = store.summary(session);
        assertEquals(2, summary.get("messageCount"));
        assertTrue(String.valueOf(summary.get("title")).contains("我想申请"));

        SessionStore reloaded = new SessionStore(file);
        assertEquals(1, reloaded.list().size());
        SessionStore.Session restored = reloaded.find(session.id).orElseThrow();
        assertEquals(2, restored.messages.size());
        assertEquals("agent", restored.messages.get(1).role);
        assertEquals(3, restored.messages.get(1).graphCites);
        assertFalse(restored.messages.get(1).processed.isEmpty());
    }
}
