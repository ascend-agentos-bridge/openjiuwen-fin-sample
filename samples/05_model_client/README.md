# 样例 05 · Model 直调与请求参数 —— 信贷问答的参数工程

> 使用**开源 openjiuwen Agent Core 真实 API**（`openjiuwen==0.1.16`）。对应特性清单 §4.1 Model Client。

## 场景

不建 Agent 的最轻量用法：`Model` 直调。验证请求参数真实生效，并复现 thinking 模型的 `max_tokens` 坑。

## 运行

```bash
python main.py   # 需要 LLM 网关（默认本地 127.0.0.1:1234）
```

## 验收方式

1. 默认参数与 `temperature=0.2` 各答一次，答案非空；
2. **`max_tokens=30`（thinking 模型）**：`finish_reason=stop` 但 `content_len=0`、`reasoning_len=47`——推理 token 计入 max_tokens，额度全花在思考上；
3. `max_tokens=4096` 的多轮对话正常，`usage_metadata` 报出 `input_tokens=56, output_tokens=1796`；
4. 最后一行 `SUCCESS: ...`，退出码 0。

实测输出（真实运行，qwen3-4b-thinking-2507）：

```text
=== 默认参数(temperature=0.95) ===
等额本息还款是指在贷款期限内，每月偿还固定金额的贷款本息，...

=== 低温度(temperature=0.2) ===
等额本息还款是指在贷款期限内，每月偿还固定金额的贷款本息，...

=== max_tokens=30（thinking 模型） ===
finish_reason=stop, content_len=0, reasoning_len=47
=> 真实行为: 推理 token 计入 max_tokens, 30 个 token 全花在思考上, content 为空

=== 多轮对话（max_tokens=4096） ===
贷款10万元是否选择等额本息还款，需结合您的收入、负债及还款能力综合判断。
finish_reason=stop
usage: input_tokens=56, output_tokens=1796, total_tokens=1852

SUCCESS: 参数生效(max_tokens/temperature) + 多轮对话 + thinking 坑验证 验收通过
```

## 代码讲解（真实 API）

| 关键点 | API | 说明 |
|---|---|---|
| 直调 | `Model(ModelClientConfig(...), ModelRequestConfig(...)).invoke(messages)` | 返回 `AssistantMessage` |
| 请求参数 | `ModelRequestConfig`: `model_name/temperature/top_p/max_tokens/stop` | **默认 temperature=0.95, top_p=0.1**——写风控类应用务必显式降温 |
| 客户端参数 | `ModelClientConfig`: `client_provider/api_base/api_key/timeout/max_retries/verify_ssl` | 本地慢推理建议 `timeout=600, max_retries=1` |
| 用量 | `resp.usage_metadata` | **pydantic 对象非 dict**：`input_tokens/output_tokens/total_tokens/input_cost/...` 属性访问 |

## 开发者注意（真实踩坑）

- **thinking 模型的 max_tokens 包含推理 token**：设太小会得到 `content=""` 且 `finish_reason=stop`（不报错！）。判断"是否真答了"要看 `content_len`，不能只看 finish_reason；
- `usage_metadata` 是对象不是字典，`.get()` 会抛 `AttributeError`；
- 框架默认 `temperature=0.95`——对抽取/判定类任务是隐患，显式传低温；
- `top_p` 默认 0.1 也偏激进，正式项目建议显式配置。
