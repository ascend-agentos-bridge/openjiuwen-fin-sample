# 样例 25 · Harness RSI —— 用真实失败案例持续演进信贷 Agent 的 Prompt 与 Skill

> 基于开源 [`openjiuwen==0.1.20`](https://github.com/openJiuwen-ai/agent-core/releases/tag/v0.1.20) 的 Single Harness RSI API。所有 Agent 执行、失败归因、候选生成、回放与 LLM 评审都调用真实模型；不使用 mock LLM。案例、人员、政策编号均为虚构演示。

## 1. 这个样例解决什么问题

一家银行的信贷一部已经上线了"标准字段版"贷前审查 Agent（下文称 h0）：客户经理把申请信息录成月收入、月债务、本次月供、逾期状态等字段后，它能够计算偿债收入比、按演示政策给出审查建议。

上线后业务反馈了真实问题：遇到**原始材料摘录**（收入按年/按季给、同一合同在汇总与明细中重复出现、旧记录被新记录替代）时，Agent 会做出错误判断；在材料完整、没有触发政策红线时，它也经常把客户转人工复核，而不是按政策进入后续审批。业务专家把这些脱敏案例登记下来，评测工程师把它们冻结成优化集与独立留出集。

本样例展示的不是"再写一个 Agent"，而是：**把现有 Agent 的 Prompt 与 Skill 导出成官方 Plugin Harness，交给官方 RSI 闭环，用真实失败证据改进它，并用独立留出集复核改完之后没有变差。** 运行者能看到：

1. 一个符合业界标准的 Skill 目录长什么样、引擎如何加载它；
2. h0 的缺口如何被冻结评测集和裁判证据暴露出来；
3. 官方 RSI 如何完成基线 → 归因 → 候选 → 全量回放 → 晋升；
4. 候选到底改了哪个文件、哪几行，以及改前改后的逐案得分；
5. 独立留出集上的双版本复验结果。

与样例 17 的区别：17 演示一次 LLM-as-Judge 评分；本样例使用官方 RSI 控制面完成基线、归因、候选、门禁、版本和发布证据管理，并保留企业交接物料。与 AutoHarness 的区别：本样例不做 Git/CI/PR 编码演进。

## 2. 业界标准 Skill 目录

本样例的 Skill 不是单个 `SKILL.md`，而是一个可维护、可执行、可渐进披露的技能目录：

```text
harness/credit_review/
├── manifest.json                     # 官方 Plugin 包声明：prompt_sections + skills[].dir
├── prompt_sections/
│   ├── sections.yaml                 # Prompt 分节注册（与官方 RSI 可编辑面约定一致）
│   └── files/
│       └── credit_review.md          # 角色、职责边界、必须按技能执行、六段输出
└── skills/pre_loan_review/
    ├── SKILL.md                      # 精简流程：何时用、前置假设、步骤、输出要求、参考/脚本索引
    ├── references/
    │   ├── policy-demo.md            # 演示政策 DEMO-CREDIT-2026.1（阈值、红线、建议权限）
    │   └── output-format.md          # 六段结构、金额/百分比口径、事实/计算/判断分离
    └── scripts/
        └── dti.py                    # 确定性偿债收入比计算器（仅标准库，可命令行运行）
```

引擎如何加载与使用（官方行为，不是本仓库封装）：

- `manifest.json` 的 `skills[].dir` 指向技能目录，`DeepAgent.load_plugin()` 按目录解析；
- `SkillUseRail` 把技能暴露给 Agent；`skill_tool` 返回 `SKILL.md` 正文、技能目录绝对路径和目录树；
- Agent 用 `skill_tool(relative_file_path="references/...")` 按需读取参考文档，用 `bash/read_file` 执行或读取 `scripts/`；
- 本样例的 `SKILL.md` 明确要求"先读技能、再按 references/scripts 执行"，因此技能目录真正参与运行，而不是摆设。

为什么不是一个 `SKILL.md` 打天下：

- 政策、格式这类文档放在 `references/`，Agent 按需读取，主文件保持简短，避免上下文膨胀；
- 计算口径落在 `scripts/dti.py`，数值可复现、可离线单测，不依赖模型心算；
- 候选项可以通过官方 RSI 修改 `SKILL.md`/`prompt_sections/files/`，而 `references/`、`scripts/` 作为静态资产继续可用；验收会检查候选包里的技能目录仍然完整、脚本仍能运行。

## 3. h0 的真实缺口（不是故意做弱）

h0 是"标准字段版"技能：它假设客户经理已经完成月化、去重，字段可以直接进入计算。这个假设在第一版生产里是成立的，但收件形态变化后就会暴露问题：

| 挑战 | 业务输入 | h0 的典型表现 |
|---|---|---|
| 周期折算 | 年收入 240000、每季度还款 9000 | 折算步骤时有时无，口径不稳定 |
| 合同重复 | 汇总合计已含 L02，明细又列一次 L02 | 把"重复列示"当作不确定事实，转人工/补充材料 |
| 汇总+旧计划 | K01 在摘要/明细重复，且含已结清旧计划 | 过度保守，主结论不是政策要求的"进入后续审批" |
| 记录替代 | C11 旧记录当前逾期，新记录明确替代且已结清 | 多数情况能识别，但主建议经常退回人工复核 |

这些不是编造的错误：失败案例来自业务反馈的收件形态，评分协议由业务冻结。h0 本身的政策红线（当前逾期、>50%）和六段格式是可靠的，本样例保护这两项能力不退化。

## 4. 冻结评测集与评分协议

- 优化集 `data/optimization_cases.json`：**6 案**，2 个保护案（当前逾期红线 `opt-current-overdue`、正常进入审批 `opt-eligible-review`）+ 4 个挑战案（`opt-mixed-periods` 周期折算、`opt-duplicate-debt` 合同重复、`opt-duplicate-transfer` 汇总重复/旧计划、`opt-superseded-record` 记录替代）；
- 留出集 `data/holdout_cases.json`：**4 案**，使用不同金额、合同号、表述和材料排列，并额外覆盖精度边界（50.004%），只在验收阶段使用，不传给 RSI；
- 评分：每案 4 个正向行为（权重合计 1.0）+ 若干 forbidden 行为（`penalty_mode: subtract`，命中即扣分）。其中"材料完整、无逾期、比值不超 50% 时仍把主结论写成补充材料/人工复核"被定义为 forbidden `over_escalation`（扣 0.6）：业务上，无谓转人工是有成本的错误，不应拿平均分掩盖；
- 对外统一 **100 分制**（原始分 × 100，展示保留两位小数）；RSI state/report 与数据集权重仍用 0～1 原始尺度；
- 裁判采用冻结 rubric 的绝对评分（`judge_baseline_comparison: false`），不把基线分数放进裁判上下文，减少长上下文噪声；候选与基线的比较由官方 RSI 控制面完成；
- 参数与预算：优化集单批（`batch_size: 6`）、`max_epochs: 1`、`min_improvement: 0.05`、候选/验证/回放证据全部落盘；这些是样例预算，不是企业生产阈值。

## 5. 用户旅程：从"看到错"到"证明变好"

1. **先看已有能力**：h0 + 保护案 → 当前逾期红线、正常审批路径不得退化。
2. **再看一笔真实失败**：挑战案里 h0 把可确定归一的重复记录当作不确定，主结论变成"建议补充材料"。
3. **冻结数据与协议**：业务提供脱敏案例、参考答案、政策依据；评测冻结 required/forbidden 行为与权重。
4. **交给官方 RSI**：`main.py` 调 `IterativeSingleHarnessRequest` + `SingleHarnessIterativeOptimizationOrchestrator`，官方完成全量基线、逐案归因、候选生成、目标验证、全量回放与晋升。
5. **审查候选**：看它改了哪个表面（Prompt/Skill）、diff 是否只包含通用规则、有没有把测试答案写进生产提示词。
6. **独立复验**：用留出集对 h0 与最终版本各跑一次，报告逐案变化；无提升时保留基线，不伪造发布。

## 6. 运行

### 前提

- Python 3.11–3.13；
- `openjiuwen==0.1.20`；
- 一个 OpenAI 兼容的真实模型网关。README 的实测使用 DeepSeek 官方 API：执行/分析/裁判为 `deepseek-flash`，优化器角色因规划阶段需要更强的结构化输出，使用同厂商 `deepseek-v4-pro`（通过官方角色覆盖环境变量，不修改代码）。

```bash
python -c "import openjiuwen; print(openjiuwen.__version__)"
export API_BASE="https://api.deepseek.com/v1"
export API_KEY="你的密钥"
export MODEL_NAME="deepseek-flash"
# 可选：单独给优化器角色指定更强模型配置（见下）
export RSI_OPTIMIZATION_MODEL_CONFIG=".cache/model-optimizer.yaml"
```

可选角色覆盖：

| 环境变量 | 用途 |
|---|---|
| `RSI_EVALUATION_MODEL_CONFIG` | 信贷 Agent 执行模型配置 |
| `RSI_ANALYSIS_MODEL_CONFIG` | 失败归因模型配置 |
| `RSI_OPTIMIZATION_MODEL_CONFIG` | 候选生成/优化器模型配置 |
| `RSI_JUDGE_MODEL_CONFIG` | LLM 裁判模型配置 |

每个覆盖文件采用与 `config/model.yaml` 相同的官方结构；例如本次实测的优化器角色覆盖文件：

```yaml
# .cache/model-optimizer.yaml（示例；密钥仍从环境变量展开，不写入文件）
model:
  model_client_config:
    client_provider: openai
    api_key: ${API_KEY}
    api_base: ${API_BASE}
    max_retries: 0
    timeout: 300
  model_request_config:
    model: deepseek-v4-pro
    temperature: 0
```

正式运行前请先确认网关支持工具调用和 JSON 输出。

### 发起演进任务

```bash
cd samples/25_harness_rsi
RSI_TASK_ID=credit-rsi-demo-001 python main.py
```

`main.py` 只做三件事：读取配置、调用官方编排器、展示发布状态与分数。断言、留出集复验、强制提分要求都在 `test.py`。

修改数据、h0 内容或评分协议后必须使用新 `RSI_TASK_ID`；仅当输入指纹不变时才恢复：

```bash
RSI_TASK_ID=credit-rsi-demo-001 RSI_RESUME=1 python main.py
```

### 预算与耗时

6 案单批、单轮迭代；每案 solver + judge 约 20–60 秒，一次基线约 3–8 分钟，完整闭环约 15–30 分钟（取决于网关、候选轮次和重试）。实测一次 published 运行约 22 分钟。

## 7. 验收

### 离线 UT（不调用模型）

```bash
cd <仓库根目录>
python -m unittest tests.test_harness_rsi_sample -v
```

覆盖：标准 Skill 目录与官方加载、`dti.py` 离线契约（含 50.004% 精度边界）、manifest 只声明 Prompt/Skill、数据集 6+4/权重/互斥、config 单批与评分协议、验收规则单测、业务入口不含断言。

### 完整 UT（真实模型）

```bash
export API_BASE=... API_KEY=... MODEL_NAME=...
REQUIRE_IMPROVEMENT=1 python samples/25_harness_rsi/test.py -v
```

完整 UT 会：发起一次真实 RSI → 交叉校验 state/report 与全量回放证据 → 官方复装最终包并检查技能目录完整、脚本可运行 → 用留出集对 h0 和最终版本各跑一次 → 输出版本对照。`REQUIRE_IMPROVEMENT=1` 额外要求官方 `published` 且最佳分高于基线。

如果已经有一次官方运行、只想复验而不想重复花模型费用，可指定既有运行目录（本仓库实测即用这种方式）：

```bash
RSI_ACCEPT_RUN_DIR="samples/25_harness_rsi/runs/credit-rsi-20261106T021056Z" \
REQUIRE_IMPROVEMENT=1 python samples/25_harness_rsi/test.py -v
```

## 8. 实测输出（真实运行记录）

运行时间：2026-11-06 10:10–10:33（Asia/Shanghai）。环境：Python 3.12、`openjiuwen==0.1.20`、DeepSeek 官方 OpenAI 兼容网关；执行/分析/裁判 `deepseek-flash`，优化器角色 `deepseek-v4-pro`。任务 `credit-rsi-20261106T021056Z`。

### 8.1 官方 RSI 结果

```text
发布状态：published
基线分数：51.67 分；最佳分数：100.00 分（满分 100 分）
接受候选：1；analysis 不可用/失败：0
improvement_proof.scope=target_cases, qualified=true
```

逐案同题对比（优化集，100 分制）：

| 案例 | 类型 | 基线 | 最终 | 变化 |
|---|---|---|---|---|
| opt-current-overdue | 保护：当前逾期红线 | 100.00 | 100.00 | 0 |
| opt-eligible-review | 保护：正常进入审批 | 100.00 | 100.00 | 0 |
| opt-mixed-periods | 挑战：周期折算 | 100.00 | 100.00 | 0 |
| opt-duplicate-debt | 挑战：合同重复 | 0.00 | 100.00 | **+100.00** |
| opt-duplicate-transfer | 挑战：汇总重复/旧计划 | 0.00 | 100.00 | **+100.00** |
| opt-superseded-record | 挑战：记录替代 | 10.00 | 100.00 | **+90.00** |

全量平均分：51.67 → 100.00（+48.33 分）。保护案无退化，挑战案 3 笔实质提升。

### 8.2 候选改了什么

官方候选只修改了 `prompt_sections/files/credit_review.md`（Prompt 表面），没有改 Skill、引用文档或脚本，也没有写入任何 case_id、参考答案或评测元信息。新增内容是一段通用的"建议标签判定规则"：

```text
# 建议标签的判定规则（正向映射）

在【审查建议】给出结论前，必须逐条评估上述红线并记录“触发/未触发”及依据，再按该记录选择标签：
- 若存在“当前逾期（未结清）”或“偿债收入比严格大于 50%”任一红线被触发，或输入声明关键资料缺失，则不得给出“建议进入后续审批”……
- 若逐条评估后所有红线均未触发，且输入声明材料清单完整，则【审查建议】必须为最低升级档标签“建议进入后续审批”……
- “建议人工复核”“建议补充材料”仅用于存在被点名的触发红线或声明的资料缺口；对已声明完整的字段残留的不安不构成触发依据。
```

这正是 h0 缺的"可确定性判定 → 建议标签映射"通用规则；候选不是针对某个案例的补丁。

候选包里的 `manifest.json`、`prompt_sections/sections.yaml`、`skills/skills.yaml` 变化属于官方注册表同步（登记候选 Prompt 路径与技能挂载），不是第二处能力改动；验收的路径级差异审计会把它们与 Prompt/Skill 内容变更一并列出。

### 8.3 独立留出集复验（4 案，未参与优化）

由完整 UT 使用官方 `TeamEvaluator` 对 h0 与最终版本分别运行留出集（同一冻结协议、单并发），逐案结果：

| 案例 | h0 基线 | 最终版本 | 变化 |
|---|---|---|---|
| hold-mixed-periods | 100.00 | 100.00 | 0 |
| hold-duplicate-debt | 0.00 | 100.00 | **+100.00** |
| hold-superseded-record | 10.00 | 100.00 | **+90.00** |
| hold-precision-boundary | 100.00 | 100.00 | 0 |
| **平均** | **52.50** | **100.00** | **+47.50** |

留出集上 2 笔同源失败被修复、另外 2 笔保持满分，说明候选的通用规则迁移到了未参与优化的材料；同时本轮没有出现留出集退化。

### 8.4 验收 UT

- 离线 UT：`python -m unittest tests.test_harness_rsi_sample -v`，25 项通过（无模型调用），真实输出见 [offline_test_output.txt](offline_test_output.txt)。
- 完整 UT（真实模型）：`RSI_ACCEPT_RUN_DIR="samples/25_harness_rsi/runs/credit-rsi-20261106T021056Z" REQUIRE_IMPROVEMENT=1 python samples/25_harness_rsi/test.py -v`，26 项通过（25 项离线 + 1 项真实模型：运行证据校验、官方复装、留出集双版本复验），耗时 1254.985 秒；真实输出与摘要见 [test_output.txt](test_output.txt)。
- `main.py` 本次运行的终端输出见 [run_output.txt](run_output.txt)。

### 8.5 证据位置

> `runs/` 是运行产物目录，已在 `.gitignore` 中忽略、不随仓库提交；下面描述的是本机实测证据的结构，重新运行会生成自己的目录。

```text
runs/credit-rsi-20261106T021056Z/
├── single_harness_report.yaml              # baseline/best/published/improvement_proof
├── single_harness_state.yaml               # 候选门禁与可恢复状态
├── evaluations/frozen_baseline/eval_ref.yaml
├── evaluations/e001/b001/attempts/a001/    # 归因、目标验证、全量回放
├── member_optimizations/member_optimization_001/   # plan.yaml、执行结果、候选包
├── published/current/re43e6034/            # 官方发布包（含候选 Prompt）
└── holdout/{baseline,final}/               # 完整 UT 产生的留出集双版本复验
```

## 9. 0.1.20 的可编辑面限制与企业约束

`config/rsi.yaml` 的 `action_group_configs: [prompt, skill]` 表达本实验范围，但 **0.1.20 的 Single Harness 构造器会把内建 action group 白名单扩展为 prompt/skill/tool/rail/config**，不能只凭 YAML 宣称其他面已被原生禁止。

因此本样例同时采取两层约束：

1. 初始包只暴露 Prompt/Skill，不提供 Tool/Rail/Config 编辑目标；
2. `test.py` 对所有候选和最终包做路径级差异审计，发现禁改内容即验收失败；官方生成的空 Tool/Rail 注册表会被识别为"无资源变化"。

这是 **UT 中的事后验收，不是业务入口的发布拦截器，也不是候选执行前的安全沙箱**。企业生产若需要控制面原生、预防式地只开放 Prompt/Skill，应向 agent-core 提 Issue/PR，把权限约束做进框架本体，而不是在样例仓私封装控制面。

## 10. 产物与恢复

产品产物写入 `runs/<task-id>/`；每次 UT 使用新的 `rsi-ut-<时间戳>` 任务名保留真实证据，不复用或覆盖业务运行目录。官方 `published` 只表示候选通过本轮机器门禁，**不等于企业生产批准**；生产发布还必须由信贷业务、风控/合规、Agent 开发者和发布审批人共同签收，核对 state/report、候选 diff、全量回放、留出集与真实冒烟证据，并准备可加载的回滚版本。

`materials/` 保留每类交接物料的空白模板与虚构示例：案例登记、评审规则、失败反馈、工具契约、优化章程、发布审批。模板不是实测输出；真实审批单必须引用真实运行文件。

## 11. 实测中的真实注意点

- **模型角色差异是真实存在的**：本次实测中 `deepseek-flash` 在成员优化器的计划生成阶段曾出现结构化输出不符合 schema、或在证据目录里长时间自循环；把优化器角色覆盖为 `deepseek-v4-pro` 后完成闭环。这是官方角色配置能力，不是样例私有封装；
- **RSI 多阶段 Agent 有随机性**：本次正式记录之前，另有若干次运行因网关连接中断、planner 计划被拒、候选回归非目标案例而 gate 失败。这些失败都保留在 `runs/` 下对应 task-id 的 evidence 中，没有被吞掉；本 README 只把 `published` 的那次作为提分证据；
- **裁判与求解存在随机波动**：单次运行内的同题对比只看同一冻结协议下的分数变化，不宣称小样本统计显著性；无提升时保留基线是合法结果；
- **成员优化工作区会跨 task 复用**：官方成员优化器的 worktree 默认在 `runs/mh/`；`main.py` 在新建任务（非 resume）时会清理该目录，避免上一个任务的 worktree 文件混入候选包。并发运行多个 task 时请自行串行化，否则会互相清理工作区；
- **不要把候选 diff 当生产补丁**：仍需业务/合规审查、回归与审批。
