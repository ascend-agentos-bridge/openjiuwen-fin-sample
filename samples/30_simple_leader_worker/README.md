# Simple Leader-Worker 动态编排验证

一个极简的 Leader-Teammate 多 Agent 示例：**角色定义写入配置文件，Leader 根据成员能力自主规划任务 DAG，执行过程中根据回报动态调整。**

## 场景

1 个 Leader + 5 个预置研发成员（需求→设计→编码→测试→评审）。输入一句需求，Leader 自主完成：

1. `list_members` 查看可用成员及能力
2. `create_task` 根据需求和成员能力**动态生成任务 DAG**（串行/并行由 Leader 自己判断）
3. `send_message(to="*")` 广播启动指令（成员靠此懒启动）
4. 等待成员回报，遇到 `[BLOCKED]`/`[FAILED]`/`[REVIEW_REJECT]` 时**动态追加返工任务**
5. 全部完成后 `send_message(to="user")` 发交付汇总

### Leader 可能生成的任务 DAG

```
T1 (req_analyst) ──→ T2 (architect) ──→ T3 (developer) ──→ T5 (reviewer)
                                    └──→ T4 (qa_engineer) ──↗
```

- 串行：T1 → T2（设计依赖需求）
- 并行：T3 ‖ T4（编码和测试用例都只依赖设计，Leader 自主判断可并行）
- 汇聚：T5 等 T3 + T4 都完成

**不同需求会生成不同 DAG**——这是动态编排，不是预定义流程执行。

## 前置条件

```bash
# 核心包必须先在本地仓库
mvn -pl core install -DskipTests

# 准备模型密钥（默认接 DeepSeek，从环境变量取）
$env:DEEPSEEK_API_KEY_OPENJIUWEN = "sk-xxxx"      # Windows PowerShell
export DEEPSEEK_API_KEY_OPENJIUWEN=sk-xxxx          # Linux / macOS
```

`apiconfig.json` 需要 `API_BASE`、`MODEL_PROVIDER`、`MODEL_NAME` 三项，以及一种密钥来源：
`API_KEY`（直接写）或 `API_KEY_ENV`（环境变量名）。默认配置从环境变量 `DEEPSEEK_API_KEY_OPENJIUWEN` 取密钥，不落盘。

换模型只改一行：

```json
"MODEL_NAME": "deepseek-flash"
```

## 运行

```bash
# 交互问答模式（启动后输入需求，输入 exit 退出）
mvn -f examples/simple_leader_worker/pom.xml compile exec:java

# 只跑纯单测（不需要 API Key）
mvn -f examples/simple_leader_worker/pom.xml test

# 只验证装配（配置/模型绑定/花名册），不调用 LLM
$env:DEEPSEEK_API_KEY_OPENJIUWEN = "dummy"
mvn -f examples/simple_leader_worker/pom.xml compile exec:java "-Ddemo.assembleOnly=true"
```

启动后进入交互问答循环：在提示符处输入需求并回车，Leader-Worker 团队执行完毕后打印本轮摘要，然后等待下一次输入。输入 `exit` 退出。

自检模式会打印 5 个预配成员的花名册（状态应为 `unstarted`，等待 Leader 的 `send_message` 拉起），不产生任何模型调用。

### 参数

| 参数 | 默认 | 说明 |
|---|---|---|
| `demo.timeout.seconds` | 300 | 单轮硬超时 |
| `demo.assembleOnly` | false | 只装配并打印花名册，不调用 LLM |

## 输出示例

