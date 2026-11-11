# 设计契约 · sample27 右栏团队协作实时信息 + 客户对话富 UI

> 一次性实现契约（本文件是团队内部 spec，不是样例 README）。
> 背景：上一轮原型选型结论 —— **B（多智能体群聊）胜出，但不上客户对话**：
> 客户侧是黑盒，只展示业务结果；团队协作过程放到右侧「团队协作实时信息」，右侧智能体架构图（d3）保持现状不变。
> 同时客户对话的回复要用 UI 卡片组件包装，不再是一段纯文字。

## 目标（必须全部满足）

1. **右侧**：保留现有 d3 智能体架构图（外观、节点内容、连线状态、缩放/适应/节点点击都保持不变）；
   在架构图下方新增「团队协作实时信息」区域，用 B 方案的群聊形态流式展示：leader 汇总/分发 → 专员工具调用与回报 → 主管汇总 → leader 收尾。
2. **左侧客户对话**：不再出现智能体名字、工具名、内部过程；助手回复用结构化 UI 呈现
   （结论卡 + 关键数据 chips + 下一步 + Markdown 详细说明 + A2UI 业务卡片），客户问题用气泡，等待时有输入中提示。
3. **后端事件流**：把现有 `{id,state}` 扩展为带类型的 NDJSON（`agent` / `collab` / `reply` / `answer` / `a2ui` / `error`），
   业务规则、人工审批/签署/放款前置条件、会话隔离、金额校验等**一律不变**。
4. 保持：A2UI 卡片与审批/签署按钮、每页新会话、错误提示、串行演示、最多 100 会话/15 轮等现有行为。
5. 不新增第三方依赖；前端物料沿用 marked / DOMPurify / @a2ui/lit / d3-org-chart。

## 布局

```text
┌───────────────────────────────┬──────────────────────────────────────────────┐
│ 左：客户对话（40%，现有栅格） │ 右：智能体协同网络（60%）                    │
│ header（不变）                │ team-header（文案微调，控件不变）            │
│ 消息流：                      │ legend（不变）                               │
│  · 客户气泡（右对齐）         │ #orgchart（d3，不变，flex:1，min-height≈280）│
│  · 助手消息：                 │ agent-detail（不变）                         │
│     结论卡（reply）           │ ┌ 团队协作实时信息（新增，固定高≈250-280px） ┐  │
│     关键数据 chips            │ │ 头部：标题 + 「内部视图·客户不可见」  │  │
│     下一步提示                │ │ 状态：待命/进行中/已完成/失败          │  │
│     Markdown 详细说明         │ │ 滚动区：群聊气泡 + 工具卡 + handoff   │  │
│     A2UI 业务卡片（按钮）     │ └───────────────────────────────────────┘  │
│ 输入框（不变）                │                                              │
└───────────────────────────────┴──────────────────────────────────────────────┘
```

- 1280×800 下：协作区降到 ≈200px，图表 min-height 降到 ≈240px，一屏不出现整页滚动。
- 客户对话里**禁止**出现：agent id / 中文智能体名 / 工具名 / "正在调用" 等内部信息；等待态只说「正在为您核实…」。

## NDJSON 事件协议（`POST /chat`，一行一个 JSON；服务端顺序保证）

### 1) `agent` —— 驱动右侧架构图节点状态（保持现有语义）

```json
{"type":"agent","agent":"credit_master","state":"working"}
{"type":"agent","agent":"risk_agent","state":"completed"}
{"type":"agent","agent":"pricing_agent","state":"error"}
```

`state ∈ working | completed | error`。每轮开始前前端自己把 10 个节点重置为 idle。

### 2) `collab` —— 右侧「团队协作实时信息」的流式事件

```json
{"type":"collab","seq":1,"ts":1760000000000,"ev":"run_start","question":"客户C1001申请经营贷…"}
{"type":"collab","seq":2,"ts":…,"ev":"agent_start","agent":"credit_master","parent":null,"task":"客户C1001申请经营贷…"}
{"type":"collab","seq":3,"ts":…,"ev":"agent_start","agent":"review_master","parent":"credit_master","task":"请审查团队按顺序核查材料、征信与定价…"}
{"type":"collab","seq":4,"ts":…,"ev":"tool_start","agent":"intake_agent","tool":"check_documents","args":{"customer_id":"C1001"}}
{"type":"collab","seq":5,"ts":…,"ev":"tool_end","agent":"intake_agent","tool":"check_documents","ok":true,"result":"{status=资料齐全}","steps":["受理","审查","审批","签约","放款","贷后"]}
{"type":"collab","seq":6,"ts":…,"ev":"agent_end","agent":"intake_agent","parent":"review_master","ok":true,"output":"受理通过，5 项材料齐全…"}
{"type":"collab","seq":7,"ts":…,"ev":"operator","agent":"credit_master","text":"运行者确认审批批准 → 状态：批准"}
{"type":"collab","seq":8,"ts":…,"ev":"error","agent":"risk_agent","message":"…"}
{"type":"collab","seq":9,"ts":…,"ev":"run_end","status":"completed"}
```

