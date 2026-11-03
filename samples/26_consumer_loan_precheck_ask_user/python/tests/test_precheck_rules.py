"""UT: 消费贷预审规则（不依赖模型），验证金额/期限解析与预审规则。"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from main import (  # noqa: E402  (依赖上面的 sys.path 注入)
    _amount_wan,
    _term_months,
    evaluate_consumer_loan_precheck,
)


class AmountParseTest(unittest.TestCase):
    def test_range_midpoint(self):
        self.assertEqual(_amount_wan("10万 - 20万元"), 15.0)

    def test_open_ended(self):
        self.assertEqual(_amount_wan("50万元以上"), 50.0)

    def test_single_value(self):
        self.assertEqual(_amount_wan("20万"), 20.0)

    def test_no_number_uses_default(self):
        self.assertEqual(_amount_wan(""), 20.0)


class TermParseTest(unittest.TestCase):
    def test_year_range(self):
        self.assertEqual(_term_months("3 - 5年"), 48)

    def test_month_range(self):
        self.assertEqual(_term_months("12-24月"), 18)

    def test_no_number_uses_default(self):
        self.assertEqual(_term_months("长期"), 36)


class PrecheckRuleTest(unittest.TestCase):
    def test_renovation_within_cap(self):
        result = evaluate_consumer_loan_precheck("装修", "10万 - 20万元", "3 - 5年")
        self.assertEqual(result["approved_amount_wan"], 15.0)
        self.assertEqual(result["annual_rate_pct"], 3.6)
        self.assertEqual(result["term_months"], 48)
        self.assertFalse(result["manual_review"])
        self.assertIn("预审通过", result["conclusion"])

        principal, monthly_rate, months = 150000, 0.036 / 12, 48
        factor = (1 + monthly_rate) ** months
        expected = principal * monthly_rate * factor / (factor - 1)
        self.assertAlmostEqual(result["monthly_payment_yuan"], round(expected, 2), places=2)

    def test_renovation_over_cap_needs_manual_review(self):
        result = evaluate_consumer_loan_precheck("装修", "40万 - 50万元", "3 - 5年")
        self.assertEqual(result["approved_amount_wan"], 30.0)
        self.assertTrue(result["manual_review"])
        self.assertIn("人工复核", result["conclusion"])

    def test_other_purpose_has_lower_cap_and_higher_rate(self):
        result = evaluate_consumer_loan_precheck("购车", "5万 - 10万元", "1 - 2年")
        self.assertEqual(result["approved_amount_wan"], 7.5)
        self.assertEqual(result["annual_rate_pct"], 4.2)


if __name__ == "__main__":
    unittest.main()
