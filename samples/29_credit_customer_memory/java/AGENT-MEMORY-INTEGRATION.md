# Agent Memory + Agent Core MemoryRail 集成（Java 版）

> 本文件对应 `samples/29_credit_customer_memory/java/` 下新增的 `agentmemory` 包：
> 用 **Agent Memory REST 服务** 作为长期记忆后端，通过 Agent Core 0.1.16 自带的
> `ExternalMemoryRail` 把「记忆召回」注入 DeepAgent，并给出同一问题的「有记忆 vs 无记忆基线」
> 双回答。所有输出均为 **真实 DeepSeek + 本机 Agent Memory** 实跑结果，无 mock、无预置答案。
>
> 本文件与 `README.md`/`API.md` 独立：现有 `WebServer`/`ChatService`/`MemoryService` 的公开行为不变；
> UI 接入由后续任务负责。

## 1. 环境与版本

| 项 | 值 |
|---|---|
| 仓库位置 | `samples/29_credit_customer_memory/java/` |
| Java | 17（Corretto 17.0.21） |
| Agent Core | `com.openjiuwen:agent-core-java:0.1.16` |
| Agent Core Memory | `com.openjiuwen:agent-core-memory-java:0.1.16`（`ExternalMemoryRail` 在这个 jar 里） |
| Agent Memory 服务 | `http://127.0.0.1:8137`，`--auth-mode dev`（固定身份 `local/developer`） |
| 对话模型 | 真实 DeepSeek，`https://api.deepseek.com`，本次实跑 `deepseek-chat` |
| 记忆抽取/向量 | Agent Memory 服务侧配置：DeepSeek 抽取 + 本机 LM Studio `nomic-embed-text`（768 维） |
| Maven | `.cache/apache-maven-3.9.9/bin/mvn` + `.cache/maven-settings.xml`（`localRepository=.cache/m2repo`） |

API Key **只**通过环境变量传入（`CHAT_API_KEY`），不写进代码、README 或配置文件；仓库里可提交的
只有 `config/model.example.json`。

## 2. 新增文件

```text
java/src/main/java/com/openjiuwen/samples/creditmemory/agentmemory/
├── AgentMemoryConfig.java      Agent Memory 地址/dev 身份/超时（全部可用环境变量覆盖）
├── AgentMemoryScope.java       四维 Scope + dev auth 身份覆盖式映射说明
├── AgentMemoryException.java   保留服务端错误信封（error/message/request_id/retryable）的异常
├── AgentMemoryHttp.java        HTTP 传输最小抽象（测试注入点）
├── JdkAgentMemoryHttp.java     Java 11 HttpClient 实现（读写超时分离、带 Origin）
├── AgentMemoryClient.java      REST 客户端：healthz/launchPortal/search/add/list/update/evolve
├── AgentMemoryHit.java         /v1/search 命中（unit_id/score/content/abstract）
├── AgentMemoryUnit.java        /v1/add、/v1/list、/v1/update 返回的记忆单元
├── AgentMemoryProvider.java    实现 com.openjiuwen.core.memory.external.MemoryProvider
├── DualAnswerRunner.java       DeepAgent + ExternalMemoryRail vs 无 rail 基线，双回答
└── AgentMemoryProbe.java       真实探针 main（写入 → 双回答 → update → evolve，打印全过程）

java/src/test/java/com/openjiuwen/samples/creditmemory/agentmemory/
├── RecordingAgentMemoryHttp.java        离线记录 HTTP 请求的测试替身
├── AgentMemoryClientTest.java           11 个规则测试（请求构造 + 响应解析 + 错误信封）
├── AgentMemoryProviderTest.java         12 个规则测试（prefetch/syncTurn 请求构造 + 降级）
├── DualAnswerRunnerTest.java            5 个结构测试（双回答结果结构/前置校验）
└── AgentMemoryIntegrationTest.java      @Tag("integration") 真实端到端验收（默认跳过）

java/AGENT-MEMORY-INTEGRATION.md        本文件
```

