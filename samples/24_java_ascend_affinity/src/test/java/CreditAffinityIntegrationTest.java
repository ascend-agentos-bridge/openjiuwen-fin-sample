import static org.junit.jupiter.api.Assertions.*;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.base.TagMatchStrategy;
import com.openjiuwen.core.singleagent.rail.AgentCallbackEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Tag("integration")
@Timeout(180)
class CreditAffinityIntegrationTest {
    @Test
    void realModelUsesToolAcrossThreeRounds() {
        var session = "affinity-test-" + UUID.randomUUID();
        var agent = CreditAffinity.createAgent(false);
        var calls = new AtomicInteger();
        agent.registerCallback(AgentCallbackEvent.AFTER_TOOL_CALL, ctx -> calls.incrementAndGet(), 10);
        try {
            for (int round = 1; round <= 3; round++) {
                var result = (Map<?, ?>) Runner.runAgent(agent,
                        Map.of("query", CreditAffinity.query(round), "conversation_id", session), null, null);
                assertEquals("answer", result.get("result_type"));
                var text = String.valueOf(result.get("output"));
                assertTrue(text.contains("C1001") && text.contains("735"), text);
                assertEquals(round, calls.get(), "Every round must refresh the tool data");
                System.out.println("真实普通模型 round=" + round + " " + text);
            }
        } finally {
            Runner.resourceMgr().removeTool(CreditAffinity.TOOL_ID, CreditAffinity.AGENT_ID, TagMatchStrategy.ALL, true);
            Runner.release(session);
            Runner.stop();
        }
    }
}