- `seq`/`ts` 由 `CreditWebApp` 统一追加（从 1 递增、毫秒时间戳），`CreditAgents` 只构造 `type/ev/...`。
- `parent` 表示委派来源；`agent_start` 带 parent → 前端画「分发任务 → @子智能体」handoff 气泡；
  `agent_end` 带 parent → 画「回报 → @父智能体」气泡。
- 文本长度：`task` ≤ 240 字符、`output` ≤ 500 字符、`result` ≤ 240 字符（服务端截断，前端不再二次截）。
- `tool_end.steps` 仅当 `tool == submit_plan` 且结果里有 steps 时存在，前端渲染为 6 阶段 chips。
- `run_end.status ∈ completed | error | cancelled`；流中发生异常时先 `error` 再 `run_end`。

### 3) `reply` —— 客户侧结构化回复卡（业务事实，非模型自由文本）

```json
{"type":"reply","data":{"cases":[
  {"customer_id":"C1001","status":"待人工审批","tone":"warn",
   "facts":[{"label":"申请金额","value":"¥800,000"},{"label":"还款期限","value":"36 期"},
            {"label":"年化利率","value":"3.8%"},{"label":"每月还款","value":"¥23,548.08"}],
   "next_step":"等待审批人员确认（演示角色），批准后可继续准备合同"}
]}}
```

- `tone ∈ ok | warn | danger | info`。
- `cases` 为空数组 = 本轮没有业务申请（纯咨询/追问），前端退化为：Markdown + 普通回复壳。
- 一个会话可能有多个客户案例，前端每个 case 渲染一张小结论卡。

### 4) `answer` / `a2ui` / `error`

```json
{"type":"answer","text":"Markdown 文本（模型最终回答）"}
{"type":"a2ui","messages":[…与现有 CreditSurfaces.event(domain).messages 相同…]}
{"type":"error","message":"处理失败：…"}
```

**顺序**：`run_start` →（`collab`/`agent` 流式）→ `run_end` → `reply` → `answer` → `a2ui`（模型分支）；
运行者确认分支：`collab run_start` → `collab operator` → `collab run_end` → `reply` → `answer` → `a2ui`。
异常：`collab error` → `collab run_end(status=error)` → `error`，不再发 answer/reply/a2ui。

## 服务端实现要点（java/src/main/java/）

### `CreditAgents.java`
- `run(String query, CreditDomainService domain, java.util.function.Consumer<Map<String,Object>> events)`（替换现有 `BiConsumer<String,String>`）。
- 回调注册（沿用官方 0.1.17 API）：
  - `BEFORE_INVOKE`：`InvokeInputs.getQuery()` → `agent`(working) + `collab agent_start(task)`；
  - `AFTER_INVOKE`：`InvokeInputs.getResult()` → `agent`(completed/error) + `collab agent_end(output)`；
  - `BEFORE_TOOL_CALL` / `AFTER_TOOL_CALL`：`ToolCallInputs.getToolName()/getToolArgs()/getToolResult()` → `collab tool_start/tool_end`（工具名去掉 `_<runSuffix>`）；
  - `ON_MODEL_EXCEPTION` / `ON_TOOL_EXCEPTION` → `collab error`。
- 静态父子关系表（与 team.addAgent 一致）：`credit_master→null`；`review_master/execution_master/service_agent/postloan_agent→credit_master`；
  `intake/risk/pricing→review_master`；`contract/disbursement→execution_master`。
- 输出提取：`Map.get("output")` 优先，否则 `String.valueOf`；空值用「（无文本输出）」。
- 业务、提示词、工具注册、资源释放逻辑不动。
- 集成测试 `CreditModelIntegrationTest` 只改事件回调签名适配（收集 `type=agent` 的 agent 集合），断言内容不变。