> 未修改 `pom.xml`：新增代码只用 JUnit（已有）、Jackson（agent-core 传递依赖）、
> `java.net.http.HttpClient`（JDK）与 slf4j/logback（已有），无需新增依赖。

> 同目录的 `AgentMemoryAssistant.java`、`agentmemory/web/DualWebServer.java`、
> `agentmemory/web/DualSessionStore.java` 与 `src/main/resources/web-dual/` 是 Lead 的
> UI 接入任务新增（复用本文件的 `AgentMemoryClient`/`AgentMemoryProvider`/`DualAnswerRunner`），
> 不属于本集成文档的范围。

## 3. 类职责与关键实现

### 3.1 `AgentMemoryClient`（REST 客户端）

请求/响应字段严格按 Agent Memory 的 S02 契约（`POST /v1/<verb>`，无 `{ok, op, result}` 信封）：

| 方法 | HTTP | 请求体要点 | 响应解析 |
|---|---|---|---|
| `healthz()` | `GET /healthz` | — | 原始 JSON |
| `launchPortal()` | `POST /portal/api/launch` | `{}`（必须带 `Origin: http://127.0.0.1:8137`） | `portal_url` |
| `search(query, scope, topK)` | `POST /v1/search` | `query`、`context.scope`、`context.extensions:{}`、`top_k` | `items[].unit_id/score/content/abstract` |
| `add(content, scope, tags, metadata)` | `POST /v1/add` | `content`、`scope`、可选 `tags`、`system_metadata` | **顶层数组**，正文取 `segments[0].content` |
| `list(scope, offset, limit)` | `POST /v1/list` | `scope`、`offset`、`limit` | `items[]`（含 superseded 历史） |
| `update(scope, unitId, content)` | `POST /v1/update` | `scope`、`unit_id`、`patch.content` | 新版本单元，`supersedes` 指向旧版 |
| `evolve(scope, mode)` | `POST /v1/evolve` | `scope`、`mode:"consolidate"` | 裸 JSON 字符串任务 ID |

失败统一抛 `AgentMemoryException`：HTTP 非 2xx 保留服务端错误信封（含 `request_id`），
网络/解析失败给出可读原因；`retryable` 区分「业务拒绝」与「服务不可用」。
`AgentMemoryHttp` 是唯一测试注入点，默认实现 `JdkAgentMemoryHttp` 读超时 30s、写超时 180s
（`infer=true` 的 add 会同步跑真实 LLM 抽取，单次实测 5~6 秒）。

### 3.2 `AgentMemoryProvider`（`implements MemoryProvider`）

0.1.16 的 `MemoryProvider` 是**同步 interface**，实现方法如下图：

```java
public final class AgentMemoryProvider implements MemoryProvider {
    String getName();                                  // "agent-memory"
    boolean isAvailable();
    void initialize(Map<String,Object> scope);         // GET /healthz 探活，只做一次
    List<Map<String,Object>> getToolSchemas();          // 空：本样例不向 LLM 注册记忆工具
    String handleToolCall(String, Map<String,Object>);  // 未注册工具则抛 UnsupportedOperationException
    String prefetch(String query, Map<String,Object> scope);   // GET → 格式化 Markdown 记忆块
    void syncTurn(String userMsg, String assistantMsg, Map<String,Object> scope); // POST /v1/add
    String systemPromptBlock();                        // 业务化提示，不含内部术语
    void shutdown(); boolean isInitialized();
}
```

- **prefetch**：`scope` 来自 rail 的 `{user_id, scope_id, session_id}`；调 `/v1/search`，
  把命中格式化成 `## Agent Memory（客户长期记忆召回）\n- ...`（rail 会再包 `<memory-context>`），
  同时缓存 `lastRecalledContext/lastMemoryContextBlock/lastRecalledHits/lastPrefetchCount` 供探针与 UI 使用。
