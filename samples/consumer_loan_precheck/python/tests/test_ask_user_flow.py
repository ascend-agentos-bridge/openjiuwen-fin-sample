"""UT(集成): 真实模型走完 ask_user 追问 → 观众回答 → 恢复 → 预审意见。

依赖真实模型服务（环境变量 API_BASE / API_KEY / MODEL_NAME），不做任何 mock。
客户回答通过 run_precheck 的 ask_user 回调注入（与 main.py 的终端交互同一个入口）。
"""
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from main import run_precheck  # noqa: E402  (依赖上面的 sys.path 注入)


def _option_midpoint(label: str):
    nums = [float(n) for n in re.findall(r"\d+(?:\.\d+)?", label or "")]
    if not nums:
        return None
    value = nums[0] if "以上" in label else (
        (nums[0] + nums[1]) / 2 if len(nums) >= 2 else nums[0])
    if "月" in label and "年" not in label:
        value = value / 12
    return value


class AskUserFlowTest(unittest.IsolatedAsyncioTestCase):
    async def test_ask_user_then_resume(self):
        asked_questions = []

        def scripted_customer(question):
            """脚本化客户: 金额挑最接近 15 万、期限挑最接近 4.5 年的选项。"""
            asked_questions.append(question)
            text = f"{question.get('header', '')}{question.get('question', '')}"
            labels = [opt.get("label", "") for opt in (question.get("options") or [])]
            if "金额" in text or "额度" in text:
                target = 15.0
            elif "期限" in text or "期数" in text or "还款" in text:
                target = 4.5
            else:
                return labels[0] if labels else "按推荐方案"
            scored = [(abs(mid - target), label) for label in labels
                      if (mid := _option_midpoint(label)) is not None]
            return min(scored)[1] if scored else labels[0]

        opinion = await run_precheck(
            "客户陈女士咨询：我想贷点钱装修，你们能批多少？", scripted_customer)

        self.assertTrue(asked_questions, "Agent 应先通过 ask_user 追问客户")
        for question in asked_questions:
            self.assertTrue(question.get("question"))
            self.assertTrue(2 <= len(question.get("options") or []) <= 4, question)
        self.assertTrue(opinion.strip(), "恢复后应输出预审意见")


if __name__ == "__main__":
    unittest.main()
