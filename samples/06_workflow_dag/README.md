# 样例 06 · Workflow 工作流 —— 消费贷审批流

> 使用**开源 openjiuwen Agent Core 真实 API**（`openjiuwen==0.1.16`）。对应特性清单 §3 Workflow / §14 Graph Execution。

## 场景

四组件审批流水线：

```text
Start(query) → 抽取客户号(LLM,json) → 查授信评分(Tool/RestfulApi) → 生成审批意见(LLM) → End(responseTemplate)
```

评分 ≥70 自动批复，<70 人工复审——结论由**工具返回的真实数据**驱动。

## 运行

```bash
python main.py   # 需要 LLM 网关（默认本地 127.0.0.1:1234）；评分 API 为本地 mock（SSRF 开关已放行）
```

## 验收方式

1. mock 评分 API 日志显示收到 `q='C1001'`——证明 **LLM 抽参 → 字段级引用 → 工具入参** 的数据流真实生效；
2. 最终输出 `state=COMPLETED`，意见引用真实评分（C1001 → 82 分 → "符合评分≥70的自动批复条件"）；
3. 最后一行 `SUCCESS: Start→LLM抽参→Tool评分→LLM意见→End 全链路执行 验收通过`，退出码 0。

实测输出（真实运行，qwen3-4b-thinking-2507）：

```text
[mock-api] 收到评分查询 q='C1001'
=== 工作流最终输出 ===
result={'response': '客户C1001的授信评分为82分，符合评分≥70的自动批复条件。
建议直接自动批复其30万元消费贷申请。'} state=<WorkflowExecutionState.COMPLETED: 'COMPLETED'>

SUCCESS: Start→LLM抽参→Tool评分→LLM意见→End 全链路执行 验收通过
```

## 代码讲解（真实 API）

| 关键点 | API | 说明 |
|---|---|---|
| 工作流 | `Workflow(card=WorkflowCard(id/name/version/input_params))` | `input_params` 是**完整 JSON Schema**（`{"type":"object","properties":...,"required":[...]}`），不是字段字典 |
| 组件 | `LLMComponent(LLMCompConfig(...))` / `ToolComponent(ToolComponentConfig(tool_id=...))` / `Start()` / `End({...})` | 工具要先 `Runner.resource_mgr.add_tool` |
| 数据流 | `add_workflow_comp(id, comp, inputs_schema={"q": "${extract.customer_id}"})` | `${组件.字段}` 引用上游输出 |
| 拓扑 | `set_start_comp / add_connection / set_end_comp` | End 的 `responseTemplate: "{{output}}"` 决定返回形态 |
| 执行 | `await flow.invoke(inputs, session=create_workflow_session(...))` | 返回 `WorkflowOutput`（`.output` 里是 `{'response': ...}`，state=COMPLETED） |

## 开发者注意（真实踩坑）

- **`response_format={"type":"text"}` 的 LLM 组件，输出字段值=整段模型文本**。要字段级抽取（如 `customer_id`）必须 `{"type": "json"}`——否则 `${extract.customer_id}` 引用的是一整段话，下游工具收到 `'{"customer_id": "C1001"}'` 这种字符串（本样例用 mock API 日志实锤过）；
- **工作流默认 60 秒执行超时**：本地/慢模型两次 LLM 调用必超。通过 `create_workflow_session(envs={"_execute_timeout": 600})` 调大（键是框架常量 `WORKFLOW_EXECUTE_TIMEOUT` 的值）；
- `End(responseTemplate)` 的 `{{output}}` 引用 set_end_comp 里映射的 inputs；
- 意图路由多工作流（ControllerAgent + ability_manager.add(card)）见官方 `multi_workflow_agent_demo`，那是"一个 Agent 管多个 Workflow"的形态，本样例聚焦单工作流执行。