```text
════════════ Leader-Worker 动态编排 ════════════
团队   : demo_team（Leader-Worker 动态编排验证）
Leader : leader / 负责人
成员   : 5 个
  - req_analyst / 需求分析师 · 需求分析师
  - architect / 架构设计师 · 架构设计师
  - developer / 开发工程师 · 开发工程师
  - qa_engineer / 测试工程师 · 测试工程师
  - reviewer / 代码评审员 · 代码评审员
模型   : DeepSeek / deepseek-flash @ https://api.deepseek.com/v1
模式   : 交互问答（输入需求后回车执行，输入 exit 退出）
════════════════════════════════════════════════
[装配] 成员预配完成: 5/5 → [req_analyst, architect, developer, qa_engineer, reviewer]

请输入需求（输入 exit 退出）: 请为一个 Java CLI 待办事项管理器完成全流程交付

>>> 第 1 轮需求: 请为一个 Java CLI 待办事项管理器完成全流程交付
>>> 超时: 300s

[21:37:55.301][成员] leader 状态: ready → busy
[leader] 我先查看可用成员...
  ● list_members()
  ⎿ ok: member_name=req_analyst ... member_name=reviewer
[leader] 根据成员能力创建任务...
  ● create_task(tasks=[{T1...}, {T2...}, {T3...}, {T4...}, {T5...}])
  ⎿ ok: Created 5
  ● send_message(to="*", content="各成员注意...")
[21:38:02.118][广播] leader → 全员

[21:38:05.221][成员] req_analyst 状态: unstarted → ready
[21:38:05.230][成员] architect 状态: unstarted → ready
[21:38:05.235][成员] developer 状态: unstarted → ready
[21:38:05.240][成员] qa_engineer 状态: unstarted → ready
[21:38:05.245][成员] reviewer 状态: unstarted → ready

[21:38:30.004][任务] T1 已完成
[21:38:31.112][任务] T2 依赖满足，已解锁
[21:39:15.882][消息] req_analyst → leader
[21:39:20.004][任务] T2 已完成
...

[心跳] 运行 120s · 本轮任务 5 个（完成 3 / 进行中 2 / 阻塞 0 / 待领取 0）

════════════ 本轮任务板 ════════════
  T1     已完成   req_analyst    需求分析与验收标准
  T2     已完成   architect      方案设计与接口契约
  T3     已完成   developer      编码实现与自测
  T4     已完成   qa_engineer    测试用例编写
  T5     已完成   reviewer       代码评审与质量门禁
【交付汇总】
  ... Leader 通过 send_message(to="user") 发出的最终汇总 ...
═══════════════════════════════

请输入需求（输入 exit 退出）: exit
[退出] 再见
```

## 目录结构

```
src/main/java/examples/simple_leader_worker/
├── SimpleDemo.java          # main：读配置 → 装配 → 交互问答循环 → 回收
├── TeamConfig.java          # record：解析 team-config.json（纯领域，零框架依赖）
├── TeamAssembler.java       # 框架适配：Builder + spawnMember + Briefing（封装4个框架约束）
└── ConsolePrinter.java      # 事件打印 + 任务板摘要（含本轮摘要）
src/main/resources/
├── team-config.json         # 角色配置（团队名 + Leader 人设 + 5 成员能力）
├── apiconfig.json           # 模型配置
└── logback.xml              # 日志配置
src/test/java/.../
└── TeamConfigTest.java      # 6 个测试（配置解析 + briefing 拼装，不需要 API Key）
```

## 配置文件说明

### team-config.json

角色定义文件，只定义「谁」，不定义「做什么」——任务由 Leader 运行时自主规划：

| 字段 | 说明 |
|---|---|
| `teamName` | 团队名（也是 `~/.openjiuwen/.agent_teams/` 下的目录名） |
| `leader.name` | Leader 成员名（不能用 `team_leader`/`human_agent`/`user` 等保留名） |
| `leader.persona` | Leader 人设：决策流程（规划→派发→等待→调整→收尾） |
| `members[].name` | 成员名（路由主键，不可重复） |
| `members[].description` | 能力描述——同时作为成员 persona 和 **Leader 选人的依据** |
| `members[].rolePrompt` | 成员首条消息：职责、产出文件、完成与回报协议 |
| `defaultQuery` | 默认用户需求（保留字段，交互模式下不使用） |

### apiconfig.json

模型连接配置：

| 字段 | 说明 |
|---|---|
| `API_BASE` | 模型服务地址 |
| `MODEL_PROVIDER` | 供应商标识（DeepSeek / OpenAI 等） |
| `MODEL_NAME` | 模型名（deepseek-flash / deepseek-chat 等） |
| `API_KEY_ENV` | 环境变量名（密钥不落盘，推荐） |
| `API_KEY` | 直接写密钥（或 `${ENV:变量名}` 展开） |
| `LLM_SSL_VERIFY` | 是否校验 TLS 证书（默认 true） |

