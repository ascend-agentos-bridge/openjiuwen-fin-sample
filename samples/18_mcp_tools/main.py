"""样例 18 · MCP 工具协议（真实 openjiuwen API）—— 信贷工具的跨进程服务化。

使用开源 openjiuwen Agent Core（v0.1.16）真实 API（对齐官方 examples/mcp/stdio）:
- Runner.resource_mgr.add_mcp_server(McpServerConfig(client_type="stdio")):
  自动拉起 FastMCP 子进程并注册
- get_mcp_tool_infos / get_mcp_tool: 工具发现与直调
- MCP 工具注册进 ReActAgent.ability_manager: Agent 真实问答走 MCP 工具

场景: 信贷核心把"查档案/算评分"通过 MCP 对外提供, Agent 跨进程使用。

运行: python main.py
"""
import asyncio
import os
import sys
from pathlib import Path

from openjiuwen.core.foundation.llm import ModelClientConfig, ModelRequestConfig
from openjiuwen.core.foundation.tool.mcp.base import McpServerConfig
from openjiuwen.core.runner import Runner
from openjiuwen.core.single_agent import AgentCard, ReActAgent, ReActAgentConfig

API_BASE = os.getenv("API_BASE", "http://127.0.0.1:1234/v1")
API_KEY = os.getenv("API_KEY", "lm-studio")
MODEL_NAME = os.getenv("MODEL_NAME", "google/gemma-4-e2b")
MODEL_PROVIDER = os.getenv("MODEL_PROVIDER", "openai")

HERE = Path(__file__).resolve().parent
SERVER_SCRIPT = HERE / "mcp_credit_server.py"
SERVER_NAME = "credit-core-stdio-server"
SERVER_ID = "stdio-credit-server-01"


async def main():
    # ---- 1. 注册 MCP 服务（自动拉起子进程） ----
    config = McpServerConfig(
        server_id=SERVER_ID, server_name=SERVER_NAME, server_path="",
        client_type="stdio",
        params={"command": sys.executable, "args": [str(SERVER_SCRIPT)],
                "cwd": str(HERE), "encoding_error_handler": "strict"})
    result = await Runner.resource_mgr.add_mcp_server(config, tag=["mcp", "credit"])
    assert not result.is_err(), f"注册 MCP 服务失败: {result.msg()}"
    print(f"=== MCP 服务已注册: {SERVER_NAME}（stdio 子进程） ===")

    # ---- 2. 工具发现 ----
    infos = await Runner.resource_mgr.get_mcp_tool_infos(server_name=SERVER_NAME)
    names = sorted(t.name for t in infos)
    print("\n=== 工具发现（get_mcp_tool_infos） ===")
    for t in infos:
        print(f"  {t.name}: {t.description[:36]}")
    assert {"query_customer", "credit_score"} <= set(names)

    # ---- 3. 工具直调 ----
    q_tool = await Runner.resource_mgr.get_mcp_tool(name="query_customer", server_name=SERVER_NAME)
    if isinstance(q_tool, list):  # 官方注释: may return a list or a single instance
        q_tool = q_tool[0]
    r1 = await q_tool.invoke({"customer_id": "C1001"})
    print(f"\n=== 直调 query_customer(C1001) ===\n{str(r1)[:160]}")
    s_tool = await Runner.resource_mgr.get_mcp_tool(name="credit_score", server_name=SERVER_NAME)
    if isinstance(s_tool, list):
        s_tool = s_tool[0]
    r2 = await s_tool.invoke({"customer_id": "C1002"})
    print(f"=== 直调 credit_score(C1002) ===\n{r2}")

    # ---- 4. MCP 工具接入 Agent: 真实问答 ----
    agent = ReActAgent(card=AgentCard(id="mcp_credit_agent", description="MCP 信贷助手")).configure(
        ReActAgentConfig(
            model_client_config=ModelClientConfig(
                client_provider=MODEL_PROVIDER, api_base=API_BASE, api_key=API_KEY,
                timeout=600, max_retries=1, verify_ssl=False),
            model_config_obj=ModelRequestConfig(model_name=MODEL_NAME, temperature=0.2),
            prompt_template=[{"role": "system",
                              "content": "你是信贷助手。查客户档案用 query_customer 工具，回答基于查询结果。"}],
            max_iterations=4))
    agent.ability_manager.add(q_tool.card)
    r = await Runner.run_agent(agent=agent, inputs={
        "query": "查一下客户 C1001 的信贷档案", "conversation_id": "sample-18"})
    print("\n=== Agent 经 MCP 工具回答 ===")
    print(str(r.get("output", ""))[:200])

    # ---- 5. 验收断言 ----
    assert "张伟" in str(r1) and "AA" in str(r1)
    assert "55" in str(r2)
    assert "张伟" in str(r.get("output", "")) or "58" in str(r.get("output", "")), \
        f"Agent 应基于 MCP 工具结果回答: {str(r)[:150]}"
    print("\nSUCCESS: MCP 注册 + 发现 + 直调 + Agent 经 MCP 工具问答 验收通过")


if __name__ == "__main__":
    asyncio.run(main())
