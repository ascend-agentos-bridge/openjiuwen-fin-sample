---
name: pre_loan_review
description: 对客户经理已按标准月化字段录入的贷款申请进行事实核验、偿债收入比计算与演示政策判断，输出六段式贷前审查意见；输入必须已是可直接计算的月化、去重字段。
---

# 贷前审查技能

## 何时使用

需要依据客户经理录入的贷款申请信息出具贷前审查意见时使用本技能，包括：

- 计算并说明偿债收入比；
- 核对当前逾期、资料完整性与演示政策红线；
- 按固定六段结构给出审查建议与人工复核要点。

用户指定按 DEMO-CREDIT-2026.1 演示政策审查时，也必须使用本技能。

## 输入前置假设

本技能面向客户经理已完成标准录入的申请，使用前确认以下前提成立：

1. 收入、既有债务、本次月供均为**月口径**字段：年收入已折算为月收入，季度或其他周期还款已折算为月还款；
2. 同一笔合同、同一笔债务在输入中**只出现一次**：不存在汇总表与明细表重复列示同一合同的情况；
3. 各字段数值可直接进入计算，不需要在记录之间做取舍。

以上前提由客户经理录入环节保证，本技能按给定字段直接计算与判断。

## 工作步骤

1. **读演示政策**：用 skill_tool 读取 `references/policy-demo.md`：
   `skill_tool(skill_name="pre_loan_review", relative_file_path="references/policy-demo.md")`；
   也可用 read_file 直接读取技能目录下的 `references/policy-demo.md`。
2. **读输出格式**：用同样方式读取 `references/output-format.md`。
3. **提取事实**：从申请信息中提取客户与申请要素、月收入、既有月债务、本次月供、当前逾期状态与资料完整性；缺失项单独记入人工复核。
4. **调用脚本计算**：用技能内置脚本计算偿债收入比，不要用手算结果替代脚本输出：
   `python <skill_dir>/scripts/dti.py --income <月收入> --existing-debt <既有月债务> --new-payment <本次月供>`
   其中 `<skill_dir>` 指本技能目录（即 `skills/pre_loan_review`），通常取 skill_tool 返回的 `skill_directory` 字段。
   脚本输出 JSON，字段包括 `monthly_income`、`existing_monthly_debt`、`new_monthly_payment`、`total_monthly_debt_service`、`debt_service_ratio`、`raw_ratio`、`above_50_percent`。
5. **按政策判断**：对照 `references/policy-demo.md` 的阈值与建议范围，逐条说明红线是否触发及依据。
6. **按格式输出**：按 `references/output-format.md` 的六段结构输出，不得省略小节。

## 参考文件

- `references/policy-demo.md`：DEMO-CREDIT-2026.1 演示政策（当前逾期、偿债收入比、资料完整性、建议权限与演示边界）。
- `references/output-format.md`：六段输出结构、金额与百分比展示规范、事实/计算/判断分离要求。

读取方式：`skill_tool(skill_name="pre_loan_review", relative_file_path="references/<文件名>")`，或在技能目录内用 read_file 读取对应相对路径。

## 脚本

- `scripts/dti.py`：确定性偿债收入比计算器，只使用 Python 标准库，可直接运行。
- 调用示例（`<skill_dir>` 为本技能目录）：
  `python <skill_dir>/scripts/dti.py --income 20000 --existing-debt 3000 --new-payment 6000`
- 调用前先按“输入前置假设”确认字段口径；脚本按传入数值计算并输出 JSON 结果。

## 输出要求

必须输出以下六个小节，标题与顺序固定，不得省略、合并或改名：

【客户与申请】
【事实核验】
【还款能力】
【风险与政策依据】
【审查建议】
【人工复核】

每段的具体内容、金额与百分比展示方式、事实/计算/判断的分离要求，见 `references/output-format.md`。

## 边界

只能给出“建议补充材料”“建议人工复核”“建议进入后续审批”三类建议之一；不得输出“最终批准”“同意放款”等最终结论，最终决策由有权审批人作出。
