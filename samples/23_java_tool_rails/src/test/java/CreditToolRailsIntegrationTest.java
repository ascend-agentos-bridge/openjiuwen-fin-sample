import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.base.TagMatchStrategy;
import com.openjiuwen.core.session.checkpointer.CheckpointerFactory;
import com.openjiuwen.core.session.checkpointer.InMemoryCheckpointer;
import com.openjiuwen.core.session.interaction.InteractiveInput;
import com.openjiuwen.core.singleagent.ReActAgent;
import com.openjiuwen.core.singleagent.interrupt.ToolInterruptException;
import com.openjiuwen.core.singleagent.rail.AgentCallbackEvent;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Real model acceptance checks; no model stub or recorded response is used. */
@Tag("integration")
@Timeout(120)
class CreditToolRailsIntegrationTest {
    private final String sessionId = "loan-rails-test-" + UUID.randomUUID();
    private final List<Map<?, ?>> toolResults = new ArrayList<>();
    private final AtomicInteger bankFailures = new AtomicInteger();
    private final AtomicInteger modelCalls = new AtomicInteger();
    private ReActAgent agent;

    @BeforeEach
    void setUp() {
        CheckpointerFactory.setDefaultCheckpointer(new InMemoryCheckpointer());
        agent = CreditToolRails.createAgent();
        agent.registerCallback(AgentCallbackEvent.BEFORE_MODEL_CALL, ctx -> modelCalls.incrementAndGet(), 10);
        agent.registerCallback(AgentCallbackEvent.AFTER_TOOL_CALL, ctx -> {
            if (ctx.getInputs() instanceof ToolCallInputs inputs
                    && inputs.getToolResult() instanceof Map<?, ?> result) {
                toolResults.add(result);
            }
        }, 10);
        agent.registerCallback(AgentCallbackEvent.ON_TOOL_EXCEPTION, ctx -> {
            if (!(ctx.getException() instanceof ToolInterruptException)
                    && ctx.getException().getMessage().contains("BANK_BUSY")) {
                bankFailures.incrementAndGet();
            }
        }, 10);
    }

    @AfterEach
    void tearDown() {
        Runner.resourceMgr().removeTool("disburse_loan", "credit_tool_rails", TagMatchStrategy.ALL, true);
        Runner.release(sessionId);
        Runner.stop();
    }

    @Test
    void approvalResumesOriginalRequestAndRetriesTemporaryFailure() {
        Map<?, ?> paused = applyForLoan();
        assertEquals("interrupt", paused.get("result_type"));
        assertTrue(toolResults.isEmpty(), "Approval must precede bank execution");
        assertEquals(0, bankFailures.get(), "The bank must not be called before approval");

        Map<?, ?> result = resume(paused, true);
        assertEquals("answer", result.get("result_type"));
        assertFalse(String.valueOf(result.get("output")).isBlank());
        assertEquals(1, toolResults.size());
        assertEquals("DISBURSED", toolResults.get(0).get("status"));
        assertEquals("C1001", toolResults.get(0).get("customer_id"));
        assertEquals(50000.0, ((Number) toolResults.get(0).get("amount")).doubleValue());
        assertEquals(1, bankFailures.get(), "Exactly one temporary failure should be retried");
        assertTrue(modelCalls.get() >= 2, "Both turns must call the real model");
        System.out.println("真实模型验收（批准）: " + result);
    }

    @Test
    void declinedApprovalNeverExecutesBankTool() {
        Map<?, ?> paused = applyForLoan();
        assertEquals("interrupt", paused.get("result_type"));

        Map<?, ?> result = resume(paused, false);
        assertEquals("answer", result.get("result_type"));
        assertEquals(1, toolResults.size());
        assertEquals("REJECTED", toolResults.get(0).get("status"));
        assertEquals(0, bankFailures.get());
        assertTrue(modelCalls.get() >= 2);
        System.out.println("真实模型验收（拒绝）: " + result);
    }

    private Map<?, ?> applyForLoan() {
        return assertInstanceOf(Map.class, Runner.runAgent(agent,
                Map.of("query", "为客户 C1001 放款 50000 元。", "conversation_id", sessionId), null, null));
    }

    private Map<?, ?> resume(Map<?, ?> paused, boolean approved) {
        List<?> interruptIds = assertInstanceOf(List.class, paused.get("interrupt_ids"));
        assertEquals(1, interruptIds.size());
        InteractiveInput approval = new InteractiveInput();
        approval.update(String.valueOf(interruptIds.get(0)), approved);
        return assertInstanceOf(Map.class, Runner.runAgent(agent,
                Map.of("query", approval, "conversation_id", sessionId), null, null));
    }
}
