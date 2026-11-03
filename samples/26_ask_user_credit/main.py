"""样例 26 · ask_user 人工澄清（HITL）—— 消费贷预审先问清客户诉求。

使用开源 openjiuwen Agent Core 真实 API（openjiuwen==0.1.20）:
- AskUserRail: harness 层的人机交互护栏，注册后自动挂载内置 ask_user 工具
- agent.invoke 第一次调用: 模型信息不全 → 调用 ask_user → 框架返回中断
  (result_type="interrupt", state[*].payload.value 是 ToolCallInterruptRequest)
- InteractiveInput().answer(interrupt_id, {"answers": {问题原文: 选项}}) 回填客户选择
- 同一 session 第二次 invoke: 从中断处恢复 → 模型调用 consumer_loan_precheck
  规则工具 → 输出预审意见

场景: 客户陈女士咨询"贷点钱装修，能批多少"（没说金额和期限）。
Agent 不瞎猜，先用 ask_user 一次性问清金额区间/期限，拿到答案后再做规则预审。

运行: python main.py
"""
import asyncio
import os
import re

from openjiuwen.core.foundation.llm import ModelClientConfig, ModelRequestConfig
from openjiuwen.core.foundation.tool import tool
from openjiuwen.core.session import InteractiveInput
from openjiuwen.core.session.agent import create_agent_session
from openjiuwen.core.single_agent import AgentCard, ReActAgent, ReActAgentConfig
from openjiuwen.harness.rails import AskUserRail

# ---- 0. LLM 配置: 与官方 examples / 本仓库其它样例相同的环境变量约定 ----
API_BASE = os.getenv("API_BASE", "http://127.0.0.1:1234/v1")
API_KEY = os.getenv("API_KEY", "lm-studio")
MODEL_NAME = os.getenv("MODEL_NAME", "google/gemma-4-e4b")
MODEL_PROVIDER = os.getenv("MODEL_PROVIDER", "openai")

SESSION_ID = "sample-26-ask-user-credit"

# 客户画像: 装修预算 15 万左右、希望 4-5 年还清（模拟客户在手机银行里的选择）
CUSTOMER_TARGET_AMOUNT_WAN = 15.0
CUSTOMER_TARGET_TERM_YEARS = 4.5

# 记录预审工具的真实调用，供末尾验收断言使用
PRECHECK_CALLS = []


# ---- 1. 业务工具: 消费贷规则预审（确定性规则，不依赖模型发挥） ----
def _numbers(text: str) -> list:
    return [float(n) for n in re.findall(r"\d+(?:\.\d+)?", text or "")]


def _amount_wan(amount_range: str) -> float:
    """把 "10-30万元" / "30万元以上" 这类区间转成中位数金额（万元）。"""
    nums = _numbers(amount_range)
    if not nums:
        return 20.0
    if "以上" in amount_range:
        return nums[0]
    return (nums[0] + nums[1]) / 2 if len(nums) >= 2 else nums[0]


def _term_months(term_range: str) -> int:
    """把 "4-5年" / "36期" 这类期限转成月数。"""
    nums = _numbers(term_range)
    if not nums:
        return 36
    years = (nums[0] + nums[1]) / 2 if len(nums) >= 2 else nums[0]
    if "月" in term_range and "年" not in term_range:
        return int(round(years))
    return int(round(years * 12))


@tool(description=(
    "消费贷预审规则计算。入参为贷款用途、金额区间、期限区间的原文，"
    "返回可贷额度(万元)、年利率(%)、每月还款(元)、是否需人工复核与预审结论。"))
def consumer_loan_precheck(purpose: str, amount_range: str, term_range: str) -> dict:
    target_wan = _amount_wan(amount_range)
    months = _term_months(term_range)
    # 规则: 装修贷上限 30 万元、年利率 3.6%; 其它用途 4.2%
    cap_wan = 30.0 if "装修" in (purpose or "") else 20.0
    annual_rate = 0.036 if "装修" in (purpose or "") else 0.042
    approved_wan = min(target_wan, cap_wan)

    monthly_rate = annual_rate / 12
    principal = approved_wan * 10000
    if monthly_rate and months:
        factor = (1 + monthly_rate) ** months
        monthly_payment = principal * monthly_rate * factor / (factor - 1)
    else:
        monthly_payment = principal / max(months, 1)

    manual_review = target_wan > cap_wan
    conclusion = (
        f"超出装修贷上限{cap_wan:.0f}万元，需人工复核后确定额度"
        if manual_review
        else f"预审通过，可批 {approved_wan:.1f} 万元，进入正式审批流程"
    )
    result = {
        "purpose": purpose,
        "target_amount_wan": round(target_wan, 1),
        "approved_amount_wan": round(approved_wan, 1),
        "annual_rate_pct": round(annual_rate * 100, 2),
        "term_months": months,
        "monthly_payment_yuan": round(monthly_payment, 2),
        "manual_review": manual_review,
        "conclusion": conclusion,
    }
    PRECHECK_CALLS.append(result)
    return result


