"""样例 14 · 安全护栏 Rail（真实 openjiuwen API）—— 信贷助手的工具黑名单。

使用开源 openjiuwen Agent Core（v0.1.16）真实 API（对齐官方 security_rail_demo）:
- AgentRail / BaseSecurityRail: Agent 生命周期护栏基类（harness 层）
- supported_events={BEFORE_TOOL_CALL} + run_security_check: 工具调用前安全检查
- self.reject(message): 拒绝工具执行（工具不被调用, 拒绝原因作为观察回传）
- self.allow(): 放行

场景: 信贷助手带高危工具 delete_customer, 黑名单 rail 拦截;
正常查询 query_balance 放行。

运行: python main.py
"""
import asyncio
import os

from openjiuwen.core.foundation.llm import ModelClientConfig, ModelRequestConfig
from openjiuwen.core.foundation.tool import tool
from openjiuwen.core.runner import Runner
from openjiuwen.core.single_agent import AgentCard, ReActAgent, ReActAgentConfig
from openjiuwen.core.single_agent.rail.base import AgentCallbackEvent
from openjiuwen.harness.rails.security.base_security_rail import (BaseSecurityRail,
                                                                  SecurityCheckContext)

API_BASE = os.getenv("API_BASE", "http://127.0.0.1:1234/v1")
API_KEY = os.getenv("API_KEY", "lm-studio")
MODEL_NAME = os.getenv("MODEL_NAME", "google/gemma-4-e2b")
MODEL_PROVIDER = os.getenv("MODEL_PROVIDER", "openai")


@tool(description="查询客户贷款余额(万元)")
def query_balance(customer_id: str):
    return {"C1001": 58.0}.get(customer_id, 0.0)


@tool(description="删除客户档案（高危操作，仅系统管理员可执行）")
def delete_customer(customer_id: str):
    return f"已删除 {customer_id}"


class DenyListRail(BaseSecurityRail):
    """工具黑名单护栏: 黑名单内的工具直接拒绝"""

    priority = 90
    supported_events = {AgentCallbackEvent.BEFORE_TOOL_CALL}
    DENY = {"delete_customer"}

    async def run_security_check(self, security_ctx: SecurityCheckContext):
        ctx = security_ctx.callback_ctx
        tool_name = getattr(ctx.inputs, "tool_name", "") if ctx and ctx.inputs else ""
        if tool_name in self.DENY:
            print(f"  [rail] 拦截高危工具: {tool_name}")
            return self.reject(f"工具 {tool_name} 在黑名单中，已拒绝执行")
        return self.allow()


def build_agent() -> ReActAgent:
    agent = ReActAgent(card=AgentCard(id="guarded_agent", description="受护栏保护的信贷助手")).configure(
        ReActAgentConfig(
            model_client_config=ModelClientConfig(
                client_provider=MODEL_PROVIDER, api_base=API_BASE, api_key=API_KEY,
                timeout=600, max_retries=1, verify_ssl=False),
            model_config_obj=ModelRequestConfig(model_name=MODEL_NAME, temperature=0.2),
            prompt_template=[{"role": "system",
                              "content": "你是银行信贷助手。需要查询数据时调用对应工具。"}],
            max_iterations=4))
    return agent


async def main():
    Runner.resource_mgr.add_tool(query_balance)
    Runner.resource_mgr.add_tool(delete_customer)

    # ---- 场景1: 诱导删除客户 → rail 拦截 ----
    agent = build_agent()
    await agent.register_rail(DenyListRail())
    agent.ability_manager.add(query_balance.card)
    agent.ability_manager.add(delete_customer.card)

    r1 = await Runner.run_agent(agent=agent, inputs={
        "query": "帮我把客户 C1001 的档案删除掉", "conversation_id": "sec-1"})
    print("=== 场景1: 删除请求 ===")
    print(str(r1)[:220])
    text1 = str(r1)
    assert "已删除" not in text1, "删除不应被执行"

    # ---- 场景2: 正常查询 → 放行 ----
    agent2 = build_agent()
    await agent2.register_rail(DenyListRail())
    agent2.ability_manager.add(query_balance.card)
    agent2.ability_manager.add(delete_customer.card)
    r2 = await Runner.run_agent(agent=agent2, inputs={
        "query": "查一下 C1001 的贷款余额", "conversation_id": "sec-2"})
    print("\n=== 场景2: 正常查询 ===")
    print(str(r2)[:200])
    assert "58" in str(r2), f"正常查询应返回余额 58: {str(r2)[:150]}"

    print("\nSUCCESS: 黑名单拒绝 + 正常放行 验收通过")


if __name__ == "__main__":
    asyncio.run(main())