- **syncTurn**：写回内容为 `user: <用户消息>\nassistant: <模型回答>`，
  `system_metadata={"infer":"true","source_app":"credit-demo","agent_label":"credit-agent"}`，
  触发服务端真实 LLM 抽取；返回的派生单元缓存在 `lastSyncedUnits()`。
- **失败降级**：prefetch 失败返回 `""`（本轮不注入记忆），syncTurn 失败只记录 `lastError()`，
  不打断业务对话——与官方 Python 适配器 `agent_memory_provider.py` 的 opportunistic 语义一致。
- **systemPromptBlock**：只写「优先使用客户长期记忆；不要向客户透露记忆如何存储/召回」，
  不含「向量/数据库/字段名/接口路径」等内部术语（有规则测试守护）。

### 3.3 `DualAnswerRunner`（双回答）

两个 `DeepAgent` 共用同一模型、同一系统提示词：

1. **有记忆**：`DeepAgentConfig.builder().rails(List.of(new ExternalMemoryRail(provider, customerId, "credit-agent", conversationId)))`；
2. **基线**：`rails(List.of())`，不注册任何记忆 rail。

0.1.16 接入代码（与 0.1.17 develop 示例的差异已核实）：

```java
CheckpointerFactory.setDefaultCheckpointer(new InMemoryCheckpointer());
Runner.setConfig(RunnerConfig.DEFAULT);
Runner.start();

DeepAgentConfig config = DeepAgentConfig.builder()
        .enableTaskLoop(true)                 // ⚠️ 必须 true，见 §6
        .enableTaskPlanning(false)
        .workspacePath(workspaceRoot.toString())
        .systemPrompt(SYSTEM_PROMPT)
        .maxIterations(4)
        .completionTimeout(120.0)
        .language("cn")
        .model(Map.of("model", model, "temperature", 0.2, "max_tokens", 2048,
                      "thinking", Map.of("type", "disabled")))
        .backend(Map.of("client_provider", "openai", "api_key", key,
                        "api_base", "https://api.deepseek.com",
                        "timeout", 120.0, "max_retries", 1, "verify_ssl", true))
        .rails(rails)
        .build();

AgentCard card = AgentCard.builder().id(agentId).name(agentId).description("...").build();
Workspace ws = Workspace.builder().rootPath(workspaceRoot.toString()).language("cn").build();
DeepAgent agent = HarnessFactory.createDeepAgent(card, config, ws);
agent.ensureInitialized();

AgentSessionApi session = AgentSessionApi.create(sessionId, new LinkedHashMap<>(), agent.getCard());
Runner.runAgent(agent, Map.of("query", q, "conversation_id", sessionId), session, null);
```

- `rememberByAgent(statement)`：让挂 rail 的 Agent 真实回答一轮，`afterInvoke` 自动把
  `user: ...\nassistant: <真实模型回答>` 写回 Agent Memory（`infer=true` 真实抽取），
  返回 `SeedResult{agentAnswer, syncedContent, extractedUnits, syncError}`。
- `ask(question)`：先跑有记忆 Agent（捕获本轮 rail prefetch 的原始上下文），再跑基线 Agent，
  返回 `DualAnswer{question, withMemoryAnswer, baselineAnswer, recalledContext,
  injectedMemoryBlock, prefetchCount, recalledHits}`；`toMap()` 可直接作为 JSON 返回给页面。
- 答案只来自真实模型调用；空回答/异常直接抛出，没有兜底文案。

## 4. MemoryRail 注入点（0.1.16 源码核实）

`com.openjiuwen.memory.harness.rails.ExternalMemoryRail`（在 `agent-core-memory-java` jar）：

