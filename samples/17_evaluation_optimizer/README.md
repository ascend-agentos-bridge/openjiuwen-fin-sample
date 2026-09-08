# 样例 17 · 评测 —— LLM-as-Judge 给信贷答复打分

> 使用**开源 openjiuwen Agent Core 真实 API**（`openjiuwen==0.1.16`，`openjiuwen.agent_evolving`）。对应特性清单 §17 Evaluation / §18 Developer Tools。

## 场景

同一问题的两份客服答复：语义正确 vs 语义错误。`DefaultEvaluator` 以 LLM 为裁判对比 predict 与 label，输出可区分的 score 与 reason。

## 运行

```bash
python main.py
```

## 验收方式

1. 好答复 `score=1.0`（reason 指出"语义一致，仅省略贷款品类"）；
2. 坏答复 `score=0.0`（reason 指出"标准答案是免违约金，预测却收 5%"）；
3. 断言 `good > bad` 且 `bad <= 0.5`；
4. 最后一行 `SUCCESS: ...`，退出码 0。

实测输出（真实运行，qwen3.5-9b 作为裁判）：

```text
=== 好答复 ===
score=1.0
reason=Both responses consistently state that personal consumption loans have no penalty fees for early settlement and require ...

=== 坏答复 ===
score=0.0
reason=The expected answer explicitly states that personal consumption loans have no penalty fee ... 

SUCCESS: LLM-as-Judge 区分好坏答复 验收通过
```

## 代码讲解（真实 API）

| 关键点 | API | 说明 |
|---|---|---|
| 用例 | `Case(case_id, inputs={"query": ...}, label={"answer": 标准答案})` | **label 必须是 dict**，传字符串会 ValidationError |
| 评测器 | `DefaultEvaluator(model_config, model_client_config)` | 裁判模型配置 |
| 打分 | `evaluator.evaluate(case, predict_dict)` | **同步方法**（不是 await）；返回 `EvaluatedCase(score, reason)` |
| 批量 | `evaluator.batch_evaluate(...)` | 数据集级评测 |

## 开发者注意（真实踩坑）

- `evaluate` 是同步方法（内部自管事件循环）——不要 await；
- 裁判是 LLM：score 有噪声，同一 case 多次评测可波动；关键结论要多次采样或换更强裁判模型；
- `agent_evolving` 还有 `MetricEvaluator`（自定义指标）、`LLMAsJudgeMetric`、`InstructionOptimizer`（prompt 优化）、RL 相关（Rollout/Reward）等进阶组件，入口见模块 `__init__`。