## 框架核心类与方法说明

本示例基于 openJiuwen AgentTeams 框架（`com.openjiuwen.agentteams`，位于 `core` 模块）。以下是支撑 Leader-Worker 模式的核心类、方法和执行过程。

### 核心类一览

所有框架类位于 `core/src/main/java/com/openjiuwen/agentteams/`：

| 类 | 路径 | 职责 |
|---|---|---|
| `LeaderTeammateAgentTeam` | `LeaderTeammateAgentTeam.java` | 团队门面与 Builder：装配团队、获取 Leader、销毁团队 |
| `TeamAgent` | `agent/TeamAgent.java` | Agent 运行时：ReAct 循环、流式输出、事件发布、输入注入 |
| `TeamBackend` | `tools/TeamBackend.java` | 数据与消息枢纽：成员管理、任务管理、消息管理的统一入口 |
| `TeamTools` | `tools/TeamTools.java` | 工具注册中心：13 个工具类、角色权限过滤 |
| `TeamTaskManager` | `tools/TeamTaskManager.java` | 任务管理器：创建、领取、完成、依赖解锁、状态机 |
| `TeamMessageManager` | `tools/TeamMessageManager.java` | 消息管理器：单播、广播、事件发布 |
| `TeamMonitor` | `monitor/TeamMonitor.java` | 监控器：查询任务/消息/成员快照 |
| `TeamEvent` | `schema/events/TeamEvent.java` | 事件类型常量（25 种） |
| `EventMessage` | `schema/events/EventMessage.java` | 事件消息体：eventType + payload |
| `ModelPoolEntry` | `schema/team/ModelPoolEntry.java` | 模型池条目：模型连接参数与推理配置 |
| `TeamRole` | `schema/team/TeamRole.java` | 角色枚举：LEADER / MEMBER / HUMAN_AGENT |

### 团队装配

#### LeaderTeammateAgentTeam（门面 + Builder）

```java
// core/.../LeaderTeammateAgentTeam.java

// Builder 装配团队（本示例的 TeamAssembler.buildTeam 调用）
LeaderTeammateAgentTeam.builder()
    .teamName("demo_team")
    .description("...")
    .lifecycle(LIFECYCLE_PERSISTENT)       // persistent：跑完保留任务板
    .spawnMode(SPAWN_MODE_INPROCESS)       // inprocess：单进程
    .transport(TRANSPORT_INPROCESS)
    .storage(STORAGE_MEMORY)               // memory：不落盘
    .teamMode(TEAM_MODE_HYBRID)            // hybrid：保留 spawn_member 工具
    .leaderMemberName("leader")
    .leaderPersona("你是团队负责人...")
    .modelPool(List.of(entry))             // 模型注入唯一入口
    .modelPoolStrategy("round_robin")
    .build();

// 获取 Leader 运行时
TeamAgent leader = team.agent();           // line 194

// 销毁团队
team.destroyTeam(true);                    // line 452
```

#### TeamBackend（数据与消息枢纽）

```java
// core/.../tools/TeamBackend.java

// 预配成员（本示例的 TeamAssembler.provisionMembers 调用）
backend.spawnMember(SpawnMemberParams.builder()
    .memberName("req_analyst")
    .displayName("需求分析师")
    .agentCard(AgentCard.builder()...)
    .role(TeamRole.MEMBER)
    .prompt("角色指令...")
    .allocation(new Allocation(entry, 0))  // 绑定模型
    .build());                              // line 319，返回 CompletableFuture<Boolean>

// 查询成员列表（list_members 工具调用）
backend.listMembers();                      // line 571，返回 List<TeamMember>

// 拉起所有未启动成员（send_message 工具内部调用）
backend.startupAllUnstarted();              // line 599，返回 List<String>（已启动的成员名）

// 获取子管理器
backend.getTaskManager();                   // line 1349 → TeamTaskManager
backend.getMessageManager();                // line 1340 → TeamMessageManager
backend.getMemberName();                    // line 1385 → 当前成员名
```

### 工具体系

#### 工具注册与角色权限（TeamTools.java:150-179）

框架注册 13 个工具，按角色过滤：

