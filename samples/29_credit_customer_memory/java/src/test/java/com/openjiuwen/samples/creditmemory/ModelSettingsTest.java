package com.openjiuwen.samples.creditmemory;

import com.openjiuwen.samples.creditmemory.config.ModelSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelSettingsTest {

    @TempDir
    Path tempDir;

    @Test
    void exposesTwoModelGroupsWithoutApiKeys() {
        ModelSettings settings = new ModelSettings();
        Map<String, Object> view = settings.publicView();
        assertTrue((Boolean) view.get("configured"));
        Map<?, ?> chat = (Map<?, ?>) view.get("chat");
        Map<?, ?> memory = (Map<?, ?>) view.get("memory");
        assertFalse(chat.containsKey("apiKey"));
        assertFalse(memory.containsKey("apiKey"));
        assertFalse(memory.containsKey("embeddingApiKey"));
        assertTrue(chat.containsKey("model"));
        assertTrue(memory.containsKey("embeddingModel"));
    }

    @Test
    void savesAndReloadsLocalConfig() {
        Path file = tempDir.resolve("config/model.local.json");
        ModelSettings settings = new ModelSettings();
        settings.chat.model = "chat-model-x";
        settings.memory.model = "memory-model-x";
        settings.memory.embeddingModel = "embedding-x";
        settings.save(file);

        ModelSettings loaded = ModelSettings.load(file);
        assertEquals("chat-model-x", loaded.chat.model);
        assertEquals("memory-model-x", loaded.memory.model);
        assertEquals("embedding-x", loaded.memory.embeddingModel);
        assertEquals(settings.chat.apiBase, loaded.chat.apiBase);
    }
}
