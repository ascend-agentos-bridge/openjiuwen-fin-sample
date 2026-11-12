# Java Agent Core 接入

Java 侧把 openJiuwen Agent Memory 当作外部记忆服务：实现 `MemoryProvider`，由 `ExternalMemoryRail`
在模型调用前后触发召回和写回。页面同时跑两个 Agent，用来对比有记忆和无记忆的回答。

版本：`agent-core-java:0.1.16`、`agent-core-memory-java:0.1.16`。

## 架构

```text
浏览器 (8099)
  │
Java Agent Core
  ├─ DeepAgent + ExternalMemoryRail
  │    ├─ beforeModelCall → AgentMemoryProvider.prefetch → <memory-context>
  │    └─ afterInvoke     → syncTurn → POST /v1/add (infer=true)
  ├─ DeepAgent（baseline，不挂 rail）
  └─ DualAnswerRunner：同一问题各答一次
        │
        └── HTTP ──▶ Agent Memory (8137)
                      ├─ /v1/search
                      ├─ /v1/add
                      ├─ /v1/list
                      ├─ /v1/update
                      ├─ /v1/evolve
                      └─ /portal
```

## 主要类

```text
src/main/java/com/openjiuwen/samples/creditmemory/
├── AgentMemoryAssistant.java              入口：启动 Agent Memory 客户端和 Web 页面
├── agentmemory/
│   ├── AgentMemoryClient.java             REST 客户端
│   ├── AgentMemoryProvider.java           MemoryProvider：prefetch / syncTurn
│   ├── DualAnswerRunner.java              双 Agent、双回答
│   ├── AgentMemoryProbe.java              真实调用 probe
│   ├── AgentMemoryConfig.java             Agent Memory 地址/身份/scope
│   ├── AgentMemoryScope.java              scope 映射
│   ├── AgentMemoryHit.java                召回命中
│   ├── AgentMemoryUnit.java               记忆单元
│   └── web/
│       ├── DualWebServer.java             业务 HTTP 页面
│       ├── DualSessionStore.java          页面会话
│       └── PortalUrls.java                Portal token / URL
└── config/ModelSettings.java              对话/记忆模型配置
```

## 启动

```bash
cd samples/29_credit_customer_memory
export LLM_API_KEY=sk-...
export EMBEDDER_BASE_URL=http://127.0.0.1:1234/v1
./scripts/start-agent-memory.sh

cd java
export CHAT_API_BASE=https://api.deepseek.com
export CHAT_API_KEY="$LLM_API_KEY"
export CHAT_MODEL=deepseek-flash
export AGENT_MEMORY_BASE_URL=http://127.0.0.1:8137
mvn -q -DskipTests compile
mvn -q exec:java
```

启动后地址：

- 业务页面：`http://127.0.0.1:8099/`
- Agent Memory Portal：`http://127.0.0.1:8137/portal/`
- Agent Memory API：`http://127.0.0.1:8137`

`AgentMemoryProbe` 用于不启动页面、直接验证 Agent Memory：

```bash
CP="target/classes:$(cat /tmp/s29-cp.txt)"
java -cp "$CP" com.openjiuwen.samples.creditmemory.agentmemory.AgentMemoryProbe
```

`mvn exec:java -Dexec.mainClass=...` 会被插件主类覆盖，不要用它跑 probe。

## 页面

左侧会话和演示脚本；中间双回答；右侧五层记忆、本轮召回、服务信息。

「模型设置」写入：

- `java/config/model.local.json`
- `memory-service/model.local.env`

修改后重启 Java 和 Agent Memory 生效。「清空记忆」按 scope 清空全部状态的个人记忆并重建会话。

## 演示脚本

| 脚本 | 输入/动作 | 预期 |
|---|---|---|
| ① 跨会话召回 | 首轮陈述，新建会话后追问额度/期限/还款方式 | 有记忆复述历史；基线重新询问 |
| ② 偏好召回 | 背景：只接受等额本息，不接受先息后本；追问推荐方案 | 有记忆按等额本息解释；基线先问偏好 |
| ③ 风险召回 | 背景：2023 年信用卡逾期已结清；追问审批注意点 | 有记忆主动提逾期已结清；基线给泛化清单 |
| ④ 关系召回 | 背景：太太林岚是共同借款人；追问共同借款人材料 | 有记忆直接复用林岚；基线重新询问关系 |

②③④ 的背景数据直接写入 Agent Memory，用作召回示例；追问与两个回答均走真实模型。

## 测试

```bash
mvn test
```

本地结果：

- 34 个测试通过；
- 其中 `AgentMemoryIntegrationTest`、`RealModelIntegrationTest` 为真实服务集成测试；
- `AgentMemoryIntegrationTest` 约 21s，覆盖 Client / Provider / Rail 写回 / 双回答。

## 已验证输出

跨会话追问：

- 有记忆：复述「120 万、24 期、等额本息、2 年内结清」；
- 基线：表示没有完整记录，需要客户重新提供。

偏好召回：

- 有记忆：「建议走等额本息。您明确说过更看重月供压力小、每月还款额固定……」；
- 基线：继续询问金额、期限、现金流。

风险召回：

- 有记忆：「2023 年那次信用卡逾期金额不大、已经结清……」；
- 基线：只给通用审批注意事项。

关系召回：

- 有记忆：「共同借款人林岚的身份证明、婚姻关系证明……」；
- 基线：重新确认共同借款人是谁。

## 限制

- Agent Memory dev auth 固定 `local/developer`，仅本机使用。
- `docker-compose.yml` 不是当前默认启动路径：容器内 Portal 受 upstream loopback 认证限制。
- `update` / `evolve` 没有接成页面演示；不要让样例代码替 Agent Memory 决定是否纠正或合并。
- LLM 输出有波动，以上是本地一次真实运行结果，不是固定断言。