```java
// Leader 专属（LEADER_ONLY_TOOLS）
"build_team"  "clean_team"  "spawn_member"  "shutdown_member"
"approve_plan"  "approve_tool"  "list_members"  "create_task"  "update_task"

// Member 专属（MEMBER_ONLY_TOOLS）
"claim_task"  "submit_plan"  "enter_worktree"  "exit_worktree"

// 共享（SHARED_TOOLS）
"view_task"  "send_message"  "workspace_meta"
```

Leader 拿 `LEADER_TOOLS = LEADER_ONLY ∪ SHARED`（11 个），Member 拿 `MEMBER_TOOLS = MEMBER_ONLY ∪ SHARED`（7 个）。

#### 核心工具源码

**`list_members`**（`ListMembersTool`，TeamTools.java:891）

```java
// 无参数。调用 backend.listMembers()，返回每个成员的 member_name/display_name/status/role
ToolOutput invoke(...) {
    List<Map<String, Object>> members = backend.listMembers().stream()
        .map(member -> Map.of(
            "member_name", member.getMemberName(),
            "display_name", member.getDisplayName(),
            "status", member.getStatus().value(),
            "role", member.getRole().name().toLowerCase()))
        .toList();
    return isOk(Map.of("members", members, "count", members.size()));
}
```

Leader 调此工具看到「有哪些成员、各自能力、当前状态」，作为规划任务的依据。

**`create_task`**（`TaskCreateTool`，TeamTools.java:956）

```java
// 参数：tasks 数组，每个任务含 title/content/task_id/assignee/dependencies
ToolOutput invoke(...) {
    List<Map<String, Object>> taskSpecs = ...;  // 从 inputs 解析
    List<TeamTask> created = taskManager.addBatch(taskSpecs).join();
    return isOk(Map.of("tasks", created.stream().map(TeamTools::taskBrief).toList(),
                       "count", created.size()));
}
```

LLM 一次调用可创建多个任务，每个任务可指定 `assignee`（分配给谁）和 `dependencies`（依赖哪些任务）。

**`claim_task`**（`ClaimTaskTool`，TeamTools.java:1341）

```java
// 参数：task_id + status（claimed 或 completed）
ToolOutput invoke(...) {
    String callerMember = backend.getMemberName();  // 当前调用者
    TeamTask task = taskManager.get(taskId).orElseThrow();

    // 权限检查：assignee 不为空且不是自己 → 拒绝
    if (assignee != null && !assignee.equals(callerMember)) {
        return error("Task is assigned to " + assignee + "; you cannot claim it.");
    }

    if ("claimed".equals(status)) {
        result = taskManager.claimResult(taskId).join();    // pending → claimed
    } else if ("completed".equals(status)) {
        result = taskManager.completeResult(taskId).join(); // claimed → completed
    }
}
```

**`send_message`**（`SendMessageTool`，TeamTools.java:1436）

```java
// 参数：to（成员名 / "*" / "user"）+ content + summary
ToolOutput invoke(...) {
    // ★ 关键：发送前自动拉起所有 UNSTARTED 成员（懒启动）
    List<String> started = backend.startupAllUnstarted();

    // 广播（to="*"）→ broadcastMessage
    // 单播          → sendMessage
    String messageId = "*".equals(to)
        ? backend.getMessageManager().broadcastMessage(content).join()
        : backend.getMessageManager().sendMessage(content, to).join();
}
```

`backend.startupAllUnstarted()` 是「成员靠 send_message 懒启动」的根源——每次 `send_message` 都会检查并拉起未启动的成员。

### 任务管理器（TeamTaskManager）

#### 任务状态机

```
              create_task
                  ↓
               pending ←──── 初始状态
              ↙       ↘
        claim_task    依赖未满足
        (claimed)       ↓
           ↓           blocked
     claim_task     (上游完成后自动解锁)
    (completed)         ↓
           ↓          pending
        completed
```

#### 核心方法

