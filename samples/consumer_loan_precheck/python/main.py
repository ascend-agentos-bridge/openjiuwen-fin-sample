"""消费贷预审 Agent（Python 版）—— 客户信息不全时，Agent 先向客户澄清再预审。

使用开源 openjiuwen Agent Core 真实 API（openjiuwen==0.1.20）:
- AskUserRail: 注册后自动挂载内置 ask_user 工具；Agent 信息不全时会追问客户
- run_precheck: 一次预审对话（Agent 追问 → 宿主收集客户回答 → 继续执行）
- consumer_loan_precheck: 消费贷规则预审工具，额度/利率/月供都由规则计算

运行: 在 python/ 目录执行 `python main.py`，按提示输入客户问题与澄清信息。
模型配置见 README 的环境变量表。
"""
import asyncio
import os
import re
from typing import Any, Callable, Dict, List, Tuple

from openjiuwen.core.foundation.llm import ModelClientConfig, ModelRequestConfig
from openjiuwen.core.foundation.tool import tool
from openjiuwen.core.session import InteractiveInput
from openjiuwen.core.session.agent import create_agent_session
from openjiuwen.core.single_agent import AgentCard, ReActAgent, ReActAgentConfig
from openjiuwen.harness.rails import AskUserRail

# ---- 模型配置: 与官方 examples / 本仓库其它样例相同的环境变量约定 ----
API_BASE = os.getenv("API_BASE", "http://127.0.0.1:1234/v1")
API_KEY = os.getenv("API_KEY", "lm-studio")
MODEL_NAME = os.getenv("MODEL_NAME", "google/gemma-4-e4b")
MODEL_PROVIDER = os.getenv("MODEL_PROVIDER", "openai")

SESSION_ID = "consumer-loan-precheck"

SYSTEM_PROMPT = (
    "你是银行消费贷预审助手。客户申请信息不全（缺金额、期限等关键要素）时，"
    "必须先调用 ask_user 工具向客户澄清，不要自行假设；"
    "拿到客户回答后，必须调用 consumer_loan_precheck 工具做规则预审，"
    "再根据工具返回结果给出预审意见，不要编造额度、利率和月供。"
)


# ---- 1. 业务工具: 消费贷预审规则（确定性计算，模型只负责组织结论） ----
def _numbers(text: str) -> List[float]:
    return [float(n) for n in re.findall(r"\d+(?:\.\d+)?", text or "")]


def _amount_wan(amount_range: str) -> float:
    """把 "10万 - 20万元" / "30万元以上" 这类区间转成中位数金额（万元）。"""
    nums = _numbers(amount_range)
    if not nums:
        return 20.0
    if "以上" in amount_range:
        return nums[0]
    return (nums[0] + nums[1]) / 2 if len(nums) >= 2 else nums[0]


def _term_months(term_range: str) -> int:
    """把 "3 - 5年" / "12-24月" 这类期限转成月数。"""
    nums = _numbers(term_range)
    if not nums:
        return 36
    value = (nums[0] + nums[1]) / 2 if len(nums) >= 2 else nums[0]
    if "月" in term_range and "年" not in term_range:
        return int(round(value))
    return int(round(value * 12))


def evaluate_consumer_loan_precheck(purpose: str, amount_range: str, term_range: str) -> Dict[str, Any]:
    """消费贷预审规则（纯业务计算）: 返回额度、利率、月供、是否人工复核与结论。"""
    target_wan = _amount_wan(amount_range)
    months = _term_months(term_range)
    # 规则: 装修贷上限 30 万元、年利率 3.6%; 其它用途上限 20 万元、年利率 4.2%
    is_renovation = "装修" in (purpose or "")
    cap_wan = 30.0 if is_renovation else 20.0
    annual_rate = 0.036 if is_renovation else 0.042
    approved_wan = min(target_wan, cap_wan)

    monthly_rate = annual_rate / 12
    principal = approved_wan * 10000
    factor = (1 + monthly_rate) ** months
    monthly_payment = principal * monthly_rate * factor / (factor - 1)

    manual_review = target_wan > cap_wan
    conclusion = (
        f"超出{cap_wan:.0f}万元预审上限，需人工复核后确定额度"
        if manual_review
        else f"预审通过，可批 {approved_wan:.1f} 万元，进入正式审批流程"
    )
    return {
        "purpose": purpose,
        "target_amount_wan": round(target_wan, 1),
        "approved_amount_wan": round(approved_wan, 1),
        "annual_rate_pct": round(annual_rate * 100, 2),
        "term_months": months,
        "monthly_payment_yuan": round(monthly_payment, 2),
        "manual_review": manual_review,
        "conclusion": conclusion,
    }


