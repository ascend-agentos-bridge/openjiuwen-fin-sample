# 样例 01 · 最小 ReAct Agent —— 信贷客户查询助手

> 使用**开源 openjiuwen Agent Core 真实 API**（`openjiuwen==0.1.16`），无任何本地封装。对应特性清单 §2.1 ReAct Agent。

## 场景

客户经理问：*"帮我查一下客户 C1001 的信贷情况，能否续贷？"*
ReActAgent 调用 `query_customer` 工具拿到档案（张伟 / AA / 58 万 / 无逾期），再基于查询结果给出续贷结论。

## 运行

```bash
# 前提: pip install -U openjiuwen  (0.1.16)
# LLM: OpenAI 兼容网关。默认指向本地 LM Studio (127.0.0.1:1234, qwen3-4b-thinking)
python main.py
```

环境变量（与官方 examples 约定一致）：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `API_BASE` | `http://127.0.0.1:1234/v1` | OpenAI 兼容网关地址 |
| `API_KEY` | `lm-studio` | 网关密钥 |
| `MODEL_NAME` | `qwen/qwen3-4b-thinking-2507` | 模型名 |
| `MODEL_PROVIDER` | `openai` | 客户端类型 |

## 验收方式

运行成功必须同时满足（代码内 assert，退出码 0）：

1. 框架日志可见完整 ReAct 循环：`ReAct iteration 1/N` 发起工具调用 → `Executing tool: query_customer with args {"customer_id":"C1001"}` → `ReAct iteration 2/N` 产出最终答案（`content_len>0, tool_call_count=0`）；
2. 打印 `=== 最终答案 ===`，答案非空且引用了查询到的客户信息（`张伟`/`AA`/`C1001` 之一）；
3. `result_type=answer`；
4. 最后一行 `SUCCESS: ReActAgent 完成推理+工具调用+作答, 验收通过`。

实测输出（真实运行，模型为本地 qwen3-4b-thinking-2507）：

```text
=== 最终答案 ===
客户张伟的信贷档案查询结果：信用等级AA，贷款余额58万元，当前无逾期记录。根据银行续贷政策，
信用等级AA且无逾期的客户可正常办理续贷业务。建议客户提交续贷申请材料，系统将自动审核通过。
=== 结构 === result_type=answer
SUCCESS: ReActAgent 完成推理+工具调用+作答, 验收通过
```

关键框架日志（证明工具真实被调用）：

```text
INFO | ReAct iteration 1/5
INFO | [LLM] <<< response: content_len=0, tool_call_count=1, tokens={input=228, output=490}
INFO | Executing tool: query_customer with args: {"customer_id":"C1001"}
INFO | ReAct iteration 2/5
INFO | [LLM] <<< response: content_len=93, tool_call_count=0, tokens={input=764, output=226}
```

## 代码讲解（真实 API）

| 关键点 | API | 说明 |
|---|---|---|
| 工具定义 | `@tool(description=...)` | 返回 `LocalFunction`，schema 自动从函数签名提取 |
| 工具注册（两步） | `Runner.resource_mgr.add_tool(f)` + `agent.ability_manager.add(f.card)` | 前者进全局资源管理器，后者挂载到该 Agent 的能力集 |
| Agent 构建 | `ReActAgent(card=AgentCard(...)).configure(ReActAgentConfig(...))` | config 里给模型配置、prompt_template、max_iterations |
| 执行 | `await Runner.run_agent(agent=..., inputs={"query":..., "conversation_id":...})` | 返回 `dict`：`{"output": 答案, "result_type": "answer"}` |

## 开发者注意（真实踩坑）

- **`run_agent` 返回 dict 不是对象**：答案取 `result["output"]`，别按 `AgentResult` dataclass 去点 `.artifacts`；
- **本地小模型必须调大 `timeout`**：`ModelClientConfig` 默认 `timeout=60` 秒，thinking 模型在 CPU 上生成很容易超时报 `[181001] model call failed: Request timed out`，样例设 300；
- **框架日志很吵**（每次 LLM 调用都打完整 payload 和 reasoning）：这是可观测性特性不是 bug；排障靠它，压测时可按框架 LogConfig 调级；
- **4B 级小模型工具调用可用但不稳定**：`temperature=0.2` + 明确的工具 description 能显著提高调用成功率；
- 换大模型/云端网关只改四个环境变量，代码零改动。