```java
// core/.../tools/TeamTaskManager.java

// 批量创建任务（含依赖关系）
CompletableFuture<List<TeamTask>> addBatch(List<Map<String, Object>> tasks);  // line 326
// 内部链式调用 add(title, content, taskId, dependencies, assignee)
// 有 dependencies 的任务初始状态为 blocked，上游完成后自动解锁

// 认领任务：pending → claimed
CompletableFuture<TaskOpResult> claimResult(String taskId);  // line 404
// 规则：
//   - 只能 claim pending 状态的任务
//   - 防囤积：一个成员同时只能持有 1 个 claimed 任务
//   - 如果 assignee 已指定且不是自己 → 拒绝
//   - 成功后发布 TASK_CLAIMED 事件

// 完成任务：claimed → completed
CompletableFuture<TaskOpResult> completeResult(String taskId);  // line 470
// 规则：
//   - 只能 complete 自己 claimed 的任务
//   - 成功后发布 TASK_COMPLETED 事件
//   - 自动检查下游任务的依赖是否全部满足，满足则解锁并发布 TASK_UNBLOCKED 事件

// 查询可领取的任务
List<TeamTask> getClaimableTasks();  // line 1637
```

### 消息管理器（TeamMessageManager）

```java
// core/.../tools/TeamMessageManager.java

// 单播消息
CompletableFuture<String> sendMessage(String content, String toMemberName);  // line 101
// 发布 MESSAGE 事件

// 广播消息
CompletableFuture<String> broadcastMessage(String content);  // line 159
// 发布 BROADCAST 事件
```

消息持久化在 TeamDatabase（memory 或 jdbc），成员通过轮询自己的消息队列收取。

### Agent 运行时（TeamAgent）

```java
// core/.../agent/TeamAgent.java

// 驱动 Leader（传入首条 query，返回流式输出）
Iterator<Object> stream(Map<String, Object> inputs, Object session);  // line 306
// 内部启动 ReAct 循环：LLM 推理 → 工具调用 → 结果反馈 → 再推理...

// 注入输入（外部向 Leader 追加指令，如兜底调整）
void deliverInput(String content);  // line 348

// 注册事件监听器
void addEventListener(Object handler);  // line 557
// handler 需实现 Consumer<EventMessage>

// 取消当前轮次
void cancelAgent();  // line 576

// 获取后端
TeamBackend getTeamBackend();  // 获取数据/消息/任务管理器
```

### 监控器（TeamMonitor）

```java
// core/.../monitor/TeamMonitor.java

// 创建监控器
static TeamMonitor createMonitor(TeamAgent teamAgent);  // line 85

// 快照查询（本示例的 ConsolePrinter 和 SimpleDemo 调用）
List<TaskInfo> getTasks();                              // line 228
List<TaskInfo> getTasks(String status);                 // line 238
List<MessageInfo> getMessages();                        // line 249
List<MessageInfo> getMessages(String to, String from);  // line 260
List<MemberInfo> getMembers();                          // line 196
```

### 事件系统（TeamEvent）

框架定义 25 种事件类型（`schema/events/TeamEvent.java`），本示例关注的核心事件：

| 事件类型 | 常量 | 触发时机 | ConsolePrinter 打印 |
|---|---|---|---|
| `task_created` | `TASK_CREATED` | `create_task` 创建任务后 | `[任务] T1 已创建 → req_analyst` |
| `task_claimed` | `TASK_CLAIMED` | `claim_task(claimed)` 后 | `[任务] T1 被领取` / `预分配给 ...` |
| `task_completed` | `TASK_COMPLETED` | `claim_task(completed)` 后 | `[任务] T1 已完成` |
| `task_unblocked` | `TASK_UNBLOCKED` | 依赖满足，自动解锁 | `[任务] T2 依赖满足，已解锁` |
| `task_cancelled` | `TASK_CANCELLED` | `update_task(status=cancelled)` | `[任务] T1 已取消` |
| `message` | `MESSAGE` | `send_message(to=成员)` | `[消息] req_analyst → leader` |
| `broadcast` | `BROADCAST` | `send_message(to="*")` | `[广播] leader → 全员` |
| `member_spawned` | `MEMBER_SPAWNED` | `spawn_member` 后 | `[成员] req_analyst 就位` |
| `member_status_changed` | `MEMBER_STATUS_CHANGED` | unstarted→ready / ready→busy | `[成员] leader 状态: ready → busy` |
| `team_completed` | `TEAM_COMPLETED` | 所有任务终态 | `[完成] 团队任务全部结束` |

