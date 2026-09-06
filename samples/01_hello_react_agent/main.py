"""样例 01 · 最小 ReAct Agent（真实 openjiuwen API）—— 信贷客户查询助手。

使用开源 openjiuwen Agent Core（v0.1.13）的真实 API:
- @tool 装饰器把普通函数变成 LocalFunction 工具
- AgentCard + ReActAgentConfig 构建并配置 ReActAgent
- Runner.resource_mgr.add_tool 注册工具, ability_manager.add 挂载到 Agent
- Runner.run_agent 执行, AgentResult.artifacts[*].parts[*].text 取答案

运行前提:
- openjiuwen==0.1.13（pip install -U openjiuwen）
- OpenAI 兼容 LLM 网关, 环境变量 API_BASE / API_KEY / MODEL_NAME / MODEL_PROVIDER
  （缺省指向本地 http://127.0.0.1:1234 的 google/gemma-4-e2b）

运行: python main.py
"""
import asyncio
import os
import sys

from openjiuwen.core.foundation.llm import ModelClientConfig, ModelRequestConfig
from openjiuwen.core.foundation.tool import tool
from openjiuwen.core.runner import Runner
from openjiuwen.core.single_agent import AgentCard, ReActAgent, ReActAgentConfig

# ---- 0. LLM 配置: 与官方 examples 相同的环境变量约定 ----
API_BASE = os.getenv("API_BASE", "http://127.0.0.1:1234/v1")
API_KEY = os.getenv("API_KEY", "lm-studio")
MODEL_NAME = os.getenv("MODEL_NAME", "google/gemma-4-e2b")
MODEL_PROVIDER = os.getenv("MODEL_PROVIDER", "openai")


# ---- 1. @tool: 普通函数 → LocalFunction 工具（银行信贷: 客户档案查询） ----
@tool(description="按客户号查询信贷客户档案：姓名、信用等级、贷款余额(万元)、是否逾期")
def query_customer(customer_id: str):
    db = {
        "C1001": {"name": "张伟", "credit_level": "AA", "loan_balance_wan": 58.0, "overdue": False},
        "C1002": {"name": "李娜", "credit_level": "B", "loan_balance_wan": 120.0, "overdue": True},
    }
    return db.get(customer_id, {"error": f"客户 {customer_id} 不存在"})


def build_agent() -> ReActAgent:
    model_config = ModelRequestConfig(model=MODEL_NAME, temperature=0.2)
    model_client = ModelClientConfig(
        client_provider=MODEL_PROVIDER, api_base=API_BASE, api_key=API_KEY,
        timeout=300,  # 本地小模型推 thinking 模型慢, 默认 60s 会超时
        verify_ssl=False)
    agent_card = AgentCard(id="credit_query_agent", description="信贷客户查询助手")
    react_config = ReActAgentConfig(
        model_client_config=model_client,
        model_config_obj=model_config,
        prompt_template=[{
            "role": "system",
            "content": "你是银行信贷客户经理助手。回答必须基于工具查询结果，先用 query_customer 工具查询客户档案，再给出结论，不要编造。",
        }],
        max_iterations=5,
    )
    agent = ReActAgent(card=agent_card).configure(react_config)

    # ---- 2. 工具注册: 框架资源管理器 + Agent 能力挂载（两步, 官方样例惯例） ----
    Runner.resource_mgr.add_tool(query_customer)
    agent.ability_manager.add(query_customer.card)
    return agent


async def main():
    agent = build_agent()
    result = await Runner.run_agent(
        agent=agent,
        inputs={"query": "帮我查一下客户 C1001 的信贷情况，能否续贷？",
                "conversation_id": "sample-01"},
    )

    # run_agent 返回 dict: {"output": 最终答案, "result_type": "answer"}
    answer = result.get("output", "")
    print("=== 最终答案 ===")
    print(answer)
    print(f"\n=== 结构 === result_type={result.get('result_type')}")

    # ---- 3. 程序化验收: 真实 LLM 输出不稳定, 断言结构性事实 ----
    assert answer and answer.strip(), "应产出非空最终答案"
    assert "C1001" in answer or "张伟" in answer or "AA" in answer, \
        f"答案应引用查询到的客户信息, 实际: {answer[:120]}"
    print("\nSUCCESS: ReActAgent 完成推理+工具调用+作答, 验收通过")


if __name__ == "__main__":
    asyncio.run(main())
