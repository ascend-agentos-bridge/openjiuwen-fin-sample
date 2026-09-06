"""样例 04 · Structured Output（真实 openjiuwen API）—— 贷款申请信息抽取。

使用开源 openjiuwen Agent Core（v0.1.16）真实 API:
- Model(model_client_config, model_config).invoke([...]): 不建 Agent 直接调模型
- JsonOutputParser.parse: 模型输出 → dict（异步）; 坏输出返回 None + ERROR 日志（不抛错）
- dict → dataclass: 类型化后供核心系统入库

运行前提: 本地 OpenAI 兼容网关（默认 127.0.0.1:1234, qwen3-4b-thinking）
运行: python main.py
"""
import asyncio
import json
import os
from dataclasses import dataclass, fields

from openjiuwen.core.foundation.llm import Model, ModelClientConfig, ModelRequestConfig
from openjiuwen.core.foundation.llm.output_parsers import JsonOutputParser

API_BASE = os.getenv("API_BASE", "http://127.0.0.1:1234/v1")
API_KEY = os.getenv("API_KEY", "lm-studio")
MODEL_NAME = os.getenv("MODEL_NAME", "google/gemma-4-e2b")
MODEL_PROVIDER = os.getenv("MODEL_PROVIDER", "openai")


@dataclass
class LoanApplication:
    applicant: str
    product: str
    amount_wan: float
    term_months: int
    purpose: str


def to_loan_application(d: dict) -> LoanApplication:
    missing = [f.name for f in fields(LoanApplication) if f.name not in d]
    if missing:
        raise ValueError(f"JSON 缺少必需字段: {missing}")
    return LoanApplication(
        applicant=str(d["applicant"]), product=str(d["product"]),
        amount_wan=float(d["amount_wan"]), term_months=int(d["term_months"]),
        purpose=str(d["purpose"]))


async def main():
    model = Model(
        ModelClientConfig(client_provider=MODEL_PROVIDER, api_base=API_BASE,
                          api_key=API_KEY, timeout=300, verify_ssl=False),
        ModelRequestConfig(model=MODEL_NAME, temperature=0.2))
    parser = JsonOutputParser()

    # ---- 1. 口述申请单 → 模型抽 JSON → parser → 强类型 ----
    user_text = ("从下面的贷款申请单中抽取信息，只输出 JSON（不要多余文字），"
                 '字段: applicant(申请人), product(产品), amount_wan(金额万元), '
                 'term_months(期限月), purpose(用途)。'
                 "申请单: 客户张伟想申请个人消费贷 30 万，分 3 年还，用途是房屋装修。")
    resp = await model.invoke([{"role": "user", "content": user_text}])
    raw = resp.content
    print("=== 模型原始输出 ===")
    print(raw[:200])
    data = await parser.parse(raw)
    app = to_loan_application(data)
    print("\n=== 类型化结果 ===")
    print(app)
    print(f"类型: amount_wan={type(app.amount_wan).__name__}, "
          f"term_months={type(app.term_months).__name__}")
    print(f"核心系统入参: insert_loan_application(applicant='{app.applicant}', "
          f"amount={int(app.amount_wan * 10000)})")

    # ---- 2. 坏输出的真实行为: parser 返回 None + 打 ERROR 日志, 不抛错 ----
    # (代码块包裹的 ```json ... ``` 能剥出 dict; 纯坏文本/夹文字返回 None)
    print("\n=== 坏输出处理（真实行为: 返回 None, 需开发者显式兜底） ===")
    md = await parser.parse('```json\n{"applicant": "张伟", "amount_wan": 30}\n```')
    bad = await parser.parse("我觉得这个客户挺好的，就没啥问题吧。")
    print(f"markdown 包裹 -> {md}")
    print(f"纯坏文本     -> {bad}")
    assert md == {"applicant": "张伟", "amount_wan": 30}
    assert bad is None, "真实行为: 坏文本 parse 返回 None"
    if bad is None:
        print("=> 兜底策略: 返回 None 时走重试/人工录入, 绝不把 None 传给业务层")

    # ---- 3. 验收断言 ----
    assert app.applicant == "张伟" and app.amount_wan == 30.0 and app.term_months == 36
    assert isinstance(app.amount_wan, float) and isinstance(app.term_months, int)
    assert isinstance(data, dict)
    print("\nSUCCESS: Model 直调 + JsonOutputParser + 强类型化 + None 兜底 验收通过")


if __name__ == "__main__":
    asyncio.run(main())