### 完整执行过程

以下是一次完整运行中，框架内部的方法调用链：

```
┌─ 装配阶段（SimpleDemo → TeamAssembler → 框架）─────────────────────────┐
│                                                                        │
│  Runner.start().join()                    启动框架运行时                  │
│  TeamAssembler.buildModelEntry(model)     → ModelPoolEntry              │
│  TeamAssembler.buildTeam(config, entry)   → LeaderTeammateAgentTeam     │
│    └ LeaderTeammateAgentTeam.builder()...build()                       │
│       └ 内部创建 TeamBackend（含 TeamDatabase + TeamTaskManager        │
│         + TeamMessageManager + Messager）                               │
│       └ 内部创建 TeamAgent（Leader，注册 LEADER_TOOLS）                 │
│  team.agent()                             → TeamAgent (Leader)          │
│  TeamAssembler.provisionMembers(leader)   → 循环 spawnMember            │
│    └ backend.spawnMember(params).join() × 5                            │
│       └ 每次写入成员记录 + 模型引用 + 角色指令                            │
│       └ 发布 MEMBER_SPAWNED 事件                                        │
│       └ 成员状态 = UNSTARTED（等待 send_message 拉起）                   │
│  TeamMonitor.createMonitor(leader)       → TeamMonitor                 │
│  monitor.start()                          启动事件监听                   │
│  leader.addEventListener(printer)        注册 ConsolePrinter            │
│                                                                        │
└────────────────────────────────────────────────────────────────────────┘

┌─ Leader 规划阶段（leader.stream → ReAct 循环）──────────────────────────┐
│                                                                        │
│  TeamAssembler.composeFirstQuery()       拼装首条 query                  │
│    （人设 + 花名册 + 硬性规则 + 用户需求）                                 │
│  leader.stream({query: briefing}, sessionId)                            │
│    └ TeamAgent 启动 ReAct 循环：                                        │
│       ┌ LLM 推理（DeepSeek API 调用）                                   │
│       │  ← 输入：首条 query + LEADER_TOOLS 的 schema                    │
│       │  → 输出：决定调用 list_members                                   │
│       │                                                                │
│       ├ 工具调用：list_members()                                        │
│       │  └ ListMembersTool.invoke()                                    │
│       │  └ backend.listMembers() → 5 个成员信息                         │
│       │  └ 返回给 LLM：member_name + display_name + status              │
│       │                                                                │
│       ├ LLM 推理（第二轮）                                              │
│       │  → 输出：决定调用 create_task，生成 T1..T5 DAG                   │
│       │                                                                │
│       ├ 工具调用：create_task(tasks=[T1..T5])                           │
│       │  └ TaskCreateTool.invoke()                                     │
│       │  └ taskManager.addBatch(taskSpecs).join()                      │
│       │     └ add(T1) → pending                                        │
│       │     └ add(T2, deps=[T1]) → blocked（T1 未完成）                 │
│       │     └ add(T3, deps=[T2]) → blocked                             │
│       │     └ add(T4, deps=[T2]) → blocked                             │
│       │     └ add(T5, deps=[T3,T4]) → blocked                          │
│       │  └ 发布 TASK_CREATED × 5 事件                                   │
│       │  └ ConsolePrinter 打印：[任务] T1 已创建 → req_analyst ...      │
│       │                                                                │
│       ├ LLM 推理（第三轮）                                              │
│       │  → 输出：决定调用 send_message(to="*")                          │
│       │                                                                │
│       ├ 工具调用：send_message(to="*", content="...")                   │
│       │  └ SendMessageTool.invoke()                                    │
│       │  └ ★ backend.startupAllUnstarted()                             │
│       │     └ 5 个成员 UNSTARTED → READY（懒启动！）                     │
│       │     └ 发布 MEMBER_STATUS_CHANGED × 5 事件                       │
│       │  └ messageManager.broadcastMessage(content)                    │
│       │     └ 发布 BROADCAST 事件                                       │
│       │  └ ConsolePrinter 打印：[广播] leader → 全员                    │
│       │     [成员] req_analyst 状态: unstarted → ready ...              │
│       │                                                                │
│       └ LLM 推理（第四轮）：无更多工具调用，输出等待状态                   │
│          └ 流自动关闭（requestCloseStreamAfterCurrentRound）            │
│                                                                        │
└────────────────────────────────────────────────────────────────────────┘

┌─ 成员执行阶段（每个成员独立 ReAct 循环）─────────────────────────────────┐
│                                                                        │
│  以 req_analyst 执行 T1 为例：                                          │
│                                                                        │
│  成员收到广播消息 → 框架拉起成员的 ReAct 循环                              │
│    ┌ LLM 推理                                                         │
│       │  ← 输入：角色指令 + 广播消息内容 + MEMBER_TOOLS 的 schema        │
│       │  → 输出：决定 claim_task(T1, "claimed")                        │
│       │                                                              │
│       ├ 工具调用：claim_task(task_id="T1", status="claimed")          │
│       │  └ ClaimTaskTool.invoke()                                    │
│       │  └ 权限检查：assignee=req_analyst == caller ✓                 │
│       │  └ taskManager.claimResult("T1").join()                      │
│       │     └ T1: pending → claimed                                  │
│       │     └ 防囤积检查：该成员无其他 claimed 任务 ✓                   │
│       │  └ 发布 TASK_CLAIMED 事件                                     │
│       │  └ ConsolePrinter 打印：[任务] T1 被领取                      │
│       │                                                              │
│       ├ LLM 推理 → 决定用 file_io 写 reports/T1-requirements.md       │
│       ├ 工具调用：file_io(action="write", ...)                        │
│       │                                                              │
│       ├ LLM 推理 → 决定回报 Leader                                    │
│       ├ 工具调用：send_message(to="leader", content="T1 完成...")     │
│       │  └ SendMessageTool.invoke()                                  │
│       │  └ messageManager.sendMessage(content, "leader")             │
│       │     └ 发布 MESSAGE 事件                                       │
│       │  └ ConsolePrinter 打印：[消息] req_analyst → leader           │
│       │                                                              │
│       ├ LLM 推理 → 决定完成任务                                       │
│       ├ 工具调用：claim_task(task_id="T1", status="completed")        │
│       │  └ taskManager.completeResult("T1").join()                   │
│       │     └ T1: claimed → completed                                │
│       │     └ ★ 检查下游：T2 依赖 [T1]，T1 已完成 → 解锁 T2           │
│       │     └ T2: blocked → pending                                  │
│       │     └ 发布 TASK_COMPLETED 事件 + TASK_UNBLOCKED 事件          │
│       │  └ ConsolePrinter 打印：[任务] T1 已完成                      │
│       │     [任务] T2 依赖满足，已解锁                                │
│       │                                                              │
│       └ 成员 ReAct 循环结束，状态回到 READY                            │
│                                                                        │
│  architect 领取 T2 → developer 和 qa_engineer 并行领取 T3/T4           │
│  → reviewer 领取 T5（等 T3+T4 都完成后）                                │
│                                                                        │
└────────────────────────────────────────────────────────────────────────┘

┌─ 动态调整阶段（Leader 根据回报自主调整）─────────────────────────────────┐
│                                                                        │
│  若 reviewer 回报 [REVIEW_REJECT]：                                     │
│  Leader 收到消息 → 框架触发 Leader 新一轮 ReAct                         │
│    └ LLM 推理 → 决定 create_task(T6 返工 + T7 复审)                    │
│    └ LLM 推理 → 决定 send_message(to="developer", content="返工...")   │
│    └ developer 收到返工指令 → 新一轮 ReAct 执行 T6                     │
│    └ reviewer 收到复审指令 → 新一轮 ReAct 执行 T7                      │
│                                                                        │
└────────────────────────────────────────────────────────────────────────┘

┌─ 收尾阶段（SimpleDemo 主线程轮询 + 打印 + 回收）─────────────────────────┐
│                                                                        │
│  SimpleDemo.waitUntilDone(monitor, timeout)                            │
│    └ 每 2s 轮询 monitor.getTasks()                                     │
│    └ 全部 completed/cancelled 且静默 3s → 退出                         │
│    └ 每 30s 打印心跳                                                   │
│                                                                        │
│  ConsolePrinter.printSummary(monitor)                                  │
│    └ 打印任务板（monitor.getTasks()）                                  │
│    └ 打印交付汇总（monitor.getMessages("user", "leader")）             │
│                                                                        │
│  cleanup()                                                             │
│    └ leader.removeEventListener(printer)                              │
│    └ leader.getStreamController().closeStream()                       │
│    └ monitor.stop()                                                   │
│    └ team.destroyTeam(true)                                           │
│    └ Runner.stop().join()                                             │
│                                                                        │
└────────────────────────────────────────────────────────────────────────┘
```

