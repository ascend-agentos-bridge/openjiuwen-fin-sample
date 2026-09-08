"""样例 07 · Session 会话续聊（真实 openjiuwen API）—— 两轮贷款咨询。

使用开源 openjiuwen Agent Core（v0.1.16）真实 API:
- 同一 conversation_id 多次 Runner.run_agent: 框架 checkpointer 自动恢复上下文,
  第二轮记得第一轮的对话内容（框架日志可见 checkpoint_restore）
- 会话记忆由框架管理, 业务代码无需手工拼历史

运行: python main.py
"""
import asyncio
import os

from openjiuwen.core.foundation.llm import ModelClientConfig, ModelRequestConfig
from openjiuwen.core.foundation.tool import tool
from openjiuwen.core.runner import Runner
from openjiuwen.core.single_agent import AgentCard, ReActAgent, ReActAgentConfig

API_BASE = os.getenv("API_BASE", "http://127.0.0.1:1234/v1")
API_KEY = os.getenv("API_KEY", "lm-studio")
MODEL_NAME = os.getenv("MODEL_NAME", "google/gemma-4-e2b")
MODEL_PROVIDER = os.getenv("MODEL_PROVIDER", "openai")
CONV_ID = "sample-07-conv"


@tool(description="查询客户最高可贷额度(万元)")
def query_quota(customer_id: str):
    quotas = {"C1001": 80.0, "C1002": 45.0}
    return quotas.get(customer_id, 0.0)


async def main():
    agent = ReActAgent(card=AgentCard(id="credit_advisor", description="个贷咨询助手")).configure(
        ReActAgentConfig(
            model_client_config=ModelClientConfig(
                client_provider=MODEL_PROVIDER, api_base=API_BASE, api_key=API_KEY,
                timeout=600, max_retries=1, verify_ssl=False),
            model_config_obj=ModelRequestConfig(model_name=MODEL_NAME, temperature=0.2),
            prompt_template=[{"role": "system",
                              "content": "你是九州银行个贷咨询助手。查额度先调用 query_quota 工具。"}],
            max_iterations=4))
    Runner.resource_mgr.add_tool(query_quota)
    agent.ability_manager.add(query_quota.card)

    # ---- 第一轮: 问额度（会触发工具调用） ----
    r1 = await Runner.run_agent(agent=agent, inputs={
        "query": "我是客户C1001，我的最高可贷额度是多少？", "conversation_id": CONV_ID})
    print("=== 第一轮 ===")
    print(r1.get("output"))
    assert "80" in r1.get("output", ""), f"第一轮应报出 80 万额度: {r1}"

    # ---- 第二轮: 同 conversation_id 续聊, 不重复给客户号 ----
    r2 = await Runner.run_agent(agent=agent, inputs={
        "query": "那我刚才说的客户号是多少？额度够不够贷 50 万？", "conversation_id": CONV_ID})
    print("\n=== 第二轮（同一会话, 框架自动恢复上下文） ===")
    print(r2.get("output"))

    # ---- 验收断言: 记住客户号 = 会话状态真实延续 ----
    assert "C1001" in r2.get("output", ""), \
        f"第二轮应记住第一轮的客户号 C1001: {r2.get('output', '')[:120]}"
    assert r1.get("result_type") == "answer" and r2.get("result_type") == "answer"
    print("\nSUCCESS: conversation_id 驱动的会话续聊 + 跨轮记忆 验收通过")


if __name__ == "__main__":
    asyncio.run(main())
