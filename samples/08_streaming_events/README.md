# 样例 08 · 流式输出 —— token 级实时渲染

> 使用**开源 openjiuwen Agent Core 真实 API**（`openjiuwen==0.1.16`）。对应特性清单 §12.3 Stream。

## 场景

信贷问答前端需要打字机效果 + 思考过程折叠展示。`run_agent_streaming` 提供 token 级事件流。

## 运行

```bash
python main.py
```

## 验收方式

1. 事件流中出现三类事件：`llm_reasoning`（思考增量，thinking 模型）、`llm_output`（正文增量）、`llm_usage`（用量）；
2. 正文增量实时拼接出完整答案（含"提前还款"相关内容）；
3. 统计行显示增量条数（thinking 模型 reasoning 增量远多于正文增量）；
4. 最后一行 `SUCCESS: ...`，退出码 0。

实测输出（真实运行，qwen3-4b-thinking-2507）：

```text
=== 事件流（前几条原始事件） ===
  [llm_reasoning] '首先'
  [llm_reasoning] '，'
  [llm_reasoning] '用户'
  ...

=== 实时拼接的最终答案 ===
提前还款前，务必检查贷款合同中的提前还款手续费和通知条款。建议直接联系银行确认细节，避免产生额外费用。

=== 统计 === reasoning增量=434条, 正文增量=28条, usage事件=1次

SUCCESS: token 级流式(reasoning/output/usage 三类事件) 验收通过
```

## 代码讲解（真实 API）

| 关键点 | API | 说明 |
|---|---|---|
| 流式执行 | `async for ev in Runner.run_agent_streaming(agent, inputs, stream_modes=None)` | 异步迭代器，边生成边产出 |
| 事件对象 | `OutputSchema(type, payload={'content': 增量文本, 'result_type': ...})` | 三类 type：`llm_reasoning` / `llm_output` / `llm_usage` |
| 前端分流 | reasoning 折叠展示，output 实时渲染 | 思考内容不应直接展示给终端用户 |

## 开发者注意（真实踩坑）

- **thinking 模型的 reasoning 增量非常密集**（本例 434 条 vs 正文 28 条）——前端要做节流/批量渲染，别每条刷一次 DOM；
- `llm_output` 的增量是**不回车的纯文本片段**，拼接后才是完整答案；
- 事件里没有工具调用专用的流事件类型——工具执行进度要在业务侧用 tracer/回调（`agent.register_callback`）补；
- 非 thinking 模型没有 `llm_reasoning` 事件，前端要兼容两者。
