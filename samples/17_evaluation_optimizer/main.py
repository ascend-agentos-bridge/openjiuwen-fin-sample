"""样例 17 · 评测（真实 openjiuwen API）—— LLM-as-Judge 给信贷答复打分。

使用开源 openjiuwen Agent Core（v0.1.16）真实 API（agent_evolving 模块）:
- Case(inputs, label): 评测用例（label 为标准答案）
- DefaultEvaluator(model_config, model_client_config).evaluate(case, predict):
  LLM 作为裁判对比 predict 与 label, 输出 score(0~1) + reason（同步调用）
- batch_evaluate: 批量评测

场景: 两份客服答复——语义正确的和语义错误的, LLM 裁判应给出可区分的分数。

运行: python main.py
"""
import os

from openjiuwen.agent_evolving import Case, DefaultEvaluator
from openjiuwen.core.foundation.llm import ModelClientConfig, ModelRequestConfig

API_BASE = os.getenv("API_BASE", "http://127.0.0.1:1234/v1")
API_KEY = os.getenv("API_KEY", "lm-studio")
MODEL_NAME = os.getenv("MODEL_NAME", "google/gemma-4-e2b")
MODEL_PROVIDER = os.getenv("MODEL_PROVIDER", "openai")


def main():
    evaluator = DefaultEvaluator(
        model_config=ModelRequestConfig(model_name=MODEL_NAME, temperature=0.2),
        model_client_config=ModelClientConfig(client_provider=MODEL_PROVIDER,
                                              api_base=API_BASE, api_key=API_KEY,
                                              timeout=600, max_retries=1, verify_ssl=False))

    case = Case(case_id="q1",
                inputs={"query": "个人消费贷提前还款有违约金吗？"},
                label={"answer": "个人消费贷提前结清免收违约金，需提前3个工作日预约。"})

    # ---- 语义正确的答复 vs 语义错误的答复 ----
    good = {"answer": "不用交违约金，提前 3 个工作日预约就可以全部提前结清。"}
    bad = {"answer": "提前还款要收取 5% 的违约金，且必须到柜面办理。"}

    r_good = evaluator.evaluate(case, good)
    r_bad = evaluator.evaluate(case, bad)
    print("=== 好答复 ===")
    print(f"score={r_good.score}")
    print(f"reason={str(r_good.reason)[:120]}")
    print("\n=== 坏答复 ===")
    print(f"score={r_bad.score}")
    print(f"reason={str(r_bad.reason)[:120]}")

    # ---- 验收断言: LLM 裁判必须区分好坏 ----
    assert 0.0 <= r_good.score <= 1.0 and 0.0 <= r_bad.score <= 1.0
    assert r_good.score > r_bad.score, \
        f"好答复({r_good.score}) 应得分高于坏答复({r_bad.score})"
    assert r_bad.score <= 0.5, f"错误答复应低分, 实际 {r_bad.score}"
    print("\nSUCCESS: LLM-as-Judge 区分好坏答复 验收通过")


if __name__ == "__main__":
    main()