| 回调 | 行为 |
|---|---|
| `init(DeepAgent)` | 把 `owner` 设为 DeepAgent；`provider.getToolSchemas()` 非空时注册工具（本样例为空，不注册）；`provider.systemPromptBlock()` 非空时作为持久 prompt section 注入 |
| `beforeInvoke` | 首次调用 `provider.initialize({user_id, scope_id, session_id})` |
| `beforeModelCall` | 取本轮用户输入 → `provider.prefetch(query, scope)` → 非空则拼成 `<memory-context>…</memory-context>` 同时写入 prompt section 与 system message（每轮只检索一次） |
| `afterInvoke` | `provider.syncTurn(query, assistantOutput, scope)`（连续失败 5 次后停止尝试） |

`providerScope()` 固定为 `{"user_id": userId, "scope_id": scopeId, "session_id": sessionId}`，
对应 `new ExternalMemoryRail(provider, customerId, "credit-agent", conversationId)`。

## 5. Scope 映射（重要：dev auth 只能写 developer）

本机 Agent Memory 是 **dev auth**，服务端固定身份 `local/developer`，写操作要求 target 的
org/user 与认证身份一致。实测直接写 `scope.user=cust_8801` 返回：

```json
{"error":"PermissionDeniedError","message":"permission denied","request_id":"9bc6...","retryable":false}
```

因此沿用官方 Python 适配器（`agent_memory_provider.py` §6.2-D）的「身份覆盖式」映射：

| HTTP scope 字段 | 取值 | 说明 |
|---|---|---|
| `org` | `local`（`AGENT_MEMORY_ORG`） | 认证身份 |
| `user` | `developer`（`AGENT_MEMORY_USER`） | 认证身份，dev 下不可改 |
| `agent` | `credit-agent`（rail 的 `scope_id`） | 语义 Agent |
| `session` | 客户 ID（rail 的 `user_id`） | **客户/会话语义轴下沉**；0.1.16 dev 单身份部署没有独立轴承载 `session_id` |

生产接入真实认证后，把 `AGENT_MEMORY_USER` 配成客户 ID、或改用
`AgentMemoryProvider(client, agent, topK, SESSION_AXIS_SESSION_ID)` 即可回到五维直映射。
该映射有规则单测覆盖（`AgentMemoryProviderTest`）。

## 6. ⚠️ 0.1.16 DeepAgent 关键坑位：`enableTaskLoop(false)` 不执行模型

首次接线时 `DualAnswerRunner` 使用 `.enableTaskLoop(false)` + `Runner.runAgent(deepAgent, ...)`，
真实运行在 `[4]` 失败：

```text
IllegalStateException: DeepAgent 返回空回答, 原始结果:
{agent_name=credit-agent-memory, mode=normal, workspace=..., inputs={query=..., conversation_id=...}}
```

读 0.1.16 `DeepAgent.invoke` 源码定位：`Runner.runAgent` 反射先命中 `DeepAgent.invoke(Map, session)`；
当 `enableTaskLoop(false)` 时它**只返回调用信封**（`agent_name/mode/workspace/inputs`），
根本不执行底层 `ReActAgent`。两种可行接法：

1. **`enableTaskLoop(true)`（本样例采用，Lead 独立实跑确认）**：`DeepAgent.invoke` 走
   `runTaskLoop`，返回真实回答，rails 在 task-loop 线程里正常触发
   （线程名形如 `task-deep_agent_task_seed-..._1`，见 §7 日志）；
2. 或跳过 DeepAgent 外壳，直接 `Runner.runAgent(agent.getAgent(), inputs, session, null)`
   调底层 `ReActAgent`（rails 已注册在其上，也能触发；但不保留 DeepAgent 的任务编排）。

结论：**在本样例里 `enableTaskLoop(true)` 不能回退**；升级到 0.1.17 后需按新 release 重新核对
`DeepAgent.invoke` 行为。

## 7. 真实运行与实测输出

### 7.1 运行命令

