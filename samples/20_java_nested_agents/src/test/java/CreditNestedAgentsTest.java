import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import org.junit.jupiter.api.Test;

import java.util.Map;

/** The business tool is tested locally; no language model is invoked or replaced. */
class CreditNestedAgentsTest {
    @Test
    void frameworkErrorResultIsAnApplicationFailure() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> CreditNestedAgents.printResult(Map.of("result_type", "error", "output", "模型迭代耗尽")));
        assertTrue(error.getMessage().contains("模型迭代耗尽"));
    }

    @Test
    void ordinaryAnswerDoesNotNeedAcceptanceMarkers() {
        assertDoesNotThrow(() -> CreditNestedAgents.printResult(
                Map.of("result_type", "answer", "output", "普通业务结果")));
    }

    @Test
    void returnsExplicitDemoCreditFactsForKnownCustomer() throws Exception {
        LocalFunction tool = CreditNestedAgents.createCreditQueryTool();

        Map<?, ?> record = assertInstanceOf(Map.class, tool.invoke(Map.of("customer_id", "C1001"), Map.of()));

        assertEquals("C1001", record.get("customer_id"));
        assertEquals(735, record.get("credit_score"));
        assertEquals(0, record.get("overdue_count_24m"));
        assertEquals("虚构演示记录，不是真实征信", record.get("data_source"));
        assertTrue(String.valueOf(record.get("report_id")).matches("CR-[0-9a-f]{8}"));
    }

    @Test
    void rejectsCustomersWithoutDemoRecords() {
        LocalFunction tool = CreditNestedAgents.createCreditQueryTool();

        assertThrows(IllegalArgumentException.class,
                () -> tool.invoke(Map.of("customer_id", "C9999"), Map.of()));
    }

    @Test
    void generatesANewReportReferenceForEachQuery() throws Exception {
        LocalFunction tool = CreditNestedAgents.createCreditQueryTool();

        Map<?, ?> first = assertInstanceOf(Map.class, tool.invoke(Map.of("customer_id", "C1001"), Map.of()));
        Map<?, ?> second = assertInstanceOf(Map.class, tool.invoke(Map.of("customer_id", "C1001"), Map.of()));

        assertNotEquals(first.get("report_id"), second.get("report_id"));
    }
}
