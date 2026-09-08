# 样例 10 · Context Engine 上下文窗口 —— 长对话的窗口管理

> 使用**开源 openjiuwen Agent Core 真实 API**（`openjiuwen==0.1.16`）。对应特性清单 §10 Context Engineering。

## 场景

长对话窗口管理：20 条历史进入 `max_context_message_num=8` 的窗口，自动丢弃最旧、保留最近 4 轮；引擎配置直接接入 Agent 多轮对话。

## 运行

```bash
python main.py
```

## 验收方式

1. 窗口裁剪：`历史 20 条 -> 窗口 8 条`，窗口首条为**第 6 轮**（最旧的 1-5 轮被丢弃），末条为第 9 轮；
2. Agent 三轮真实对话均有回答；第三轮问"我第一个问题问的是什么产品"能答出 `个人消费贷`（窗口内跨轮记忆）；
3. 最后一行 `SUCCESS: ...`，退出码 0。

实测输出（真实运行）：

```text
=== 窗口裁剪 ===
历史 20 条 -> 窗口 8 条
窗口首条: 第6轮: 咨询贷款产品6号，额度6万，利率3.6%。
窗口末条: 已记录您咨询产品9号（额度9万）。

客户: 个人消费贷最高多少额度？
客服: 个人消费贷额度根据您的资质浮动，最高可达数万元。...

客户: 我第一个问题问的是什么产品？
客服: 个人消费贷

SUCCESS: 窗口裁剪(20->8, 保留最近) + 引擎配置接入 Agent 验收通过
```

## 代码讲解（真实 API）

| 关键点 | API | 说明 |
|---|---|---|
| 引擎配置 | `ContextEngineConfig(max_context_message_num=8, default_window_message_num=6)` | 硬上限 + 默认窗口，超限自动丢最旧（滑动窗口） |
| 独立使用 | `await engine.create_context(history_messages=[...])` → `ctx.get_messages()` | 返回窗口内消息 |
| 接入 Agent | `ReActAgentConfig(context_engine_config=ContextEngineConfig(...))` | Agent 每轮自动走窗口管理 |
| LLM 压缩 | `processors=[("DialogueCompressor", DialogueCompressorConfig(model=..., model_client=...))]` | 超阈值用 LLM 摘要压缩（见下） |

## 开发者注意（真实踩坑）

- processor 的 type 字符串是**类名**（`"DialogueCompressor"`），不是 snake_case；
- LLM 压缩器（`DialogueCompressor/FullCompactProcessor`）需要同时配 `model` 和 `model_client`，且触发时机由内部策略决定（本机实测 force-trigger 时出现 noop——阈值与轮次选择策略要结合 `messages_to_keep/keep_last_round` 调试）；
- 窗口截断（本样例 Part 1）是**确定性**的，可测试可断言；LLM 压缩是概率性的，验收用"消息数下降"而不是内容断言；
- `create_context` 是 **async** 且要求 `BaseMessage` 对象（`UserMessage/AssistantMessage`），不能直接塞 dict。
