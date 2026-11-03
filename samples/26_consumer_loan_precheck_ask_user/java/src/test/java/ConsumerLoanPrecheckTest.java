import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/** 消费贷预审规则纯函数单测（不依赖模型）。 */
class ConsumerLoanPrecheckTest {
    private static final double DELTA = 1e-9;

    @Test
    void amountRangeUsesMidpoint() {
        assertEquals(15.0, ConsumerLoanPrecheck.amountWan("10万 - 20万元"), DELTA);
        assertEquals(2.5, ConsumerLoanPrecheck.amountWan("1-4万"), DELTA);
    }

    @Test
    void openEndedAmountUsesStartValue() {
        assertEquals(50.0, ConsumerLoanPrecheck.amountWan("50万元以上"), DELTA);
    }

    @Test
    void singleAmountAndMissingAmountFallBackToDefaults() {
        assertEquals(20.0, ConsumerLoanPrecheck.amountWan("20万"), DELTA);
        assertEquals(20.0, ConsumerLoanPrecheck.amountWan(""), DELTA);
        assertEquals(20.0, ConsumerLoanPrecheck.amountWan(null), DELTA);
    }

    @Test
    void yearTermIsConvertedToMonths() {
        assertEquals(48, ConsumerLoanPrecheck.termMonths("3 - 5年"));
        assertEquals(24, ConsumerLoanPrecheck.termMonths("2年"));
    }

    @Test
    void monthTermIsKeptAsMonths() {
        assertEquals(18, ConsumerLoanPrecheck.termMonths("12-24月"));
        assertEquals(36, ConsumerLoanPrecheck.termMonths("36月"));
    }

    @Test
    void missingTermFallsBackToThreeYears() {
        assertEquals(36, ConsumerLoanPrecheck.termMonths("长期"));
        assertEquals(36, ConsumerLoanPrecheck.termMonths(null));
    }

    @Test
    void renovationWithinCapPassesWithRenovationRate() {
        Map<String, Object> result = ConsumerLoanPrecheck.evaluatePrecheck("装修", "10万 - 20万元", "3 - 5年");

        assertEquals(15.0, number(result, "target_amount_wan"), DELTA);
        assertEquals(15.0, number(result, "approved_amount_wan"), DELTA);
        assertEquals(3.6, number(result, "annual_rate_pct"), DELTA);
        assertEquals(48, ((Number) result.get("term_months")).intValue());
        assertFalse((Boolean) result.get("manual_review"));
        assertTrue(String.valueOf(result.get("conclusion")).contains("预审通过"));
    }

    @Test
    void renovationOverCapIsCappedAndNeedsManualReview() {
        Map<String, Object> result = ConsumerLoanPrecheck.evaluatePrecheck("装修", "40万 - 50万元", "3 - 5年");

        assertEquals(45.0, number(result, "target_amount_wan"), DELTA);
        assertEquals(30.0, number(result, "approved_amount_wan"), DELTA);
        assertEquals(3.6, number(result, "annual_rate_pct"), DELTA);
        assertTrue((Boolean) result.get("manual_review"));
        assertTrue(String.valueOf(result.get("conclusion")).contains("人工复核"));
    }

    @Test
    void openEndedAmountAboveCapNeedsManualReview() {
        Map<String, Object> result = ConsumerLoanPrecheck.evaluatePrecheck("装修", "50万元以上", "5年");

        assertEquals(50.0, number(result, "target_amount_wan"), DELTA);
        assertEquals(30.0, number(result, "approved_amount_wan"), DELTA);
        assertTrue((Boolean) result.get("manual_review"));
    }

    @Test
    void otherPurposeHasLowerCapAndHigherRate() {
        Map<String, Object> result = ConsumerLoanPrecheck.evaluatePrecheck("购车", "5万 - 10万元", "1 - 2年");

        assertEquals(7.5, number(result, "approved_amount_wan"), DELTA);
        assertEquals(4.2, number(result, "annual_rate_pct"), DELTA);
        assertEquals(18, ((Number) result.get("term_months")).intValue());
        assertFalse((Boolean) result.get("manual_review"));
    }

    @Test
    void otherPurposeOverCapIsCappedAtTwoHundredThousand() {
        Map<String, Object> result = ConsumerLoanPrecheck.evaluatePrecheck("经营周转", "30万", "2年");

        assertEquals(20.0, number(result, "approved_amount_wan"), DELTA);
        assertEquals(4.2, number(result, "annual_rate_pct"), DELTA);
        assertTrue((Boolean) result.get("manual_review"));
    }

    @Test
    void monthlyPaymentFollowsEqualPrincipalAndInterestFormula() {
        Map<String, Object> result = ConsumerLoanPrecheck.evaluatePrecheck("装修", "10万 - 20万元", "3 - 5年");

        double principal = 15.0 * 10000;
        double monthlyRate = 0.036 / 12;
        int months = 48;
        double factor = Math.pow(1 + monthlyRate, months);
        double expected = principal * monthlyRate * factor / (factor - 1);
        assertEquals(Math.round(expected * 100) / 100.0, number(result, "monthly_payment_yuan"), 0.005);
    }

    @Test
    void resultOwnsEveryFieldTheModelReports() {
        Map<String, Object> result = ConsumerLoanPrecheck.evaluatePrecheck("装修", "10万 - 20万元", "3 - 5年");

        assertEquals(List.of("purpose", "target_amount_wan", "approved_amount_wan", "annual_rate_pct",
                "term_months", "monthly_payment_yuan", "manual_review", "conclusion"),
                List.copyOf(result.keySet()));
        assertEquals("装修", result.get("purpose"));
    }

    private static double number(Map<String, Object> result, String field) {
        return ((Number) result.get(field)).doubleValue();
    }
}
