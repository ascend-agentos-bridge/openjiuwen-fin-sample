"""MCP stdio 服务端: 信贷工具服务（真实 FastMCP，对齐官方 examples/mcp/stdio/server.py）。

由 main.py 通过 Runner.resource_mgr.add_mcp_server() 以子进程自动拉起。
stdout 专用于 JSON-RPC，日志走 stderr——不要直接运行本文件。
"""
import logging
import sys
from contextlib import asynccontextmanager

# 注: 独立 fastmcp 包若安装损坏, 可用 mcp SDK 自带的 FastMCP（接口兼容）
from mcp.server.fastmcp import FastMCP

_log = logging.getLogger(__name__)
if not _log.handlers:
    _log.addHandler(logging.StreamHandler(sys.stderr))
    _log.setLevel(logging.INFO)


@asynccontextmanager
async def lifespan(app):
    _log.info("credit MCP server started")
    yield
    _log.info("credit MCP server stopped")


mcp = FastMCP(name="credit-core-stdio-server", lifespan=lifespan)


@mcp.tool()
def query_customer(customer_id: str) -> dict:
    """查询客户信贷档案: 信用等级与贷款余额(万元)"""
    db = {"C1001": {"name": "张伟", "credit_level": "AA", "loan_balance_wan": 58.0},
          "C1002": {"name": "李娜", "credit_level": "B", "loan_balance_wan": 120.0}}
    return db.get(customer_id, {"error": f"客户 {customer_id} 不存在"})


@mcp.tool()
def credit_score(customer_id: str) -> int:
    """按客户号计算授信评分(0-100)"""
    return {"C1001": 82, "C1002": 55}.get(customer_id, 50)


if __name__ == "__main__":
    mcp.run(transport="stdio")
