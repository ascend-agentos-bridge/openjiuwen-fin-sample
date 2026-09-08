# 样例 16 · A2A 跨机构互操作 —— 本行 Agent 咨询征信中心 Agent

> 使用**开源 openjiuwen Agent Core 真实 API**（`openjiuwen==0.1.16`，`openjiuwen.extensions.a2a`），对齐官方 `examples/a2a`。对应特性清单 §19/§20。

## 场景

征信中心作为独立机构把自己的 Agent 发布为 A2A 服务（JSON-RPC 端点）；本行信贷助手作为 A2A 客户端跨进程调用，拿回征信摘要（task 完成态 + artifacts）。

## 运行

```bash
python main.py   # 依赖 a2a SDK（openjiuwen[a2a] extras）
```

## 验收方式

1. 征信中心 A2A 服务上线（`http://127.0.0.1:8791/a2a/jsonrpc/`）；
2. `A2AClient.invoke` 返回 `AgentResult(status=COMPLETED)`，artifacts 里是真实征信摘要（含 `信用评分712`）；
3. 最后一行 `SUCCESS: ...`，退出码 0。

实测输出（真实运行）：

```text
=== 征信中心 A2A 服务已上线: http://127.0.0.1:8791/a2a/jsonrpc/ ===

=== A2A 调用结果 ===
task_id='0df857f9-...' sessionId='a2a-1' status=<TaskStatus.COMPLETED: 'completed'>
artifacts=[Artifact(..., parts=[Part(text='征信摘要 C1001: 无当前逾期，近1个月硬查询2次，信用评分712，建议正常受理。')])]

SUCCESS: A2A 服务发布 + 跨进程调用 + 征信摘要返回 验收通过
```

## 代码讲解（真实 API）

| 关键点 | API | 说明 |
|---|---|---|
| 服务端 | `A2AServer(agent_card=AgentCard(..., interface_url=...), adapter_id, invoke_handler, rpc_url)` | `invoke_handler(payload) -> AgentResult`；`server.start(host, port)` |
| 客户端 | `A2AClient(card=<a2a SDK AgentCard>)` + `await client.invoke({"query": ...})` | 返回 jiuwen `AgentResult`（task_id/status/artifacts） |
| SDK card | `a2a.types.AgentCard(supported_interfaces=[AgentInterface(url=..., protocol_binding="JSONRPC")])` | **必须用 a2a SDK 的 card 类型**——jiwen 的 AgentCard 没有 `supported_interfaces` 字段，传错会 `RuntimeError` |

## 开发者注意（真实踩坑）

- A2A 依赖 `a2a` SDK（官方 open-a2a-sdk），安装 `pip install "openjiuwen[a2a]"`；
- 客户端 card 的接口声明里 `protocol_binding="JSONRPC"` 要与服务端 `rpc_url` 路径一致；
- 更完整的委托模式（本地 Agent 委托远端，`Runner.run_agent` 直通远端）见官方 `client_local_agent_delegates_remote_a2a.py`；
- 跨机构调用务必在网关层加认证与审计——A2A 协议本身不含安全。