```bash
cd samples/29_credit_customer_memory/java

# 1) 编译 + 导出依赖 classpath
export JAVA_HOME=/Users/tianxiaoliang/codespace/openjiuwen-fin-sample/.cache/tools/amazon-corretto-17.jdk/Contents/Home
MVN=/Users/tianxiaoliang/codespace/openjiuwen-fin-sample/.cache/apache-maven-3.9.9/bin/mvn
SET=/Users/tianxiaoliang/codespace/openjiuwen-fin-sample/.cache/maven-settings.xml
$MVN -s $SET -q -DskipTests compile
$MVN -s $SET -q dependency:build-classpath -Dmdep.outputFile=/tmp/am-cp.txt

# 2) 模型环境变量（Key 不进仓库）
export CHAT_API_BASE=https://api.deepseek.com
export CHAT_API_KEY=sk-...            # 运行者自己的 Key
export CHAT_MODEL=deepseek-chat

# 3) 可选：Agent Memory 连接/身份（默认 127.0.0.1:8137 + local/developer）
export AGENT_MEMORY_BASE_URL=http://127.0.0.1:8137
export AGENT_MEMORY_CUSTOMER=zhou-yanqing
export AGENT_MEMORY_CONVERSATION=zhou-yanqing-s1

# 4) 跑真实探针（两个参数：客户首轮陈述 | 客户追问；省略则终端输入或使用周砚清示例）
"$JAVA_HOME/bin/java" -cp "target/classes:$(cat /tmp/am-cp.txt)" \
  com.openjiuwen.samples.creditmemory.agentmemory.AgentMemoryProbe \
  "我想申请经营贷。我是周砚清，在杭州经营精密制造公司，想贷80万分36期，偏好等额本息，计划5年内提前结清；2023年有过一次信用卡逾期但已结清，不接受先息后本。" \
  "我想申请多少额度、期限多久、偏好什么还款方式？"
```

> 为什么不用 `mvn exec:java -Dexec.mainClass=...`：本模块 `exec-maven-plugin` 的
> `<mainClass>` 固定为 `CreditMemoryAssistant`，CLI 属性不会覆盖已写死的配置；
> 用 `java -cp` 直接运行得到的才是 `AgentMemoryProbe`（上面的命令已实测，退出码 0）。

### 7.2 真实输出（2026-11-12，本机 8137 + DeepSeek `deepseek-chat`）

`[1]`~`[3]` 探活 / Portal / provider 初始化：

```text
[1] GET /healthz 健康检查 ...
    -> {"status":"ok","profile":"credit-memory-demo"}
[2] POST /portal/api/launch 获取 Portal 链接 ...
    -> http://127.0.0.1:8137/portal/#access=ecaYumRWhE0mJWy5st-zjkwguQpjDr-wgDK-gBSl3qw
[3] AgentMemoryProvider.initialize 成功（scope 映射见 AGENT-MEMORY-INTEGRATION.md）
```

`[4]` 挂 rail 的 Agent 真实回答首轮陈述，`afterInvoke` 写回并触发真实抽取（**6 条**）：