### `CreditWebApp.java`
- `emit` 改成 `Consumer<Map<String,Object>>`，写行时补 `seq`（每请求从 1 开始）与 `ts`，同步 flush。
- 模型分支：发 `run_start` → `CreditAgents.run(..., emit)` → `run_end(completed)` → `reply` → `answer` → `a2ui`；异常时 `collab error`+`run_end(error)`+`error`。
- 运行者确认分支：`run_start` → `operator` → `run_end` → `reply` → `answer` → `a2ui`；异常逻辑沿用（X-Decision 的 400 语义不变）。
- `CreditSurfaces.event(domain)` 方法签名/返回值保持不变（测试兼容），发 a2ui 时包成 `{"type":"a2ui","messages":…}`。

### `CreditSurfaces.java`
- 新增 `static Map<String,Object> reply(CreditDomainService domain)`，只读 `domain.snapshots()`，产出上面的 `cases`：
  - `status` 原样；`tone` 映射：`批准/合同待签署/已签署并授权放款/已放款→ok`，`待人工审批/待补件→warn`，`拒绝→danger`，其他→info；
  - `facts`：从 `quote` 取 amount/months/annual_rate/monthly_payment，格式 `¥800,000` / `36 期` / `3.8%` / `¥23,548.08`（千分位）；
  - `next_step` 按 status 映射（待人工审批→等待审批人员确认…；批准→可以准备合同；合同待签署→等待客户签署及放款授权；
    已签署并授权放款→可以继续办理放款；已放款→可查询还款安排；拒绝→申请已被拒绝；待补件→请补充经营流水与购销合同；其他→null 省略）。
- 测试：`CreditSurfacesTest` 增加 reply 断言（C1001 走完审查 → 1 个 case、tone=warn、facts 含 `¥800,000`、status=待人工审批）。

## 前端实现要点（web/src/）

### 文件所有权
- `main.js`、`style.css`：web-chat。
- `collab.js`、`collab.css`：web-collab。`collab.js` 里 `import './collab.css';`。
- `index.html` 一般不动；构建产物 `../java/src/main/resources/ui/` 由 Lead 统一 `npm run build`。
- 禁止改动 `../prototype-stream/`（已归档原型）与 java 目录（后端同学负责）。

### `collab.js` 模块接口（web-collab 导出，web-chat 调用）

```js
import {createCollabStream} from './collab.js';

const collab = createCollabStream({
  streamEl: document.querySelector('#collab-stream'),   // 滚动容器
  statusEl: document.querySelector('#collab-status'),   // 头部状态 chip
  roles: ROLES                                           // 见下（main.js 定义）
});
collab.beginRun(question);        // 新请求开始时调用：清空上一轮 + 状态「进行中」
collab.handle(event);             // 每条 NDJSON 事件原样传入；只处理 type==='collab'
collab.endRun(status, message);   // 流结束兜底（服务端已发 run_end 时幂等）；status: 'completed'|'error'
collab.reset();                   // 新会话/重置
```

`ROLES`（main.js 定义并传给 collab；保持现有 10 个 agent）：
```js
[{id:'credit_master', parentId:null, title:'信贷超级智能体', subtitle:'全局规划 · 任务调度', icon:'◈', color:'#c7000b'}, …]
```

### `collab.js` 渲染要求（B 方案群聊，右栏窄容器 560–760px 宽可用）

- 气泡身份：头像块（icon + agent color）+ 短名 + 角色标签 + 时间；leader 消息靠右（红色系），专员靠左，
  `review_master` 汇总用琥珀色高亮；handoff 气泡显示「→ @审查主管 分发任务」+ 任务文本。
- 工具卡：嵌在气泡内的浅色卡：`check_documents()` 工具名 chip + 入参 chips；执行中显示脉冲，结束后显示结果摘要 + ✓/✗；
  `submit_plan` 的 `steps` 渲染为 6 阶段 chips（受理/审查/审批/签约/放款/贷后，本轮 3 个蓝色、后 3 个灰）。
- 状态：`agent_start` 后该 agent 处于「工作中」，`agent_end` 后「已回报」；头部状态 `待命/进行中/已完成/失败`。
- 自动滚动：用户停在底部时跟随新事件；用户上翻后不抢滚动，出现「↓ 回到最新」小按钮。
- 空态：`发起问题后，团队协作过程会在这里实时直播（客户不可见）`。
- 稳定渲染：事件按到达顺序 append，不做全量重绘；每个 agent 复用同一气泡节点；无第三方依赖、无内联定时动画（CSS 动画可）。
- 视觉：沿用页面白底/招商红 #c7000b/系统字体，紧凑但可读；内部视图标签「客户不可见」常显。

