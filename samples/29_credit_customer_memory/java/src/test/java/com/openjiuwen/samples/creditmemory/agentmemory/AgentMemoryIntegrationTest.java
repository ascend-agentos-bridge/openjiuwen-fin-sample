package com.openjiuwen.samples.creditmemory.agentmemory;

import com.openjiuwen.samples.creditmemory.config.ModelSettings;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实集成验收：本机 Agent Memory 服务 + 真实 DeepSeek。
 *
 * <p>默认 surefire 通过 {@code excludedGroups=integration} 跳过；显式运行：</p>
 * <pre>{@code
 * export CHAT_API_BASE=https://api.deepseek.com
 * export CHAT_API_KEY=sk-...
 * export CHAT_MODEL=deepseek-flash
 * mvn -q -DexcludedGroups=none -Dtest=AgentMemoryIntegrationTest test
 * }</pre>
 *
 * <p>验证链路：/healthz → /portal/api/launch → provider.initialize → DeepAgent+ExternalMemoryRail
 * 首轮写回（infer=true 真实抽取）→ 双回答（rail 召回 vs 无记忆基线）→ /v1/update SUPERSEDE
 * → /v1/evolve consolidate。没有 mock、没有预置记忆：所有记忆都由本轮真实写入产生。</p>
 */
@Tag("integration")
@Timeout(value = 6, unit = TimeUnit.MINUTES)
class AgentMemoryIntegrationTest {

    @Test
    void realAgentMemoryAndDualAnswer() throws Exception {
        Assumptions.assumeTrue(System.getenv("CHAT_API_KEY") != null
                        && !System.getenv("CHAT_API_KEY").isBlank(),
                "集成验收需要 CHAT_API_KEY（真实对话模型）");
        ModelSettings settings = ModelSettings.load(Paths.get("config/model.local.json"));
        AgentMemoryConfig memoryConfig = AgentMemoryConfig.fromEnvironment();
        AgentMemoryClient client = new AgentMemoryClient(memoryConfig);

        String health = client.healthz();
        assertTrue(health.contains("ok"), "Agent Memory /healthz 不可用: " + health);
        String portalUrl = client.launchPortal();
        assertTrue(portalUrl.startsWith("http"), "portal_url 异常: " + portalUrl);
        assertTrue(portalUrl.contains("/portal/"), "portal_url 异常: " + portalUrl);

        String customerId = "cust-it-" + Long.toString(System.currentTimeMillis(), 36);
        String conversationId = customerId + "-s1";
        String statement = "我是赵一鸣，在苏州经营一家精密零部件公司，想申请90万元经营贷，"
                + "期限36期，只接受等额本息还款。";
        String question = "我想申请多少经营贷？期限多久？能接受哪种还款方式？";

        AgentMemoryProvider provider = new AgentMemoryProvider(client);
        provider.initialize(java.util.Map.of("user_id", customerId, "scope_id", "credit-agent",
                "session_id", conversationId));
        assertTrue(provider.isInitialized());

        try (DualAnswerRunner runner = new DualAnswerRunner(
                settings, client, provider, customerId, conversationId)) {
            runner.start();

            DualAnswerRunner.SeedResult seed = runner.rememberByAgent(statement);
            assertFalse(seed.agentAnswer().isBlank(), "首轮真实回答为空");
            assertTrue(seed.syncError().isBlank(), "rail syncTurn 失败: " + seed.syncError());
            assertTrue(seed.syncedContent().startsWith("user: "), "rail 写回内容形态异常");
            assertFalse(seed.extractedUnits().isEmpty(), "infer=true 应真实抽取派生记忆单元");

            DualAnswerRunner.DualAnswer answer = runner.ask(question);
            assertFalse(answer.withMemoryAnswer().isBlank(), "有记忆回答为空");
            assertFalse(answer.baselineAnswer().isBlank(), "基线回答为空");
            assertTrue(answer.prefetchCount() >= 1,
                    "rail 应从 Agent Memory 召回记忆，实际 " + answer.prefetchCount());
            assertTrue(answer.injectedMemoryBlock().contains("<memory-context>"),
                    "rail 注入块缺失: " + answer.injectedMemoryBlock());
            assertTrue(answer.recalledContext().contains("经营贷"),
                    "召回内容应包含本轮写入的经营贷信息: " + answer.recalledContext());

            List<AgentMemoryUnit> active = runner.listMemories(50).stream()
                    .filter(AgentMemoryUnit::active).toList();
            assertFalse(active.isEmpty(), "list 应返回 active 记忆");

            AgentMemoryUnit target = active.get(0);
            AgentMemoryUnit updated = client.update(runner.memoryScope(), target.unitId(),
                    target.content() + "（客户补充：放款前可提供近6个月对公流水）");
            assertEquals(target.unitId(), updated.supersedes(), "update 应以 SUPERSEDE 指向旧版本");
            assertTrue(updated.active());

            List<AgentMemoryUnit> afterUpdate = runner.listMemories(50);
            assertTrue(afterUpdate.stream().anyMatch(unit -> unit.lifecycle().equals("superseded")),
                    "旧版本应变为 superseded");

            String jobId = client.evolve(runner.memoryScope(), "consolidate");
            assertFalse(jobId.isBlank(), "evolve 应返回任务 ID");
        }
    }
}
