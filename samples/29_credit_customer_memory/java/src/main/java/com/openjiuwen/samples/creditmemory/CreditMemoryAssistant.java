package com.openjiuwen.samples.creditmemory;

import com.openjiuwen.samples.creditmemory.config.ModelSettings;
import com.openjiuwen.samples.creditmemory.service.ChatService;
import com.openjiuwen.samples.creditmemory.service.MemoryService;
import com.openjiuwen.samples.creditmemory.service.SessionStore;
import com.openjiuwen.samples.creditmemory.web.WebServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 样例 29 · 信贷客户记忆助手（Java Agent Core 0.1.16 + memory 0.1.16）。
 *
 * <p>启动后浏览器打开 http://127.0.0.1:8099/。对话模型与记忆模型在页面「模型设置」中配置；
 * 默认读取环境变量或 java/config/model.local.json。</p>
 */
public final class CreditMemoryAssistant {

    private CreditMemoryAssistant() {
    }

    public static void main(String[] args) throws Exception {
        Path baseDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path configPath = baseDir.resolve("config/model.local.json");
        Path dataDir = baseDir.resolve("data");
        Files.createDirectories(dataDir);

        ModelSettings settings = ModelSettings.load(configPath);
        SessionStore sessionStore = new SessionStore(dataDir.resolve("sessions.json"));
        ChatService chatService = new ChatService(settings);
        MemoryService memoryService = new MemoryService(settings, dataDir);
        if (sessionStore.list().isEmpty()) {
            sessionStore.create();
        }

        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8099"));
        WebServer server = new WebServer(port, settings, configPath, sessionStore, chatService, memoryService);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            memoryService.shutdown();
        }));

        System.out.println();
        System.out.println("============================================================");
        System.out.println(" 信贷客户记忆助手 · Java Agent Core 0.1.16 样例");
        System.out.println(" 对话模型: " + settings.chat.model + " @ " + settings.chat.apiBase);
        System.out.println(" 记忆模型: " + settings.memory.model + " / embedding=" + settings.memory.embeddingModel);
        System.out.println(" 数据目录: " + dataDir.toAbsolutePath());
        System.out.println(" 页面:     http://127.0.0.1:" + port + "/");
        if (memoryService.initError() != null) {
            System.out.println(" [警告] 记忆引擎初始化失败: " + memoryService.initError());
        }
        System.out.println("============================================================");
    }
}