### `main.js` 重写要求（可读性优先，不用压缩成一行）

保留现有能力：d3 组织图（节点内容/连线状态/缩放/适应/点击详情不变）、A2UI Lit 0.15 渲染与审批按钮、
Markdown + DOMPurify、会话 id、X-Conversation/X-Decision、busy 锁、旧卡片失效、错误展示、新建会话、建议问题。

新客户对话渲染（左侧，40%）：
- 客户消息：右对齐气泡 + 「我」；助手消息：左对齐，头像 ◈，标题「AI 信贷顾问」+ 小徽标 `AI` + 时间。
- 助手消息 body 分区（按到达顺序填充）：
  1. `reply` → 结构化结论卡：每个 case = 头部「C1001 · 待人工审批」+ 状态 chip（tone 配色）+ facts chips + 「下一步：…」；
  2. `answer` → `.markdown` 详细说明（marked + DOMPurify），放进回复卡内的浅色「详细说明」区；
  3. `a2ui` → 业务卡片挂在消息内（caption「业务办理卡片 · A2UI」），审批/签署按钮交互沿用；
- 等待态：请求发出后显示助手「正在为您核实…」三点动画占位，收到首个结果事件后移除。
- 错误：红色提示卡（复用现有错误样式），仍释放 busy 锁。
- 输入框上方提示文案保持生产语气，不出现「原型」。

## 构建与自检命令

```bash
# 前端（web/）
npm test
npm run build            # 产物写到 ../java/src/main/resources/ui（Lead 最后统一执行）

# 后端（java/）
export JAVA_HOME=/tmp/java-tools/jdk-17.0.21+9/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
/tmp/java-tools/apache-maven-3.9.9/bin/mvn -Dmaven.repo.local=/tmp/java-tools/m2 -q test
# 注意：不要跑 -Preal-model（本环境没有 API_KEY）；验收用单测 + 本地脚本化事件流的 UI 截图
```

## 验收分工

- 后端同学：`mvn test` 全绿；`CreditSurfacesTest` 覆盖 reply；编译通过；不得改业务规则/README。
- 前端同学：`node --check`/`npm test` 通过；用本地 stub（Lead 提供 `/tmp/credit-stub/server.mjs`，带 `/chat` 假事件流）
  启动页面截图自检：客户对话无内部信息、右侧架构图与协作直播同时可见、collab 气泡/工具卡/step chips 正常、无横向溢出。
- Lead：集成、最终 build、README 更新、before/after 截图、CDP 验收（switching/审批按钮/错误态/无 console error）。

---

## 实现状态（2026-11-11）

已按本契约落地到真实页面与后端，最终验证记录：

- 后端：`CreditAgents`（Consumer 事件 + 6 个回调 + 父子表 + 截断 + plan steps）、`CreditWebApp`（EventWriter seq/ts + 三分支事件顺序）、
  `CreditSurfaces.reply` 全部实现；`mvn test` 11 个用例通过（integration 默认排除），`mvn -DskipTests package` 通过。
- 前端：`main.js` / `style.css` 重写客户对话与布局，`collab.js` / `collab.css` 实现右栏协作直播；d3 架构图配置、节点、
  连线、缩放/适应/节点点击保持原样；构建产物已更新到 `java/src/main/resources/ui/`。
- 端到端：用脚本化 NDJSON stub（仅验证用、不入仓库）在 headless Chrome 验证 1440×900 / 1280×800：
  零横向溢出、零 console error、客户对话无内部信息、协作直播完整一轮、审批 → operator 事件 → 旧卡片失效、错误态正常。

实现过程中对契约的 4 处补充（不改变协议字段）：

1. **委派工具事件过滤**：层级团队把「调用子智能体」实现为工具（tool 名 = 子 agent id），服务端跳过这类
   `tool_start/tool_end`，避免与 handoff 气泡重复；业务工具事件不受影响。
2. **A2UI 卡片数值格式化**：`CreditSurfaces.event` 的金额/利率/月供改为 `¥800,000` / `3.8%` / `¥23,548.08`，
   与 `reply` 卡一致（`CreditSurfacesTest` 断言同步更新）。
3. **运行者确认分支补一个 `agent(credit_master, completed)`**：让右侧架构图与协作直播「已完成」状态一致。
4. **协作直播空态文案**不再重复 header 的「内部视图 · 客户不可见」标签。

验证限制：本机无 `API_KEY`，未执行 `mvn -Preal-model test` 与真实模型浏览器回归；真实模型端到端需配置密钥后重跑。
