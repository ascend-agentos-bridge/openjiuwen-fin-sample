package com.openjiuwen.samples.creditmemory.agentmemory;

import com.openjiuwen.samples.creditmemory.config.ModelSettings;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Agent Memory + Agent Core MemoryRail 的真实验证探针。
 *
 * <p>不是业务入口，也不是 UI 的一部分：它只做一件事 —— 用真实的本机 Agent Memory 服务
 * 和真实 DeepSeek，跑一遍「写入 → 双回答（rail 召回 vs 无记忆基线）→ 纠正 → 演进」，
 * 把原始输出打印出来作为集成证据。业务 UI 由 Lead 后续接入，不在这里实现。</p>
 *
 * <p>运行（两个参数分别是客户首轮陈述和追问；省略时从终端读取，无终端时用探针内置示例）：</p>
 * <pre>{@code
 * export CHAT_API_BASE=https://api.deepseek.com
 * export CHAT_API_KEY=sk-...        # 不要提交到仓库
 * export CHAT_MODEL=deepseek-flash
 * export AGENT_MEMORY_BASE_URL=http://127.0.0.1:8137
 * mvn -q exec:java -Dexec.mainClass=com.openjiuwen.samples.creditmemory.agentmemory.AgentMemoryProbe \
 *   -Dexec.args="客户陈述|客户问题"
 * }</pre>
 */
public final class AgentMemoryProbe {

    /** 探针示例：只在没有参数也没有终端输入时使用，并会在输出里标注。与 java/README.md 周砚清场景一致。 */
    private static final String DEMO_STATEMENT =
            "我想申请经营贷。我是周砚清，在杭州经营精密制造公司，想贷80万分36期，偏好等额本息，"
                    + "计划5年内提前结清；2023年有过一次信用卡逾期但已结清，不接受先息后本。";
    private static final String DEMO_QUESTION =
            "我想申请多少额度、期限多久、偏好什么还款方式？";

    private AgentMemoryProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path baseDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        ModelSettings settings = ModelSettings.load(baseDir.resolve("config/model.local.json"));
        AgentMemoryConfig memoryConfig = AgentMemoryConfig.fromEnvironment();
        String customerId = env("AGENT_MEMORY_CUSTOMER", "zhou-yanqing");
        String conversationId = env("AGENT_MEMORY_CONVERSATION", "zhou-yanqing-s1");

        String statement = args.length > 0 ? args[0].strip() : readInput("客户首轮陈述", DEMO_STATEMENT);
        String question = args.length > 1 ? args[1].strip() : readInput("客户追问", DEMO_QUESTION);
        if (statement.isBlank() || question.isBlank()) {
            throw new IllegalArgumentException("客户陈述与追问都不能为空");
        }

        printBanner(settings, memoryConfig, customerId, conversationId);
        AgentMemoryClient client = new AgentMemoryClient(memoryConfig);

        System.out.println("[1] GET /healthz 健康检查 ...");
        String health = client.healthz();
        System.out.println("    -> " + health);

        System.out.println("[2] POST /portal/api/launch 获取 Portal 链接 ...");
        String portalUrl = client.launchPortal();
        System.out.println("    -> " + portalUrl);

        AgentMemoryProvider provider = new AgentMemoryProvider(client);
        provider.initialize(Map.of());
        System.out.println("[3] AgentMemoryProvider.initialize 成功（scope 映射见 AGENT-MEMORY-INTEGRATION.md）");