```text
[4] 让挂 ExternalMemoryRail 的 Agent 真实回答客户首轮陈述（afterInvoke 自动写回记忆）...
    客户: 我想申请经营贷。我是周砚清，在杭州经营精密制造公司，想贷80万分36期，偏好等额本息，计划5年内提前结清；2023年有过一次信用卡逾期但已结清，不接受先息后本。
    Agent: 周总您好，我是您的客户经理。您的情况我大致了解了：杭州精密制造公司，申请80万、36期，等额本息，计划5年内提前结清，不接受先息后本。

先跟您确认几个关键点：您2023年那次信用卡逾期已经结清，这个对审批影响不大，但需要您授权我们查一下征信来核实。另外想了解一下公司目前的经营情况——比如成立年限、近一年的开票或流水大概什么水平、有没有抵押物？这些直接关系到额度和利率。

等额本息36期没问题，提前结清一般也没有违约金，具体以合同为准。您方便的话，把营业执照和近半年流水先发我，我帮您做个初步评估。
    rail syncTurn 写回内容: user: 我想申请经营贷。我是周砚清，…… assistant: 周总您好，我是您的客户经理。……
    infer=true 真实抽取单元数: 6
      - [daecd533-a4ef-4585-9141-d6c016414ef2] 周砚清在杭州经营一家精密制造公司，申请80万元经营贷，分36期，偏好等额本息，计划5年内提前结清，不接受先息后本。
      - [247cdc1d-0a40-473a-acb7-1cb421e0dbbc] 周砚清在2023年有过一次信用卡逾期，但已结清。
      - [a2a051b6-2581-4ce2-b679-f760ac0979cb] 客户经理认为周砚清2023年那次已结清的信用卡逾期对审批影响不大，但需要周砚清授权查征信来核实。
      - [5d2f8091-dbc7-451e-b24d-2e139d43c554] 客户经理向周砚清询问公司目前的经营情况，包括成立年限、近一年的开票或流水水平以及是否有抵押物，因为这些直接关系到额度和利率。
      - [4c302216-ac21-4e17-b099-2d7ea8544f7e] 客户经理表示等额本息36期没有问题，提前结清一般也没有违约金，具体以合同为准。
      - [56a9b603-6b4e-4ae5-81b5-57e688d5ff55] 客户经理请周砚清把营业执照和近半年流水先发给他，以便做初步评估。
```

`[5]` 同一追问的「有记忆 vs 基线」双回答 + rail 注入原文（**prefetchCount=5**）：

```text
[5] 同一客户追问：有 rail 召回 vs 无 rail 基线 ...
    客户追问: 我想申请多少额度、期限多久、偏好什么还款方式？
    ------------------------------------------------
    [有记忆] 周总，您之前提过：申请80万元经营贷，分36期，偏好等额本息，计划5年内提前结清，不接受先息后本。这些我这边都按您的意向来推进。

目前还差两块材料：营业执照和近半年流水，您方便先发我，我做初步评估；另外征信查询需要您授权，我才能核实之前那笔已结清的信用卡逾期情况。
    ------------------------------------------------
    [基线  ] 您这个问题像是把您想了解的信息说反了——应该是您来告诉我您的需求，我来帮您匹配方案。您可以先说三件事：大概需要多少资金、打算用多久、希望怎么还款（比如按月付息到期还本，或等额本息），我再结合您的经营情况给您建议。
    ------------------------------------------------
    rail prefetch 召回条数: 5
    rail 注入的 <memory-context> 原文:
      <memory-context>
      [System note: recalled memory context from long-term memory, NOT new user input.]

      ## Agent Memory（客户长期记忆召回）
      - 客户经理向周砚清询问公司目前的经营情况，包括成立年限、近一年的开票或流水水平以及是否有抵押物，因为这些直接关系到额度和利率。
      - 周砚清在杭州经营一家精密制造公司，申请80万元经营贷，分36期，偏好等额本息，计划5年内提前结清，不接受先息后本。
      - 客户经理表示等额本息36期没有问题，提前结清一般也没有违约金，具体以合同为准。
      - 客户经理请周砚清把营业执照和近半年流水先发给他，以便做初步评估。
      - 客户经理认为周砚清2023年那次已结清的信用卡逾期对审批影响不大，但需要周砚清授权查征信来核实。
      </memory-context>
```

`[6]`~`[8]` `/v1/update` SUPERSEDE、`/v1/evolve`、`/v1/list`（**真实返回**）：

