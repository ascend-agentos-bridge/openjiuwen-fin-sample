# 样例 13 · 人机协同中断/恢复 —— 大额放款的人工确认

> 使用**开源 openjiuwen Agent Core 真实 API**（`openjiuwen==0.1.16`），中断模式对齐官方 `multi_workflow_agent_demo`。对应特性清单 §12.4 Interrupt/Resume / §12.5 Checkpoint。

## 场景

80 万经营贷超过 50 万自动审批上限：工作流在"审批员确认"节点（`QuestionerComponent`）挂起 → 审批员输入 `approved` → **同一 session 断点恢复** → LLM 出具最终批复。

## 运行

```bash
python main.py
```

## 验收方式

1. 第一次执行：`state=INPUT_REQUIRED`，`result` 含 `type='__interaction__'` 事件，payload 提问文本（"请审批员输入批准意见(approved/rejected)"）；
2. `InteractiveInput().update("ask", "approved")` + 同 session 再 invoke；
3. 恢复后 `state=COMPLETED`，批复含"同意…放款80万元经营贷"；
4. 最后一行 `SUCCESS: ...`，退出码 0。

实测输出（真实运行）：

```text
=== 第一次执行（应挂起等待人工输入） ===
result=[OutputSchema(type='__interaction__', index=0, payload=InteractionOutput(id='ask',
value='该笔放款超过50万元自动审批上限，请审批员输入批准意见(approved/rejected)：'))]
state=<WorkflowExecutionState.INPUT_REQUIRED: 'INPUT_REQUIRED'>

>>> 挂起于组件 'ask', 提问: 该笔放款超过50万元自动审批上限...

=== 人工决定: approved, 从断点恢复(component_id=ask) ===
result={'response': '经审批，同意向客户C1001放款80万元经营贷。'} state=<WorkflowExecutionState.COMPLETED: 'COMPLETED'>

SUCCESS: 中断挂起 + InteractiveInput 恢复 + 最终批复 验收通过
```

## 代码讲解（真实 API）

| 关键点 | API | 说明 |
|---|---|---|
| 中断节点 | `QuestionerComponent(QuestionerConfig(model_client_config, model_config, question_content, field_names=[FieldInfo(...)]))` | 配置参数名是 `model_config`（不是 `model`/`model_config_obj`） |
| 挂起形态 | `WorkflowOutput.state=INPUT_REQUIRED`，`result[i].type='__interaction__'`，`payload=InteractionOutput(id, value)` | `id` 是组件名，恢复时要用 |
| 人工输入 | `InteractiveInput().update(component_id, 值)` | 一条输入对应一个挂起组件 |
| 恢复执行 | `await flow.invoke(interactive_input, session=同一session)` | **session 复用是恢复的前提**（检查点在 session 里） |
| 最终批复 | LLM 组件引用 `${ask.approval}` | 人工输入作为字段流入下游 |

## 开发者注意（真实踩坑）

- 恢复的 `invoke` 输入是 `InteractiveInput` 而不是原始 dict——用错类型会被 input_params 校验拒掉；
- 挂起组件的 `id` 从 `interaction` 事件 payload 里拿（本例 `"ask"`），不要硬编码；
- 检查点默认 inmemory（日志 `storage_type: inmemory`）——跨进程恢复要换持久化 session 存储；
- rejected 分支：在 opinion 前加 Branch 组件按 `${ask.approval}` 路由即可（工作流条件分支，样例 06 同款）。
