package com.openjiuwen.samples.creditmemory;

import com.openjiuwen.samples.creditmemory.agentmemory.AgentMemoryClient;
import com.openjiuwen.samples.creditmemory.agentmemory.AgentMemoryConfig;
import com.openjiuwen.samples.creditmemory.agentmemory.AgentMemoryProvider;
import com.openjiuwen.samples.creditmemory.agentmemory.DualAnswerRunner;
import com.openjiuwen.samples.creditmemory.agentmemory.web.DualWebServer;
import com.openjiuwen.samples.creditmemory.agentmemory.web.PortalUrls;
import com.openjiuwen.samples.creditmemory.config.ModelSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * 样例 29 · 信贷客户记忆助手（Agent Memory 服务端 + Agent Core MemoryRail）。
 *
 * <p>启动前先运行 Agent Memory（docker compose / 本机服务）。启动时打印两个地址：
 * 业务页面与 Agent Memory 记忆管理 Portal。</p>
 */
public final class AgentMemoryAssistant {
    private AgentMemoryAssistant() {
    }

    public static void main(String[] args) throws Exception {
        Path baseDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Files.createDirectories(baseDir.resolve("data"));
        ModelSettings settings = ModelSettings.load(baseDir.resolve("config/model.local.json"));
        AgentMemoryConfig memoryConfig = AgentMemoryConfig.fromEnvironment();
        AgentMemoryClient client = new AgentMemoryClient(memoryConfig);
        String health = client.healthz();
        String portalUrl = PortalUrls.publicUrl(client.launchPortal());

        AgentMemoryProvider provider = new AgentMemoryProvider(client);
        provider.initialize(Map.of());
        String customer = env("AGENT_MEMORY_CUSTOMER", "C1001");
        String conversation = env("AGENT_MEMORY_CONVERSATION", "credit-demo");
        DualAnswerRunner runner = new DualAnswerRunner(settings, client, provider, customer, conversation);
        runner.start();

        int port = Integer.parseInt(env("PORT", "8099"));
        DualWebServer web = new DualWebServer(port, settings, client, runner);
        web.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            web.stop();
            runner.close();
        }));

        System.out.println();
        System.out.println("============================================================");
        System.out.println(" 信贷客户记忆助手 · Agent Memory + MemoryRail 双回答");
        System.out.println(" Agent Memory API : " + memoryConfig.baseUrl() + "  (" + health + ")");
        System.out.println(" Agent Memory Web : " + portalUrl);
        System.out.println(" 业务页面         : http://127.0.0.1:" + port + "/");
        System.out.println(" 对话模型         : " + settings.chat.model + " @ " + settings.chat.apiBase);
        System.out.println("============================================================");
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
