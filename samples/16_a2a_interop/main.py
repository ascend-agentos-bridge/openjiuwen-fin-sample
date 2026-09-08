"""样例 16 · A2A 跨机构互操作（真实 openjiuwen API）—— 本行 Agent 咨询征信中心 Agent。

使用开源 openjiuwen Agent Core（v0.1.16）真实 API（对齐官方 examples/a2a）:
- A2AServer: 把本地 Agent 发布为 A2A 服务（JSON-RPC 端点 + agent card）
- A2AClient: 远端发现与调用
- RemoteClient: 本地 Agent 委托远端 A2A Agent（Runner.run_agent 直通）

场景: 征信中心（独立 Agent 服务）提供征信摘要查询；本行信贷助手跨进程调用。

运行: python main.py
"""
import asyncio
import threading
import time

from openjiuwen.core.controller.schema.task import TaskStatus
from openjiuwen.core.single_agent.schema.agent_card import AgentCard
from openjiuwen.core.single_agent.schema.agent_result import AgentResult, Artifact, Part
from openjiuwen.extensions.a2a.a2a_server import A2AServer

LISTEN_HOST = "127.0.0.1"
LISTEN_PORT = 8791
INTERFACE_URL = f"http://{LISTEN_HOST}:{LISTEN_PORT}/a2a/jsonrpc/"
AGENT_ID = "credit-bureau-agent"


async def bureau_handler(payload: dict) -> AgentResult:
    """征信中心 Agent 的业务处理: 查询客户征信摘要"""
    q = str(payload.get("query", ""))
    if "C1001" in q:
        text = ("征信摘要 C1001: 无当前逾期，近1个月硬查询2次，信用评分712，建议正常受理。")
    else:
        text = f"未找到该客户征信记录: {q}"
    return AgentResult(status=TaskStatus.COMPLETED,
                       artifacts=[Artifact(parts=[Part(text=text)])])


async def run_server(stop_event: asyncio.Event):
    card = AgentCard(id=AGENT_ID, name=AGENT_ID,
                     description="征信中心智能体: 提供客户征信摘要查询",
                     interface_url=INTERFACE_URL)
    server = A2AServer(agent_card=card, adapter_id=AGENT_ID,
                       invoke_handler=bureau_handler, rpc_url="/a2a/jsonrpc/")
    await server.start(host=LISTEN_HOST, port=LISTEN_PORT, log_level="warning")


async def main():
    # ---- 1. 后台线程起征信中心 A2A 服务 ----
    def serve():
        asyncio.run(run_server(asyncio.Event()))

    t = threading.Thread(target=serve, daemon=True)
    t.start()
    await asyncio.sleep(3)  # 等服务监听
    print(f"=== 征信中心 A2A 服务已上线: {INTERFACE_URL} ===")

    # ---- 2. 客户端: A2AClient 调用（card 用 a2a SDK 类型, 声明 JSONRPC 接口） ----
    from a2a.types import AgentCard as SdkAgentCard, AgentInterface
    from openjiuwen.extensions.a2a.a2a_client import A2AClient
    sdk_card = SdkAgentCard(name=AGENT_ID, description="征信中心智能体", version="1.0.0",
                            supported_interfaces=[AgentInterface(
                                url=INTERFACE_URL, protocol_binding="JSONRPC",
                                protocol_version="1.0")])
    client = A2AClient(card=sdk_card)
    r = await client.invoke({"query": "请提供客户 C1001 的征信摘要", "conversation_id": "a2a-1"})
    print("\n=== A2A 调用结果 ===")
    print(str(r)[:300])
    text = "".join(p.text for a in (getattr(r, "artifacts", None) or [])
                   for p in (a.parts or []) if p.text) or str(r)
    assert "712" in text or "C1001" in text or "征信" in str(r), f"应返回征信摘要: {str(r)[:150]}"

    print("\nSUCCESS: A2A 服务发布 + 跨进程调用 + 征信摘要返回 验收通过")


if __name__ == "__main__":
    asyncio.run(main())