# ---- 2. 组装 Agent: ReActAgent + AskUserRail（自动挂载 ask_user 工具） ----
def build_agent() -> ReActAgent:
    model_config = ModelRequestConfig(model=MODEL_NAME, temperature=0.2)
    model_client = ModelClientConfig(
        client_provider=MODEL_PROVIDER, api_base=API_BASE, api_key=API_KEY,
        timeout=600, max_retries=1, verify_ssl=False)
    react_config = ReActAgentConfig(
        model_client_config=model_client,
        model_config_obj=model_config,
        prompt_template=[{
            "role": "system",
            "content": (
                "你是银行消费贷预审助手。客户申请信息不全（缺金额、期限等关键要素）时，"
                "必须先调用 ask_user 工具向客户澄清，不要自行假设；"
                "拿到客户回答后，必须调用 consumer_loan_precheck 工具做规则预审，"
                "再根据工具返回结果给出预审意见，不要编造额度、利率和月供。"
            ),
        }],
        max_iterations=5,
    )
    agent = ReActAgent(card=AgentCard(
        id="consumer_loan_precheck_agent", description="消费贷预审助手")).configure(react_config)
    # 当前 API 推荐用 add_ability(card, 实例) 同时完成资源注册与能力挂载
    agent.ability_manager.add_ability(consumer_loan_precheck.card, consumer_loan_precheck)
    return agent


# ---- 3. 模拟客户作答: 金额挑最接近 15 万、期限挑最接近 4.5 年的选项 ----
def _option_midpoint(label: str):
    """把选项文案里的数值转成中位数: 金额按万元、期限按年; 无数字返回 None。"""
    nums = _numbers(label)
    if not nums:
        return None
    value = nums[0] if "以上" in label else (
        (nums[0] + nums[1]) / 2 if len(nums) >= 2 else nums[0])
    if "月" in label and "年" not in label:
        value = value / 12
    return value


def pick_option(question: dict) -> str:
    text = f"{question.get('header', '')}{question.get('question', '')}"
    labels = [opt.get("label", "") for opt in (question.get("options") or [])]
    if not labels:
        return ""
    if "金额" in text or "额度" in text:
        target = CUSTOMER_TARGET_AMOUNT_WAN
    elif "期限" in text or "期数" in text or "还款" in text:
        target = CUSTOMER_TARGET_TERM_YEARS
    else:
        return labels[0]
    scored = [(abs(mid - target), label)
              for label in labels if (mid := _option_midpoint(label)) is not None]
    return min(scored)[1] if scored else labels[0]


def print_questions(request) -> list:
    questions = getattr(request, "questions", None) or []
    print(f"  interrupt_kind = {getattr(request, 'interrupt_kind', '')}, "
          f"interrupt_id = {getattr(request, 'interrupt_id', '')}")
    print(f"  共 {len(questions)} 个问题（ask_user 支持 1-4 个问题、每题 2-4 个选项）：")
    for i, question in enumerate(questions, 1):
        print(f"\n  问题{i} [{question.get('header', '')}] {question.get('question', '')}")
        for j, opt in enumerate(question.get("options", []), 1):
            print(f"    {j}. {opt.get('label', '')} —— {opt.get('description', '')}")
    return questions


async def main():
    agent = build_agent()
    agent = await agent.register_rail(AskUserRail())
    session = create_agent_session(session_id=SESSION_ID, card=agent.card)
    await session.pre_run()

    # ---- 第 1 次 invoke: 信息不全 → ask_user 中断 ----
    print("=== 第 1 次 invoke：客户提出模糊申请，Agent 触发 ask_user ===")
    r1 = await agent.invoke(
        {"query": "客户陈女士咨询：我想贷点钱装修，你们能批多少？", "conversation_id": SESSION_ID},
        session=session)
    print(f"result_type = {r1.get('result_type')}, interrupt_ids = {r1.get('interrupt_ids')}")

    # 中断返回的 state[0].payload.value 就是 ask_user 的 ToolCallInterruptRequest
    assert r1.get("result_type") == "interrupt", f"应返回中断: {str(r1)[:500]}"
    assert r1.get("interrupt_ids"), f"中断应带 interrupt_ids: {str(r1)[:500]}"
    state_outputs = r1.get("state") or []
    assert state_outputs, f"中断 state 为空: {str(r1)[:500]}"
    request = state_outputs[0].payload.value
    questions = print_questions(request)

    # 验收 1: ask_user 的问题结构符合工具约定
    assert 1 <= len(questions) <= 4, f"ask_user 应问 1-4 个问题: {questions}"
    for question in questions:
        assert question.get("question"), f"问题缺少 question 文本: {question}"
        options = question.get("options") or []
        assert 2 <= len(options) <= 4, f"每个问题应有 2-4 个选项: {question}"

    # ---- 模拟客户作答 + 恢复执行 ----
    answers = {q["question"]: pick_option(q) for q in questions}
    assert all(answers.values()), f"客户作答不能为空: {answers}"
    print("\n=== 模拟客户在银行 App 中选择 ===")
    for question_text, answer in answers.items():
        print(f"  「{question_text}」→ {answer}")

    interactive_input = InteractiveInput()
    interactive_input.answer(getattr(request, "interrupt_id", ""), {"answers": answers})

    print("\n=== 第 2 次 invoke：回填答案，Agent 从中断处恢复 ===")
    r2 = await agent.invoke({"query": interactive_input, "conversation_id": SESSION_ID}, session=session)
    print(f"result_type = {r2.get('result_type')}")
    answer_text = r2.get("output", "")

    # ---- 验收 2: 恢复后拿到最终预审意见，且预审工具真的被调用 ----
    assert r2.get("result_type") == "answer", f"恢复后应正常作答: {str(r2)[:500]}"
    assert answer_text.strip(), f"预审意见不能为空: {str(r2)[:500]}"

    print("\n=== 最终预审意见 ===")
    print(answer_text)

    print("\n=== 预审工具真实调用记录（规则计算由工具完成） ===")
    assert PRECHECK_CALLS, "consumer_loan_precheck 工具未被调用"
    for call in PRECHECK_CALLS:
        print(f"  {call}")

    print("\nSUCCESS: ask_user 中断 → answers 回填 → 恢复执行 → 消费贷预审 验收通过")
    await session.post_run()


if __name__ == "__main__":
    asyncio.run(main())