```text
[6] POST /v1/update 演示 SUPERSEDE 纠正 ...
    旧 unit: 90caaf20-7607-4774-b0aa-9dafe8693056
    新 unit: 82bc53a6-ecfb-47d7-b18f-b5a09c49dcb2 supersedes=90caaf20-7607-4774-b0aa-9dafe8693056
    lifecycle=superseded id=90caaf20-7607-4774-b0aa-9dafe8693056 supersedes=
    lifecycle=active id=82bc53a6-ecfb-47d7-b18f-b5a09c49dcb2 supersedes=90caaf20-7607-4774-b0aa-9dafe8693056
    ...
[7] POST /v1/evolve 触发记忆演进（consolidate，异步任务）...
    -> job_id=f60f4a22-1e52-4cd7-b230-d2edc63f8097
[8] 当前 scope 记忆快照（POST /v1/list）：
    lifecycle=active |  | 客户经理在受理经营贷申请时，先收集基础材料（营业执照、近半年流水），再询问经营年限、开票或流水水平、抵押物情况，以评估额度和利率，最后结合客户的贷款金额、期限、还款方式偏好和提前结清计划给出方案。
    lifecycle=active |  | 客户经理已确认周砚清提出的经营贷意向：申请80万元、分36期、采用等额本息还款、计划5年内提前结清、不接受先息后本，并按此推进；36期等额本息可行，提前结清一般无违约金（以合同为准），放款前可提供近6个月对公流水。周砚清2023年有一次已结清的信用卡逾期，客户经理认为对审批影响不大，但需其授权查询征信核实。
    lifecycle=superseded | 确认贷款意向 | 客户经理确认周砚清此前提出的经营贷意向：……
    lifecycle=active | 确认贷款意向 | 客户经理确认周砚清此前提出的经营贷意向：……（客户补充：放款前可提供近6个月对公流水）
    lifecycle=active | 索要营业执照与流水 | 客户经理请周砚清把营业执照和近半年流水先发给他，以便做初步评估。
    lifecycle=active | 还款方式与违约金 | 客户经理表示等额本息36期没有问题，提前结清一般也没有违约金，具体以合同为准。
    lifecycle=active | 询问公司经营情况 | 客户经理向周砚清询问公司目前的经营情况，包括成立年限、近一年的开票或流水水平以及是否有抵押物，因为这些直接关系到额度和利率。
    lifecycle=active | 逾期对审批影响 | 客户经理认为周砚清2023年那次已结清的信用卡逾期对审批影响不大，但需要周砚清授权查征信来核实。
    lifecycle=active | 2023年信用卡逾期 | 周砚清在2023年有过一次信用卡逾期，但已结清。
    lifecycle=active | 经营贷申请需求 | 周砚清在杭州经营一家精密制造公司，申请80万元经营贷，分36期，偏好等额本息，计划5年内提前结清，不接受先息后本。
```

`evolve` 的 consolidate 是异步任务；本次实跑在随后 `list` 中已经能看到两条新合并出的记忆
（`md_title` 为空的两条 active），说明任务在服务端真实执行，而不是仅返回 job id。

> 说明：`[4]` 的模型回答与抽取条数会随模型/抽取配置略有波动（Lead 先前的独立实跑为 4 条抽取、
> prefetchCount=4），但链路与语义一致：`rail syncTurn` 写入 → 下一轮 `prefetch` 召回 → 双回答差异明显。

### 7.3 JUnit 验收

默认（离线规则测试，不联网、不需要 Agent Memory）：

```bash
$MVN -s $SET test
# Tests run: 32, Failures: 0, Errors: 0, Skipped: 0 —— BUILD SUCCESS
#   AgentMemoryClientTest     11
#   AgentMemoryProviderTest   12
#   DualAnswerRunnerTest       5
#   （其余 4 个为样例原有测试）
```

真实集成测试（`@Tag("integration")`，默认被 surefire 排除）：

```bash
export CHAT_API_BASE=https://api.deepseek.com
export CHAT_API_KEY=sk-...
export CHAT_MODEL=deepseek-chat
$MVN -s $SET -DexcludedGroups=none -Dtest=AgentMemoryIntegrationTest test

# Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 21.24 s
# BUILD SUCCESS
```

集成测试覆盖同一真实链路：`/healthz` → `/portal/api/launch` → provider 初始化 →
`rememberByAgent`（rail 写回，assert 有抽取单元）→ `ask`（assert `prefetchCount >= 1`、
`<memory-context>` 非空、召回内容含「经营贷」）→ `/v1/update`（assert SUPERSEDE）→
`/v1/evolve`（assert 返回任务 ID）。

