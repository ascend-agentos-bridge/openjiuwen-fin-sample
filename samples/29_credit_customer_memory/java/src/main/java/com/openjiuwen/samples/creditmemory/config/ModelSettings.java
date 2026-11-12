package com.openjiuwen.samples.creditmemory.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.retrieval.common.EmbeddingConfig;
import com.openjiuwen.core.retrieval.embedding.APIEmbedding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对话模型与记忆模型两组配置。
 *
 * <p>样例里对话回答和记忆抽取使用不同的模型配置，便于把「便宜/快的模型用于回答」、
 * 「稳定的模型用于记忆抽取」分开调整。默认读取环境变量，其次读取 config/model.local.json
 * （gitignored，可被页面「模型设置」写回）。</p>
 */
public final class ModelSettings {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Chat {
        public String provider = "OpenAI 兼容";
        public String apiBase = env("CHAT_API_BASE", env("API_BASE", "http://127.0.0.1:1234/v1"));
        public String apiKey = env("CHAT_API_KEY", env("API_KEY", "lm-studio"));
        public String model = env("CHAT_MODEL", env("MODEL_NAME", "google/gemma-4-e4b"));
        public double temperature = 0.2;
        public int maxTokens = 2048;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Memory {
        public String provider = "OpenAI 兼容";
        public String apiBase = env("MEMORY_API_BASE", env("API_BASE", "http://127.0.0.1:1234/v1"));
        public String apiKey = env("MEMORY_API_KEY", env("API_KEY", "lm-studio"));
        public String model = env("MEMORY_MODEL", env("MODEL_NAME", "google/gemma-4-e4b"));
        public String embeddingApiBase = env("EMBED_API_BASE",
                env("API_BASE", "http://127.0.0.1:1234/v1") + "/embeddings");
        public String embeddingApiKey = env("EMBED_API_KEY", env("API_KEY", "lm-studio"));
        public String embeddingModel = env("EMBED_MODEL", "text-embedding-nomic-embed-text-v1.5");
        public int embeddingDim = Integer.parseInt(env("EMBED_DIM", "768"));
        public int maxTokens = 2048;
    }

    public Chat chat = new Chat();
    public Memory memory = new Memory();

    public static ModelSettings load(Path configPath) {
        ModelSettings defaults = new ModelSettings();
        if (configPath == null || !Files.isRegularFile(configPath)) {
            return defaults;
        }
        try {
            ModelSettings stored = JSON.readValue(Files.readString(configPath), ModelSettings.class);
            mergeChat(defaults.chat, stored.chat);
            mergeMemory(defaults.memory, stored.memory);
            return defaults;
        } catch (IOException exception) {
            System.err.println("[settings] 读取 " + configPath + " 失败，使用环境变量默认值: " + exception.getMessage());
            return defaults;
        }
    }

    private static void mergeChat(Chat target, Chat source) {
        if (source == null) { return; }
        target.provider = prefer(source.provider, target.provider);
        target.apiBase = prefer(source.apiBase, target.apiBase);
        target.apiKey = prefer(source.apiKey, target.apiKey);
        target.model = prefer(source.model, target.model);
        target.temperature = source.temperature > 0 ? source.temperature : target.temperature;
        target.maxTokens = source.maxTokens > 0 ? source.maxTokens : target.maxTokens;
    }

    private static void mergeMemory(Memory target, Memory source) {
        if (source == null) { return; }
        target.provider = prefer(source.provider, target.provider);
        target.apiBase = prefer(source.apiBase, target.apiBase);
        target.apiKey = prefer(source.apiKey, target.apiKey);
        target.model = prefer(source.model, target.model);
        target.embeddingApiBase = prefer(source.embeddingApiBase, target.embeddingApiBase);
        target.embeddingApiKey = prefer(source.embeddingApiKey, target.embeddingApiKey);
        target.embeddingModel = prefer(source.embeddingModel, target.embeddingModel);
        target.embeddingDim = source.embeddingDim > 0 ? source.embeddingDim : target.embeddingDim;
        target.maxTokens = source.maxTokens > 0 ? source.maxTokens : target.maxTokens;
    }

    private static String prefer(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    public void save(Path configPath) {
        try {
            if (configPath.getParent() != null) {
                Files.createDirectories(configPath.getParent());
            }
            Files.writeString(configPath, JSON.writeValueAsString(this));
        } catch (IOException exception) {
            System.err.println("[settings] 保存失败: " + exception.getMessage());
        }
    }

    @JsonIgnore
    public boolean isConfigured() {
        return notBlank(chat.apiBase) && notBlank(chat.model)
                && notBlank(memory.apiBase) && notBlank(memory.model)
                && notBlank(memory.embeddingApiBase) && notBlank(memory.embeddingModel);
    }

    public Model buildChatModel() {
        return buildModel(chat.provider, chat.apiBase, chat.apiKey, chat.model, chat.temperature, chat.maxTokens);
    }

    public Model buildMemoryModel() {
        return buildModel(memory.provider, memory.apiBase, memory.apiKey, memory.model, 0.1, memory.maxTokens);
    }

    public APIEmbedding buildEmbedding() {
        EmbeddingConfig config = new EmbeddingConfig(memory.embeddingModel, memory.embeddingApiBase, memory.embeddingApiKey);
        return new APIEmbedding(config, 300, 1, null, 8, 4);
    }

    private static Model buildModel(String provider, String apiBase, String apiKey, String model,
                                    double temperature, int maxTokens) {
        ModelClientConfig client = ModelClientConfig.builder()
                .clientId("credit-memory-sample")
                .clientProvider("openai")
                .apiBase(apiBase)
                .apiKey(apiKey)
                .timeout(900)
                .maxRetries(1)
                .build();
        ModelRequestConfig request = ModelRequestConfig.builder()
                .modelName(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();
        return new Model(client, request);
    }

    public Map<String, Object> publicView() {
        Map<String, Object> chatView = new LinkedHashMap<>();
        chatView.put("provider", chat.provider);
        chatView.put("apiBase", chat.apiBase);
        chatView.put("model", chat.model);
        chatView.put("temperature", chat.temperature);
        Map<String, Object> memoryView = new LinkedHashMap<>();
        memoryView.put("provider", memory.provider);
        memoryView.put("apiBase", memory.apiBase);
        memoryView.put("model", memory.model);
        memoryView.put("embeddingApiBase", memory.embeddingApiBase);
        memoryView.put("embeddingModel", memory.embeddingModel);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("configured", isConfigured());
        view.put("chat", chatView);
        view.put("memory", memoryView);
        return view;
    }

    static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