@tool(description=(
    "消费贷预审规则计算。入参为贷款用途、金额区间、期限区间的原文，"
    "返回可贷额度(万元)、年利率(%)、每月还款(元)、是否需人工复核与预审结论。"))
def consumer_loan_precheck(purpose: str, amount_range: str, term_range: str) -> Dict[str, Any]:
    return evaluate_consumer_loan_precheck(purpose, amount_range, term_range)


# ---- 2. Agent 构建 ----
def build_agent() -> ReActAgent:
    model_client = ModelClientConfig(
        client_provider=MODEL_PROVIDER, api_base=API_BASE, api_key=API_KEY,
        timeout=600, max_retries=1, verify_ssl=False)
    react_config = ReActAgentConfig(
        model_client_config=model_client,
        model_config_obj=ModelRequestConfig(model=MODEL_NAME, temperature=0.2),
        prompt_template=[{"role": "system", "content": SYSTEM_PROMPT}],
        max_iterations=5,
    )
    agent = ReActAgent(card=AgentCard(
        id="consumer_loan_precheck_agent", description="消费贷预审助手")).configure(react_config)
    agent.ability_manager.add_ability(consumer_loan_precheck.card, consumer_loan_precheck)
    return agent


# ---- 3. 一次预审对话: Agent 追问时由宿主收集客户回答，然后继续 ----
async def run_precheck(query: str, ask_user: Callable[[Dict[str, Any]], str]) -> str:
    """跑一次预审，返回预审意见。

    ask_user 是宿主提供的交互回调：收到 Agent 的追问（含选项）后返回客户回答。
    终端里用 input() 实现，UT 里用脚本化的回答实现。
    """
    agent = build_agent()
    await agent.register_rail(AskUserRail())
    session = create_agent_session(session_id=SESSION_ID, card=agent.card)
    await session.pre_run()
    try:
        result = await agent.invoke(
            {"query": query, "conversation_id": SESSION_ID}, session=session)
        while result.get("result_type") == "interrupt":
            request = result["state"][0].payload.value
            answers = {question["question"]: ask_user(question)
                       for question in (request.questions or [])}
            resume = InteractiveInput()
            resume.answer(request.interrupt_id, {"answers": answers})
            result = await agent.invoke(
                {"query": resume, "conversation_id": SESSION_ID}, session=session)
        return result.get("output", "")
    finally:
        await session.post_run()


# ---- 4. CLI 宿主: 把 Agent 的追问交给运行样例的人输入 ----
def ask_in_terminal(question: Dict[str, Any]) -> str:
    print(f"\n[Agent 追问] {question.get('header', '')}: {question.get('question', '')}")
    for index, option in enumerate(question.get("options") or [], 1):
        print(f"  {index}. {option.get('label', '')} —— {option.get('description', '')}")
    while True:
        answer = input("请选择/输入答案> ").strip()
        if answer:
            return answer


async def main():
    while True:
        query = input("客户问题> ").strip()
        if query:
            break
        print("请输入客户问题，例如：我想贷点钱装修，能批多少？")
    opinion = await run_precheck(query, ask_in_terminal)
    print("\n=== 预审意见 ===")
    print(opinion)


if __name__ == "__main__":
    asyncio.run(main())
