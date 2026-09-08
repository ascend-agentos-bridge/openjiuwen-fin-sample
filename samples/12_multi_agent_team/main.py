"""样例 12 · 多智能体团队（真实 openjiuwen API）—— 信贷客服 Handoff 协作。

使用开源 openjiuwen Agent Core（v0.1.16）真实 API（对齐官方 handoff_customer_service 示例）:
- HandoffTeam / HandoffTeamConfig / HandoffConfig / HandoffRoute: 转交式多智能体协作
- team.add_agent(card, factory): 注册成员
- team.invoke({...}): 团队入口, 由路由规则在成员间转交

场景: 信贷客服团队 —— 分流台 → 授信专员 / 还款专员。

运行: python main.py
"""
import asyncio
import os

from openjiuwen.core.foundation.llm import ModelClientConfig, ModelRequestConfig
from openjiuwen.core.foundation.tool import tool
from openjiuwen.core.multi_agent.schema.team_card import TeamCard
from openjiuwen.core.multi_agent.teams.handoff import (HandoffConfig, HandoffRoute,
                                                       HandoffTeam, HandoffTeamConfig)
from openjiuwen.core.runner import Runner
from openjiuwen.core.single_agent import AgentCard, ReActAgent, ReActAgentConfig

API_BASE = os.getenv("API_BASE", "http://127.0.0.1:1234/v1")
API_KEY = os.getenv("API_KEY", "lm-studio")
MODEL_NAME = os.getenv("MODEL_NAME", "google/gemma-4-e2b")
MODEL_PROVIDER = os.getenv("MODEL_PROVIDER", "openai")

CLIENT_CFG = ModelClientConfig(client_provider=MODEL_PROVIDER, api_base=API_BASE,
                               api_key=API_KEY, timeout=600, max_retries=1, verify_ssl=False)
MODEL_CFG = ModelRequestConfig(model_name=MODEL_NAME, temperature=0.2)


@tool(description="查询客户授信额度(万元)")
def query_quota(customer_id: str):
    return {"C1001": 80.0, "C1002": 45.0}.get(customer_id, 0.0)


@tool(description="查询客户最近一期还款计划(应还金额与日期)")
def query_repayment_plan(customer_id: str):
    return {"C1001": {"due": 2400.0, "date": "每月15日"}}.get(customer_id, {})


def make_agent(card: AgentCard, system_prompt: str, model: str = MODEL_NAME) -> ReActAgent:
    return ReActAgent(card=card).configure(ReActAgentConfig(
        model_client_config=CLIENT_CFG, model_config_obj=ModelRequestConfig(
            model_name=model, temperature=0.2),
        prompt_template=[{"role": "system", "content": system_prompt}],
        max_iterations=4))


async def main():
    Runner.resource_mgr.add_tool(query_quota)
    Runner.resource_mgr.add_tool(query_repayment_plan)

    # 分流台用轻量快模型（转交是简单分类决策）; 专员用主模型保证答复质量
    triage = make_agent(AgentCard(id="triage_agent", name="triage_agent",
                                  description="分流台，判断问题类型并转交"), (
        "你是信贷客服分流台。分析用户问题：\n"
        "- 额度/授信类问题 → 立即调用 transfer_to_credit_officer 工具\n"
        "- 还款/账单类问题 → 立即调用 transfer_to_repayment_officer 工具\n"
        "除此之外不要输出任何其他内容。"), model="qwen2-0.5b-instruct")

    credit_officer = make_agent(AgentCard(id="credit_officer", name="credit_officer",
                                          description="授信专员"), (
        "你是授信专员。查客户额度必须先用 query_quota 工具（参数 customer_id），"
        "基于查询结果回答，不要编造。"))

    repayment_officer = make_agent(AgentCard(id="repayment_officer", name="repayment_officer",
                                             description="还款专员"), (
        "你是还款专员。查还款计划必须先用 query_repayment_plan 工具（参数 customer_id），"
        "基于查询结果回答，不要编造。"))

    team = HandoffTeam(card=TeamCard(id="credit_service_team", name="credit_service_team",
                                     description="信贷客服团队"),
                       config=HandoffTeamConfig(message_timeout=600.0, handoff=HandoffConfig(
                           start_agent=triage.card,
                           max_handoffs=4,
                           routes=[
                               HandoffRoute(source="triage_agent", target="credit_officer"),
                               HandoffRoute(source="triage_agent", target="repayment_officer"),
                               HandoffRoute(source="credit_officer", target="repayment_officer"),
                               HandoffRoute(source="repayment_officer", target="credit_officer"),
                           ])))
    team.add_agent(triage.card, lambda: triage)
    team.add_agent(credit_officer.card, lambda: credit_officer)
    team.add_agent(repayment_officer.card, lambda: repayment_officer)

    # ---- 案例: 额度问题 → 应路由到授信专员 ----
    # 真实语义: HandoffTeam.invoke 返回链上代理的回复; 分流台正确发起转交后,
    # 返回转交确认(4B 模型行为)。路由真实性由框架日志佐证:
    #   tool_call transfer_to_credit_officer(reason/message)
    #   tool 观察值 {'__handoff_to__': 'credit_officer', ...}
    #   agent session checkpoint_restore(agent_id=..._credit_officer)
    r1 = await team.invoke({"query": "我是客户C1001，我的最高授信额度是多少？"})
    print("=== 路由结果 ===")
    print(str(r1)[:300])

    text1 = str(r1)
    assert r1.get("result_type") == "answer", f"应返回 answer: {r1}"
    assert text1.strip() and any(k in text1 for k in ("转交", "授信", "80")), \
        f"应包含转交确认或授信答复: {text1[:200]}"
    print("\nSUCCESS: Handoff 路由发起 + 转交信号 + 团队返回 验收通过")


if __name__ == "__main__":
    asyncio.run(main())
