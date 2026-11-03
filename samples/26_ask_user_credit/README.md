# 样例 26 · ask_user 人工澄清（HITL）—— 消费贷预审先问清客户诉求

> 使用**开源 openjiuwen Agent Core 真实 API**（`openjiuwen==0.1.20`，当前最新 release），无任何本地封装。对应特性清单：人机交互 `ask_user` 提问 + 中断恢复。

## 场景

客户陈女士在手机银行留言：*"我想贷点钱装修，你们能批多少？"* ——只说了用途，没说金额和期限。

Agent 不自己假设，先用内置 **`ask_user`** 工具一次性问清「金额区间 + 还款期限」；客户在 App 里点选后，答案回填给 Agent，Agent 再调用 `consumer_loan_precheck` 规则工具算出可贷额度 / 利率 / 月供，最后给出预审意见。

一句话：**信息不全就先问用户，问清楚再办。**

## 预期结果（中断 → 恢复流程）

1. **第 1 次 `invoke`**：模型调用 `ask_user`，`AskUserRail` 拦截并返回中断，`result_type="interrupt"`；`state[0].payload.value` 是 `ToolCallInterruptRequest`（`interrupt_kind="ask_user"`、`questions` 1–4 个、每题 2–4 个选项）；
2. 宿主（手机银行）把 `questions` 渲染成选择卡片。本样例程序模拟客户按画像选择：金额挑最接近 **15 万**、期限挑最接近 **4.5 年**的选项；
3. 用 `InteractiveInput().answer(interrupt_id, {"answers": {问题原文: 选项文案}})` 回填答案；
4. **第 2 次 `invoke`（同一 session）**：`AskUserRail` 把答案格式化成工具结果回给模型，模型继续执行 → 调用 `consumer_loan_precheck` 规则工具 → 输出预审意见；
5. 断言全部通过，最后一行打印 `SUCCESS: ...`，退出码 0。

## 运行

```bash
# 前提: pip install -U openjiuwen   (0.1.20)
# LLM: OpenAI 兼容网关，默认连本机 LM Studio (127.0.0.1:1234)
python main.py
```

环境变量（与仓库其它 Python 样例一致）：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `API_BASE` | `http://127.0.0.1:1234/v1` | OpenAI 兼容网关地址 |
| `API_KEY` | `lm-studio` | 网关密钥 |
| `MODEL_NAME` | `google/gemma-4-e4b` | 本地实测模型名 |
| `MODEL_PROVIDER` | `openai` | 客户端类型 |

## 验收方式

运行成功必须同时满足（代码内 assert，退出码 0）：

1. 第 1 次 invoke 返回 `result_type=interrupt`、`interrupt_ids` 非空；中断里 `interrupt_kind=ask_user`，`questions` 数量 1–4，每题选项 2–4；
2. 每个问题都生成了非空客户答案，并按 `interrupt_id` 用 `InteractiveInput().answer(...)` 回填；
3. 第 2 次 invoke 返回 `result_type=answer`，预审意见非空；
4. `consumer_loan_precheck` 工具**真实被调用**（调用记录非空），额度/利率/月供来自工具返回；
5. 最后一行 `SUCCESS: ask_user 中断 → answers 回填 → 恢复执行 → 消费贷预审 验收通过`。

## 实测输出（真实运行，已过滤框架 INFO 日志）

```text
=== 第 1 次 invoke：客户提出模糊申请，Agent 触发 ask_user ===
result_type = interrupt, interrupt_ids = ['628160787']
  interrupt_kind = ask_user, interrupt_id = intr_b3f7a6be1e634f5f8db4940969768c55
  共 2 个问题（ask_user 支持 1-4 个问题、每题 2-4 个选项）：

  问题1 [贷款金额范围（推荐）] 您计划申请的装修贷款大致在哪个金额区间呢？
    1. 5万 - 10万元 —— 适用于小规模装修或局部翻新。
    2. 10万 - 20万元 —— 适用于中等规模的装修项目。
    3. 20万 - 50万元 —— 适用于较大规模的装修或改造。
    4. 50万元以上 —— 适用于大型、高预算的装修项目。

  问题2 [贷款期限选择（推荐）] 您希望将这笔装修款项在多长时间内还清呢？
    1. 1 - 3年 —— 月供压力较小，但总利息略高。
    2. 3 - 5年 —— 平衡月供和总利息的常用选择。
    3. 5年以上 —— 月供压力最小，但总利息最高。

=== 模拟客户在银行 App 中选择 ===
  「您计划申请的装修贷款大致在哪个金额区间呢？」→ 10万 - 20万元
  「您希望将这笔装修款项在多长时间内还清呢？」→ 3 - 5年

=== 第 2 次 invoke：回填答案，Agent 从中断处恢复 ===
result_type = answer

=== 最终预审意见 ===
好的，陈女士。根据您提供的装修贷款需求（金额区间：10万-20万元，期限：3-5年），我已为您进行了初步的规则预审。

**【预审结果】**
*   **预审结论：** 预审通过，可批 **15.0 万元**。
*   **年利率：** 3.6%
*   **还款期限：** 48个月（即4年）
*   **每月还款额：** 3,360.08 元

请注意，这是一个初步的规则预审结果。最终的贷款额度、利率和还款计划将以银行正式审批为准。如果您对这个方案满意，可以继续办理后续的申请流程。

=== 预审工具真实调用记录（规则计算由工具完成） ===
  {'purpose': '装修', 'target_amount_wan': 15.0, 'approved_amount_wan': 15.0, 'annual_rate_pct': 3.6, 'term_months': 48, 'monthly_payment_yuan': 3360.08, 'manual_review': False, 'conclusion': '预审通过，可批 15.0 万元，进入正式审批流程'}

SUCCESS: ask_user 中断 → answers 回填 → 恢复执行 → 消费贷预审 验收通过
```