        try (DualAnswerRunner runner = new DualAnswerRunner(
                settings, client, provider, customerId, conversationId)) {
            runner.start();

            System.out.println();
            System.out.println("[4] 让挂 ExternalMemoryRail 的 Agent 真实回答客户首轮陈述（afterInvoke 自动写回记忆）...");
            DualAnswerRunner.SeedResult seed = runner.rememberByAgent(statement);
            System.out.println("    客户: " + statement);
            System.out.println("    Agent: " + seed.agentAnswer());
            System.out.println("    rail syncTurn 写回内容: " + oneLine(seed.syncedContent()));
            if (!seed.syncError().isBlank()) {
                System.out.println("    [警告] 写回失败: " + seed.syncError());
            }
            System.out.println("    infer=true 真实抽取单元数: " + seed.extractedUnits().size());
            for (AgentMemoryUnit unit : seed.extractedUnits()) {
                System.out.println("      - [" + unit.unitId() + "] " + unit.content());
            }
            if (seed.extractedUnits().isEmpty()) {
                System.out.println("    [警告] 未抽取到记忆单元，后续召回可能为空；请检查 Agent Memory 的 LLM 抽取配置");
            }

            System.out.println();
            System.out.println("[5] 同一客户追问：有 rail 召回 vs 无 rail 基线 ...");
            DualAnswerRunner.DualAnswer dual = runner.ask(question);
            System.out.println("    客户追问: " + question);
            System.out.println("    ------------------------------------------------");
            System.out.println("    [有记忆] " + dual.withMemoryAnswer());
            System.out.println("    ------------------------------------------------");
            System.out.println("    [基线  ] " + dual.baselineAnswer());
            System.out.println("    ------------------------------------------------");
            System.out.println("    rail prefetch 召回条数: " + dual.prefetchCount());
            System.out.println("    rail 注入的 <memory-context> 原文:");
            System.out.println(indent(dual.injectedMemoryBlock()));
            for (AgentMemoryHit hit : dual.recalledHits()) {
                System.out.printf("      - unit=%s score=%.4f %s%n",
                        hit.unitId(), hit.score(), oneLine(hit.displayText()));
            }

            System.out.println();
            System.out.println("[6] POST /v1/update 演示 SUPERSEDE 纠正 ...");
            List<AgentMemoryUnit> active = runner.listMemories(20).stream()
                    .filter(AgentMemoryUnit::active).toList();
            if (active.isEmpty()) {
                System.out.println("    [跳过] 当前 scope 没有 active 记忆");
            } else {
                AgentMemoryUnit target = active.get(0);
                AgentMemoryUnit updated = client.update(runner.memoryScope(), target.unitId(),
                        target.content() + "（客户补充：放款前可提供近6个月对公流水）");
                System.out.println("    旧 unit: " + target.unitId());
                System.out.println("    新 unit: " + updated.unitId() + " supersedes=" + updated.supersedes());
                for (AgentMemoryUnit unit : runner.listMemories(20)) {
                    System.out.println("    lifecycle=" + unit.lifecycle() + " id=" + unit.unitId()
                            + " supersedes=" + unit.supersedes());
                }
            }

            System.out.println();
            System.out.println("[7] POST /v1/evolve 触发记忆演进（consolidate，异步任务）...");
            String jobId = client.evolve(runner.memoryScope(), "consolidate");
            System.out.println("    -> job_id=" + jobId);

            System.out.println();
            System.out.println("[8] 当前 scope 记忆快照（POST /v1/list）：");
            for (AgentMemoryUnit unit : runner.listMemories(20)) {
                System.out.println("    lifecycle=" + unit.lifecycle() + " | " + unit.mdTitle()
                        + " | " + oneLine(unit.content()));
            }
        }

        System.out.println();
        System.out.println("=== probe 结束：以上回答/召回/抽取均来自真实 DeepSeek + 本机 Agent Memory ===");
    }

    private static void printBanner(ModelSettings settings, AgentMemoryConfig memoryConfig,
                                    String customerId, String conversationId) {
        System.out.println("============================================================");
        System.out.println(" Agent Memory + Agent Core MemoryRail 真实探针");
        System.out.println(" Agent Memory: " + memoryConfig.baseUrl()
                + " (dev auth org=" + memoryConfig.identityOrg() + ", user=" + memoryConfig.identityUser() + ")");
        System.out.println(" 对话模型:     " + settings.chat.model + " @ " + settings.chat.apiBase);
        System.out.println(" 客户/会话:    customer=" + customerId + ", conversation=" + conversationId);
        if (settings.chat.apiKey == null || settings.chat.apiKey.isBlank()) {
            System.out.println(" [错误] 未配置对话模型 API Key：请设置 CHAT_API_KEY（或 config/model.local.json）");
            throw new IllegalStateException("缺少 CHAT_API_KEY");
        }
        System.out.println("============================================================");
    }

    private static String readInput(String label, String demoValue) {
        if (System.console() != null) {
            System.out.print(label + "（回车使用示例）: ");
            String line = System.console().readLine();
            if (line != null && !line.isBlank()) {
                return line.strip();
            }
            System.out.println("    使用探针内置示例。");
            return demoValue;
        }
        System.out.println("[提示] 无终端输入，使用探针内置示例作为" + label + "。");
        return demoValue;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        String single = text.replace('\n', ' ').strip();
        return single.length() <= 200 ? single : single.substring(0, 200) + "...";
    }

    private static String indent(String text) {
        if (text == null || text.isBlank()) {
            return "      <未召回，未注入>";
        }
        return text.lines().map(line -> "      " + line).reduce((a, b) -> a + "\n" + b).orElse("");
    }
}
