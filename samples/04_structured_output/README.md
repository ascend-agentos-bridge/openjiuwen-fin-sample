# 样例 04 · Structured Output —— 贷款申请信息抽取

> 使用**开源 openjiuwen Agent Core 真实 API**（`openjiuwen==0.1.16`）。对应特性清单 §4.2。

## 场景

客户经理口述申请单，`Model` 直调模型抽取 JSON，`JsonOutputParser` 解析，dataclass 类型化后供核心系统入库。

## 运行

```bash
python main.py   # 需要 LLM 网关（默认本地 127.0.0.1:1234）
```

## 验收方式

1. 模型原始输出为纯 JSON（含 `applicant/product/amount_wan/term_months/purpose`）；
2. `await JsonOutputParser().parse(raw)` 得 dict，dataclass 类型化后 `amount_wan=float, term_months=int`；
3. ` ```json ` 代码块包裹的输出能被 parser 剥出 dict；**纯坏文本 parse 返回 `None`**（真实行为：不抛错，框架打 ERROR 日志）；
4. 最后一行 `SUCCESS: ...`，退出码 0。

实测输出（真实运行，qwen3-4b-thinking-2507）：

```text
=== 模型原始输出 ===
{"applicant": "张伟", "product": "个人消费贷", "amount_wan": 30, "term_months": 36, "purpose": "房屋装修"}

=== 类型化结果 ===
LoanApplication(applicant='张伟', product='个人消费贷', amount_wan=30.0, term_months=36, purpose='房屋装修')
类型: amount_wan=float, term_months=int
核心系统入参: insert_loan_application(applicant='张伟', amount=300000)

=== 坏输出处理（真实行为: 返回 None, 需开发者显式兜底） ===
markdown 包裹 -> {'applicant': '张伟', 'amount_wan': 30}
纯坏文本     -> None
=> 兜底策略: 返回 None 时走重试/人工录入, 绝不把 None 传给业务层

SUCCESS: Model 直调 + JsonOutputParser + 强类型化 + None 兜底 验收通过
```

## 代码讲解（真实 API）

| 关键点 | API | 说明 |
|---|---|---|
| 直调模型 | `Model(ModelClientConfig(...), ModelRequestConfig(...))` + `await model.invoke([{role,content}])` | 不建 Agent 的最短路；返回 `AssistantMessage` |
| 解析 | `await JsonOutputParser().parse(text)` | **异步**；自动剥离 ` ```json ` 代码块 |
| 类型化 | `to_loan_application(d)` | parser 只给 dict，schema 校验/类型化是开发者的活 |

## 开发者注意（真实踩坑）

- **`JsonOutputParser` 对坏输出返回 `None` 而不是抛错**——错误只在框架日志里（`Failed to decode JSON from LLM output`）。生产代码必须显式判 `None`，否则 None 会一路传进业务层；
- parser 能容 ` ```json ` 代码块，容不了"JSON 前后有文字"——提示词里要求"只输出 JSON"依然必要；
- 抽取字段用小写无空格的英文键名，4B 级小模型遵从性最好；
- 需要输出模板时可在 user 消息里直接给 JSON 例子，比描述字段更稳。