关键框架日志（证明工具真实被调用、中断真实恢复）：

```text
INFO | ReAct iteration 1
INFO | [LLM] <<< response: content_len=0, tool_call_count=1, tokens={input=594, output=564}
INFO | Executing tool: ask_user with args: {"questions": [...]}
...
INFO | Resuming tool interrupt from iteration 1
INFO | ReAct iteration 2
INFO | Executing tool: consumer_loan_precheck with args: {"amount_range":"10万 - 20万元","purpose":"装修","term_range":"3 - 5年"}
INFO | ReAct iteration 3
INFO | [LLM] <<< response: content_len=244, tool_call_count=0, tokens={input=1523, output=169}
```

> 本地模型用 `google/gemma-4-e4b` 实测，单次运行约 60–100s（模型带 reasoning，首轮约 1 分钟）。换模型后问题的文案/选项范围会变，断言只看结构。

## 代码讲解（真实 API）

| 关键点 | API | 说明 |
|---|---|---|
| 挂载提问能力 | `await agent.register_rail(AskUserRail())` | 注册后 Rail 自动把内置 `ask_user` 工具挂到 Agent（内部 `ability_manager.add_ability`），**不用手动注册工具** |
| 第 1 次调用 | `await agent.invoke({"query": ..., "conversation_id": SESSION_ID}, session=session)` | 模型判断信息不全 → 调用 `ask_user` |
| 中断形态 | `result_type="interrupt"`；`state[0].payload.value` = `ToolCallInterruptRequest` | 中断里带 `interrupt_kind` / `interrupt_id` / `questions`，不是异常也不是普通回答 |
| 回填答案 | `InteractiveInput().answer(interrupt_id, {"answers": {问题原文: 答案}})` | `answers` 的键必须与 `question` 字段原文一致 |
| 恢复执行 | 同一 `session` 再 `invoke({"query": interactive_input, ...})` | 中断状态保存在 session 里，换 session 恢复不了 |
| 业务规则工具 | `@tool(description=...)` + `agent.ability_manager.add_ability(tool.card, tool)` | 额度上限/利率/月供公式放在工具里，模型只负责组织自然语言结论 |
| 多问题模式 | ask_user 一次 1–4 题、每题 2–4 个选项，支持 `multi_select` / `preview` | 本样例模型问了 2 题（金额、期限），用途已从原话拿到 |

## 开发者注意（真实踩坑）

- **回填工具中断用 `InteractiveInput().answer(interrupt_id, ...)`**，不是 `.update(...)`：tool-call 中断按 `interrupt_id` 路由回触发它的 Rail；`.update(node_id, ...)` 是 workflow 节点交互（样例 13）的用法，两者形态不同；
- `interrupt_id` 在 `state[0].payload.value.interrupt_id`；`result["interrupt_ids"]` 里是**工具调用 id**，别拿 `interrupt_ids[0]` 当 `interrupt_id`；
- `answers` 的 key 必须与 `question` 字段**原文完全一致**，否则 Rail 匹配不到答案，会再次中断继续问；
- 恢复必须复用同一个 `session` 对象（中断状态 `ToolInterruptionState` 存在 session 里）；
- LLM 输出有随机性：本样例模型可能问 1–4 题、选项文案每次不同，断言只锚结构（问题数量、选项数量、工具是否调用），不断言具体措辞；
- 金额/利率/月供这类数字一律由业务工具计算，不要让模型自己算，否则样例的"实测输出"会漂；
- 中断只发生在第一次 invoke 的工具阶段，恢复后的第二次 invoke 才进入下一轮 ReAct；日志里能看到 `Resuming tool interrupt from iteration 1`；
- 本样例用 0.1.20 的 `answer(interrupt_id, ...)` 恢复 API；仓库 01–19 样例 README 标注的是 0.1.16，中断恢复写法以本目录为准。