## 8. 给 UI/WebServer 接入的用法

现有 `WebServer`/`ChatService`/`MemoryService` 不需要改；新页面/接口可以按下面方式接入：

```java
AgentMemoryConfig   config   = AgentMemoryConfig.fromEnvironment();
AgentMemoryClient   client   = new AgentMemoryClient(config);       // 或注入 AgentMemoryHttp 便于测试
AgentMemoryProvider provider = new AgentMemoryProvider(client);
DualAnswerRunner    runner   = new DualAnswerRunner(settings, client, provider, customerId, conversationId);

runner.start();                                   // 进程内一次即可
runner.rememberByAgent(customerMessage);          // 或 runner.rememberTurn(userMsg, agentReply) 直接写 provider
var result = runner.ask(customerQuestion);        // DualAnswer
Map<String,Object> json = result.toMap();         // 直接响应：withMemoryAnswer/baselineAnswer/
                                                  // recalledContext/injectedMemoryBlock/prefetchCount/recalledHits
runner.close();                                   // 关闭两个 DeepAgent、Runner、临时 workspace
```

`provider.lastRecalledContext()` / `lastPrefetchCount()` / `lastRecalledHits()` 可直接驱动页面右栏
「本轮召回」；`AgentMemoryClient.launchPortal()` 返回的 `portal_url` 可作为「打开记忆 Portal」入口
（注意 access token 一次性、服务重启后失效）。

## 9. 风险与未验证点

| 风险 | 说明 / 处理 |
|---|---|
| 0.1.16 `DeepAgent.invoke` 行为 | `enableTaskLoop(false)` 只返回信封不执行模型；本样例固定 `enableTaskLoop(true)`。升级 0.1.17 需重新核对 |
| dev auth 身份限制 | 只能写 `local/developer`；客户维度下沉到 `session` 轴。生产需真实认证 + `AGENT_MEMORY_USER` 或 `SESSION_AXIS_SESSION_ID` 切换 |
| Agent Memory 依赖本机服务 | base URL 默认 `127.0.0.1:8137`；`/v1/add infer=true` 依赖服务端配置的 LLM + embedding（本机 LM Studio）。服务未起时 provider 初始化失败、`/v1/add` 会 5xx，provider 按降级返回空 |
| portal_url 时效 | access token 一次一签，重启/过期后需重新 `launchPortal()` |
| 抽取条数波动 | 由服务端 LLM 抽取决定（实测 3~6 条），测试只断言「>0 且召回成功」，不锁定条数 |
| 未接入真实生产认证 | 未验证多租户/space 权限；当前只覆盖本机 dev auth |
| 未改 UI | 双回答的页面呈现由 Lead 后续接入；本文件只提供 `DualAnswer.toMap()` JSON 结构 |
| `thinking:disabled` extra field | 用于 DeepSeek 推理模型关闭思考；非 DeepSeek 的 OpenAI 兼容端点若不支持该扩展可能报错，换模型时删除该字段即可 |

## 10. 结论

- Agent Memory REST（add/search/list/update/evolve/portal）+ Agent Core 0.1.16 `MemoryProvider`
  实现 + `ExternalMemoryRail` 注入 DeepAgent 的链路已 **真实跑通**：rail 写回 6 条派生记忆、
  下一轮召回 5 条并注入 `<memory-context>`、有记忆回答准确复述客户 80 万/36 期/等额本息偏好，
  无记忆基线只能反问；update/evolve/list 均有真实服务端结果。
- 默认 32 个离线规则测试全绿；`@Tag("integration")` 真实端到端测试 21s 通过。
- 唯一框架级坑位是 0.1.16 `DeepAgent.invoke` 在 task loop 关闭时返回信封，已按
  `enableTaskLoop(true)` 解决并在本文档留档。
