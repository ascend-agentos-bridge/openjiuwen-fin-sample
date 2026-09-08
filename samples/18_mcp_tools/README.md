# 样例 18 · MCP 工具协议 —— 信贷工具的跨进程服务化

> 使用**开源 openjiuwen Agent Core 真实 API**（`openjiuwen==0.1.16`），对齐官方 `examples/mcp/stdio`。对应特性清单 §6.5 MCP。

## 场景

信贷核心用 FastMCP 把"查客户档案 / 算授信评分"发布为 MCP stdio 服务；`Runner.resource_mgr.add_mcp_server` 自动拉起子进程；工具发现、直调，并注册进 ReActAgent 完成真实问答。

## 运行

```bash
python main.py   # 依赖 mcp SDK（自带 FastMCP）或独立 fastmcp 包
```

## 验收方式

1. MCP 服务注册成功（stdio 子进程自动拉起）；
2. `get_mcp_tool_infos` 发现 `query_customer` / `credit_score`；
3. 直调返回真实数据（张伟/AA/58 万、评分 55）；
4. Agent 经 MCP 工具回答客户档案查询（回答含张伟/AA/58）；
5. 最后一行 `SUCCESS: ...`，退出码 0。

实测输出（真实运行，qwen3.5-9b）：

```text
=== 直调 query_customer(C1001) ===
{'name': '张伟', 'credit_level': 'AA', 'loan_balance_wan': 58.0}

=== Agent 经 MCP 工具回答 ===
客户 C1001 的信贷档案查询结果如下：
| 项目 | 信息 |
|------|------|
| 客户姓名 | 张伟 |
| 信用等级 | AA |
| 贷款余额 | 58.0 万元 |
...

SUCCESS: MCP 注册 + 发现 + 直调 + Agent 经 MCP 工具问答 验收通过
```

## 代码讲解（真实 API）

| 关键点 | API | 说明 |
|---|---|---|
| 服务端 | `FastMCP` + `@mcp.tool()` + `mcp.run(transport="stdio")` | **stdout 专用于 JSON-RPC**，日志必须走 stderr |
| 注册 | `await Runner.resource_mgr.add_mcp_server(McpServerConfig(client_type="stdio", params={command,args,cwd}))` | 子进程由框架管理；返回 Result（`.is_err()` 判错） |
| 发现 | `await Runner.resource_mgr.get_mcp_tool_infos(server_name=...)` | 返回工具信息列表（name/description） |
| 直调 | `get_mcp_tool(name, server_name)` → `await tool.invoke({...})` | **可能返回 list**，需解包取 `[0]` |
| 接入 Agent | `agent.ability_manager.add(tool.card)` | MCP 工具与本地工具同一套用法 |

## 开发者注意（真实踩坑）

- **独立 `fastmcp` 包可能损坏**（namespace 安装问题，`ImportError: unknown location`）——此时用 mcp SDK 自带的 `from mcp.server.fastmcp import FastMCP`，API 兼容；
- `get_mcp_tool` 返回 list 或单例不定，必须 isinstance 判断解包（官方示例也这么写）；
- stdio 模式下服务端**任何 stdout 输出都会破坏协议**——日志用 stderr（本样例的 logging handler 示范）；
- MCP 工具失败以 JSON-RPC error 传播（`tools/call` 返回 error 字段），客户端要处理。