### 工具调用时序（Leader 首轮）

```
Leader ReAct 循环
  │
  ├─ 轮 1: LLM 推理 → list_members()
  │        └→ backend.listMembers() → [req_analyst, architect, ...]
  │
  ├─ 轮 2: LLM 推理 → create_task(tasks=[T1,T2,T3,T4,T5])
  │        └→ taskManager.addBatch(...)
  │           ├→ T1 pending (assignee=req_analyst)
  │           ├→ T2 blocked (deps=[T1])
  │           ├→ T3 blocked (deps=[T2])
  │           ├→ T4 blocked (deps=[T2])
  │           └→ T5 blocked (deps=[T3,T4])
  │
  ├─ 轮 3: LLM 推理 → send_message(to="*", content="启动指令")
  │        ├→ backend.startupAllUnstarted() → 5 成员 UNSTARTED→READY
  │        └→ messageManager.broadcastMessage(content)
  │
  └─ 轮 4: LLM 推理 → 无工具调用，等待
           └→ 流自动关闭
```

## 设计要点

### 角色定义与框架适配分离

| 文件 | 层次 | 框架依赖 |
|---|---|---|
| `TeamConfig` | 领域层 | 零（只有 Jackson） |
| `ConsolePrinter` | 领域层 | 仅 EventMessage/TeamMonitor |
| `TeamAssembler` | 适配层 | 封装全部框架交互 |
| `SimpleDemo` | 组合根 | 协调领域 + 适配 |

