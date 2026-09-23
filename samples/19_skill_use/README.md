# 样例 19 · Agent Skill —— 给 Agent 装配「贷前审查」技能

> 使用**开源 openjiuwen Agent Core 真实 API**（`openjiuwen==0.1.16`，0.1.18.post1 的 skill 模块与其完全一致）。对应特性清单 §7.1 Agent Skill。

## 场景

信贷审查团队沉淀了一套「贷前审查意见」的写法规范（先查档、再按五段模板撰写、禁止编数据）。把这套规范写成 **SKILL.md 技能文档**放进文件系统，Agent 启动时只注册技能清单，运行时按需读取技能正文并照着执行——规范更新只需改文件，不用改代码、不用改提示词。

## 运行

```bash
python main.py
```

样例目录自带技能：`skills/pre_loan_review/SKILL.md`（frontmatter 写 `name`/`description`，正文写工作流程与强制输出模板）。

## 验收方式

1. `agent.register_skill("skills")` 把含 SKILL.md 的子目录注册为技能（技能名 = 子目录名）；
2. 提问里**不出现"技能"二字**，模型按技能描述自主匹配；
3. 答案包含 SKILL.md 规定的 `【客户档案】`/`【审批结论】`等五段标题——这些标题在系统提示和用户提问里都不存在，出现即证明模型读了技能正文并遵循；
4. 答案引用 `query_customer` 查到的客户数据（张伟 / AA / 58.0 万元），无编造；
5. 最后一行 `SUCCESS: ...`，退出码 0。

实测输出（真实运行，本地 LM Studio，模型 `google/gemma-4-e4b`）：

```text
=== 最终答案 ===
**【客户档案】**
客户号C1001，姓名张伟，信用等级AA，贷款余额58.0万元。

**【还款能力】**
客户信用良好，贷款余额在可控范围内，预计具备稳定的还款能力。

**【信用状况】**
客户目前无逾期记录。信用等级为AA，表明其财务状况和还款意愿处于优秀水平。

**【风险提示】**
未见明显风险。

**【审批结论】**
同意。客户信用状况良好，且无逾期记录，符合续贷条件。

SUCCESS: register_skill 注册 + 技能提示注入 + 模型按 SKILL.md 流程执行, 验收通过
```

运行日志里能看到完整的技能装载链路——模型第一轮就自主调用 `read_file` 读技能正文（推理摘录）：

```text
tool_calls=[... name='read_file', arguments='{"path":".../skills/pre_loan_review/SKILL.md"}'],
reasoning_content='I need to follow the workflow defined in the `pre_loan_review` skill...
**Step 1: Read SKILL.md**'
```

## 代码讲解（真实 API）

| 关键点 | API | 说明 |
|---|---|---|
| 文件底座 | `SysOperationCard(mode=OperationMode.LOCAL) + Runner.resource_mgr.add_sys_operation(card)` | 技能注册与 read_file 都走这套 fs；`cfg.sys_operation_id = card.id` 必须挂上 |
| 读文件工具 | `Runner.resource_mgr.get_sys_op_tool_cards(sys_operation_id, "fs", "read_file")` → `agent.ability_manager.add(card)` | **技能提示注入的前提**，见下方注意点 |
| 技能注册 | `await agent.register_skill(str(skills_dir))` | 参数是技能目录或其父目录（可一次注册多个）；每个含 SKILL.md 的子目录注册为一个技能 |
| 技能文档格式 | SKILL.md 的 YAML frontmatter | `description` 必填（缺了注册报 KeyError）；`name` 字段不参与命名——**技能名 = 子目录名** |
| 运行时注入 | 框架自动完成 | `invoke` 阶段把技能清单（名称/描述/目录）拼进系统提示，并指示模型用 `read_file` 读 SKILL.md 再按流程执行 |

## 开发者注意（真实踩坑）

- **不挂 read_file 工具，技能形同虚设**：框架只在日志里告警（`skill prompt requires tool 'read_file' but it is not found in ability_manager`），技能清单照样注入，但模型无法读取技能正文——这是最容易踩的坑；
- **重复注册会抛 ValueError**：`Skill already exists: pre_loan_review`。`register_skill` 没有暴露 `overwrite` 参数（`SkillManager.register` 有），同一 agent 上重复注册同一技能目录会炸；
- **SKILL.md 匹配大小写不敏感**（`skill.md` 也认），但技能名取的是**子目录名**，frontmatter 里的 `name` 只作文档声明，框架不读；
- **描述缺失不静默**：frontmatter 没有 `description` 时 `register_skill` 直接抛 KeyError，不是跳过该技能；
- 本地 LM Studio 实测出现过一次首次调用 `Connection error`（框架原样抛 `[181001] model call failed`），复跑即恢复；本地小模型推理慢，记得设 `cfg.model_client_config.timeout = 300`（同 01 的坑）；
- 技能机制与工具是互补的：工具（02）给模型"能力"，技能（本样例）给模型"操作规程"。规程放在文件系统里按需装载，正是官方 skill 机制的设计意图。
