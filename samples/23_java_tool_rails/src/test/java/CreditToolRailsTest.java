import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.interrupt.InterruptRequest;
import com.openjiuwen.core.singleagent.interrupt.ToolInterruptException;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentCallbackEvent;
import com.openjiuwen.core.singleagent.rail.AgentCallbackFirer;
import com.openjiuwen.core.singleagent.rail.RailExecutor;
import com.openjiuwen.harness.rails.interrupt.ApproveResult;
import com.openjiuwen.harness.rails.interrupt.InterruptDecision;
import com.openjiuwen.harness.rails.interrupt.InterruptResult;
import com.openjiuwen.harness.rails.interrupt.RejectResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.atomic.AtomicInteger;

class CreditToolRailsTest {
    @ParameterizedTest
    @ValueSource(doubles = {-1, 0, 100000.01})
    void rejectsAmountsOutsideBusinessLimit(double amount) {
        assertInstanceOf(RejectResult.class, decide(amount, null));
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.01, 20000})
    void approvesAmountsAtOrBelowAutomaticLimit(double amount) {
        assertInstanceOf(ApproveResult.class, decide(amount, null));
    }

    @ParameterizedTest
    @ValueSource(doubles = {20000.01, 50000, 100000})
    void pausesLargeAmountsForApproval(double amount) {
        InterruptResult result = assertInstanceOf(InterruptResult.class, decide(amount, null));
        assertEquals("loan-call", result.getRequest().getInterruptId());
    }

    @Test
    void resumesAfterExplicitApproval() {
        assertInstanceOf(ApproveResult.class, decide(50000, Boolean.TRUE));
    }

    @Test
    void rejectsDeclinedApproval() {
        assertInstanceOf(RejectResult.class, decide(50000, Boolean.FALSE));
    }

    @Test
    void rejectsAmbiguousApprovalInsteadOfTreatingTextAsConsent() {
        assertInstanceOf(RejectResult.class, decide(50000, "true"));
    }

    @Test
    void rechecksBusinessLimitEvenWhenApprovalIsProvided() {
        assertInstanceOf(RejectResult.class, decide(100001, Boolean.TRUE));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"{}", "{\"amount\":\"50000\"}", "null", "not-json", "{\"amount\":10000} not-json"})
    void rejectsMissingOrMalformedAmounts(String arguments) {
        ToolCall call = ToolCall.builder().id("loan-call").name("disburse_loan").arguments(arguments).build();
        assertInstanceOf(RejectResult.class, new CreditToolRails.LoanApprovalRail()
                .resolveInterrupt(AgentCallbackContext.builder().build(), call, null));
    }

    @Test
    void retriesTemporaryBankFailureOnceAndReturnsSuccess() {
        AtomicInteger attempts = new AtomicInteger();
        String result = RailExecutor.execute(retryContext(), null, null, AgentCallbackEvent.ON_TOOL_EXCEPTION, () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("BANK_BUSY: temporarily unavailable");
            }
            return "DISBURSED";
        }).orElseThrow();
        assertEquals("DISBURSED", result);
        assertEquals(2, attempts.get());
    }

    @Test
    void stopsAfterOneRetryWhenBankRemainsUnavailable() {
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> RailExecutor.execute(retryContext(), null, null,
                AgentCallbackEvent.ON_TOOL_EXCEPTION, () -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("BANK_BUSY: still unavailable");
                }));
        assertEquals(2, attempts.get());
    }

    @Test
    void doesNotRetryBusinessFailure() {
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () -> RailExecutor.execute(retryContext(), null, null,
                AgentCallbackEvent.ON_TOOL_EXCEPTION, () -> {
                    attempts.incrementAndGet();
                    throw new IllegalArgumentException("ACCOUNT_FROZEN");
                }));
        assertEquals(1, attempts.get());
    }

    @Test
    void neverRetriesAnApprovalInterruption() {
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(ToolInterruptException.class, () -> RailExecutor.execute(retryContext(), null, null,
                AgentCallbackEvent.ON_TOOL_EXCEPTION, () -> {
                    attempts.incrementAndGet();
                    throw new ToolInterruptException(InterruptRequest.builder().message("BANK_BUSY approval").build(),
                            ToolCall.builder().id("loan-call").name("disburse_loan").build());
                }));
        assertEquals(1, attempts.get());
    }

    private InterruptDecision decide(double amount, Object approval) {
        ToolCall call = ToolCall.builder().id("loan-call").name("disburse_loan")
                .arguments("{\"customer_id\":\"C1001\",\"amount\":" + amount + "}").build();
        return new CreditToolRails.LoanApprovalRail()
                .resolveInterrupt(AgentCallbackContext.builder().build(), call, approval);
    }

    private AgentCallbackContext retryContext() {
        CreditToolRails.BankRetryRail rail = new CreditToolRails.BankRetryRail();
        AgentCallbackFirer firer = (event, ctx) -> {
            if (event == AgentCallbackEvent.ON_TOOL_EXCEPTION) {
                rail.onToolException(ctx);
            }
        };
        return AgentCallbackContext.builder().agent(firer).build();
    }
}
