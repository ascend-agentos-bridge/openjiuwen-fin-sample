package com.openjiuwen.samples.creditmemory.agentmemory;

import com.openjiuwen.samples.creditmemory.config.ModelSettings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DualAnswerRunner 结构单测：验证「双回答」返回结构、启动前置校验、防御性拷贝。
 *
 * <p>真实双回答需要本机 Agent Memory + DeepSeek，放在 {@code AgentMemoryIntegrationTest}
 * （@Tag("integration")）里，默认 surefire 不执行。</p>
 */
class DualAnswerRunnerTest {

    private static DualAnswerRunner newRunner() {
        AgentMemoryConfig config = new AgentMemoryConfig(
                "http://127.0.0.1:8137", "local", "developer", "", 5_000, 30_000);
        AgentMemoryClient client = new AgentMemoryClient(config, new RecordingAgentMemoryHttp());
        AgentMemoryProvider provider = new AgentMemoryProvider(client);
        return new DualAnswerRunner(new ModelSettings(), client, provider, "cust-8801", "cust-8801-s1");
    }

    @Test
    void askBeforeStartFailsFast() {
        DualAnswerRunner runner = newRunner();
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> runner.ask("客户想申请多少经营贷？"));
        assertTrue(exception.getMessage().contains("start"));
    }

    @Test
    void askRejectsBlankQuestionEvenWhenStarted() {
        DualAnswerRunner runner = newRunner();
        assertThrows(IllegalArgumentException.class, () -> runner.ask("  "));
    }

    @Test
    void dualAnswerExposesStructureAndSerializesHits() {
        List<AgentMemoryHit> hits = new ArrayList<>();
        hits.add(new AgentMemoryHit("u1", 0.91, "客户只接受等额本息", "", "l0",
                Map.of("source_app", "credit-demo")));
        DualAnswerRunner.DualAnswer answer = new DualAnswerRunner.DualAnswer(
                "客户能接受什么还款方式？",
                "您此前明确只接受等额本息。",
                "建议您可以考虑等额本息或先息后本。",
                "## Agent Memory（客户长期记忆召回）\n- 客户只接受等额本息",
                "<memory-context>\n- 客户只接受等额本息\n</memory-context>",
                1, hits);

        Map<String, Object> map = answer.toMap();

        assertEquals("客户能接受什么还款方式？", map.get("question"));
        assertEquals("您此前明确只接受等额本息。", map.get("withMemoryAnswer"));
        assertEquals("建议您可以考虑等额本息或先息后本。", map.get("baselineAnswer"));
        assertEquals(1, map.get("prefetchCount"));
        assertTrue(String.valueOf(map.get("injectedMemoryBlock")).contains("<memory-context>"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> serializedHits = (List<Map<String, Object>>) map.get("recalledHits");
        assertEquals(1, serializedHits.size());
        assertEquals("u1", serializedHits.get(0).get("unitId"));
        assertEquals(0.91, (double) serializedHits.get(0).get("score"), 1e-9);
        assertEquals("客户只接受等额本息", serializedHits.get(0).get("content"));
    }

    @Test
    void dualAnswerDefensivelyCopiesHitsAndAllowsNull() {
        List<AgentMemoryHit> hits = new ArrayList<>();
        hits.add(new AgentMemoryHit("u1", 0.5, "记忆", "", "l0", Map.of()));
        DualAnswerRunner.DualAnswer answer = new DualAnswerRunner.DualAnswer(
                "q", "a", "b", "", "", 1, hits);
        hits.clear();
        assertEquals(1, answer.recalledHits().size());
        assertEquals(0, new DualAnswerRunner.DualAnswer("q", "a", "b", "", "", 0, null)
                .recalledHits().size());
    }

    @Test
    void seedResultCopiesUnits() {
        List<AgentMemoryUnit> units = new ArrayList<>();
        units.add(new AgentMemoryUnit("n1", "客户申请120万元经营贷", "active", "",
                "org=local, user=developer, agent=credit-agent, session=cust-8801", Map.of()));
        DualAnswerRunner.SeedResult seed = new DualAnswerRunner.SeedResult(
                "陈述", "真实模型回答", "user: 陈述\nassistant: 真实模型回答", units, "");
        units.clear();

        assertEquals(1, seed.extractedUnits().size());
        assertEquals("真实模型回答", seed.agentAnswer());
        assertFalse(seed.syncedContent().isBlank());
        assertEquals(0, new DualAnswerRunner.SeedResult("s", "a", "", null, "").extractedUnits().size());
    }
}