### 四个框架约束（封装在 TeamAssembler）

| 约束 | 处理方式 |
|---|---|
| 模型只能通过 modelPool 注入 | `buildModelEntry()` 构建 ModelPoolEntry |
| 预置成员拿不到模型 | `provisionMembers()` 用 spawnMember 写入模型引用 |
| TeamRail 提示词注入失效 | `composeFirstQuery()` 把人设+花名册拼进首条 query |
| 成员靠 send_message 懒启动 | Leader 人设写死 `send_message(to="*")` |

### 与 agent_teams_orchestration 示例的对比

| 维度 | agent_teams_orchestration | simple_leader_worker |
|---|---|---|
| 源文件 | 24 | 4 |
| 角色定义 | Java 常量（191 行） | JSON 配置文件 |
| 任务 DAG | Leader 人设文本硬编码 | Leader 运行时自主生成 |
| 观测系统 | 6 文件（四阶段映射+队列+缓存） | 1 文件（直接打印） |
| 动态调整 | 7 文件（4 规则+双线程监督器） | Leader 人设驱动（无额外代码） |
| 框架约束绕过 | 4 处分散 | TeamAssembler 一处封装 |

## 排障

| 现象 | 原因 | 处理 |
|---|---|---|
| `HTTP 402: Insufficient Balance` | DeepSeek 账户余额不足 | 充值后重试 |
| 启动即打印配置缺失 | 没设置环境变量 | `$env:DEEPSEEK_API_KEY_OPENJIUWEN = "sk-xxxx"` |
| 成员一直 unstarted | Leader 没发 `send_message(to="*")` | 查看日志是否有广播 |
| 任务长期 claimed | 成员没调 `claim_task(completed)` | 等超时退出后查看任务板 |
| 跑到一半退出 | 达到 `demo.timeout.seconds` | 调大超时 |
| 想清理历史 | 团队用 memory 存储 | 重启即可（不落盘） |

## 说明

- `storage=memory`、`spawnMode=inprocess`：单进程演示最稳。
- 团队生命周期选 `persistent`：跑完保留任务板，便于汇总。
- 本示例不修改 `core` 任何代码，全部能力都走公开 API。
