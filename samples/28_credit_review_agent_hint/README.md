# 28 信贷复核：AgentHint 与会话缓存生命周期

Java 使用 **`com.openjiuwen:agent-core-java:0.1.17`**，需要 Java 17+、Maven 3.9+、可用真实模型，以及模型工具调用支持。Python 版待实现。

信贷专员输入客户问题，Agent 调只读工具查询虚构征信，再回答客户编号、评分、逾期笔数。本例仅有客户 `C1001` 的演示档案（735 分、逾期 0 笔），不办理实际授信。模型回答来自真实服务，没有假模型响应。

```text
28_credit_review_agent_hint/
├── README.md
├── python/README.md                 # 待实现
└── java/
    ├── pom.xml
    ├── src/main/java/CreditReviewAgentHint.java
    ├── src/test/java/                # 离线规则和真实模型测试
    └── test_output.txt
```

## 先运行普通模型

```bash
cd samples/28_credit_review_agent_hint/java
export API_BASE='https://api.deepseek.com'
export MODEL_NAME='deepseek-flash'
read -r -s -p 'API key: ' API_KEY
export API_KEY
printf '\n'
export AGENT_HINT_ENABLED=false
mvn compile exec:java
```

在终端输入问题，程序不替运行者填写问题或回答。DeepSeek 官方普通模型用于验证业务基线，不能验证 AgentHint 的执行效果。

| 步骤 | 运行者输入 | 程序行为 / 输出 |
|---|---|---|
| 1 | `请查询客户C1001的评分和逾期笔数` | Agent 调工具，输出 C1001、735、逾期 0 笔 |
| 2 | `/pause` | 暂停业务会话；开启 Hint 时额外调用缓存 suspend |
| 3 | `/resume` | 允许继续提问；不立即触发模型调用 |
| 4 | `请重新查询C1001` | 复用同一会话查询；开启 Hint 时 runtime 按状态自动预取 |
| 5 | `/exit` | 开启 Hint 时请求释放缓存，并清理资源 |

缺少客户编号时 Agent 可先追问；未知客户由工具拒绝。暂停期间输入问题会提示先 `/resume`。退出释放的是缓存，不是删除银行业务数据。

## 如何开启 AgentHint

需要**支持 AgentHint 身份与缓存管理协议的真实推理服务**；普通 OpenAI 兼容接口返回 200 不能证明支持该协议。

```bash
export AGENT_HINT_ENABLED=true
export API_BASE='http://your-compatible-server:8000/v1'
export MODEL_NAME='your-model-id'
# 配置该服务的 API_KEY 后运行
mvn compile exec:java
```

`API_BASE` 是客户端追加 `/chat/completions` 前的地址；若服务暴露 `/v1/chat/completions`，设置到 `/v1`。DeepSeek 示例使用官方根地址；不要套用旧 sample 24 的专用 release 接口配置。

入口直接调用官方 API，同时配置三项：

```java
ModelClientConfig.builder()
    .clientProvider("AscendAffinity")
    .apiBase(apiBase).apiKey(apiKey)
    .extraFields(Map.of("extensions",
        Map.of("kv_cache", Map.of("mode", "affinity"))))
    .build();

agentConfig.setEnableKvCacheAffinity(true);
```

还需把 `new KVCacheRuntime(() -> model)` 注入官方 `AgentSession`。本例复用同一个非流式 session，通过官方 `Runner.runAgent` 发起业务。SDK 构造 `agent_hint` 身份字段，业务代码不拼协议 JSON。

生命周期对应：

- `/pause` → `session.suspendKvc().join()`，在当前轮完成后请求 offload。
- `/resume` 后下一次推理 → runtime admission 按状态自动 prefetch，不手工重复预取。
- `/exit` → `session.releaseKvc().join()`，请求 session evict；随后关闭 runtime 和 Runner。

AgentHint 是推理缓存调度提示，不改变业务结论。`true` 返回值只说明客户端动作成功，不能证明迁移了多少 KV；SDK 存在 fail-open，缓存动作异常时模型仍可能作答。服务端执行量和缓存资源变化要另取证。本例不演示子 Agent 血缘、压缩或消息范围 evict。

## 配置与代码讲解

| 环境变量 | 默认 | 作用 |
|---|---|---|
| `API_BASE` | `https://api.deepseek.com` | 模型地址 |
| `MODEL_NAME` | `deepseek-flash` | 服务端模型 ID |
| `API_KEY` | 无 | 服务密钥，运行前提供，不提交仓库 |
| `AGENT_HINT_ENABLED` | `false` | 只接受 `true` / `false` |

`CreditReviewAgentHint.java` 直接装配官方 Model、ReActAgent、LocalFunction、AgentSession 和 KVCacheRuntime。工具只提供演示档案；终端循环只处理运行者输入、查询与暂停/继续/退出。输出预算 1024 token、temperature=0、top_p=1，关闭 thinking，关闭可配置重试。

## 验收与真实运行记录

```bash
cd java
mvn test                    # 离线规则测试
mvn -Preal-model test       # 需要真实模型环境变量
```

2026-11-10 已在 **Linux Docker / Eclipse Temurin Java 17 / Maven 3.9.16 / DeepSeek 官方 deepseek-flash** 上运行。3 项测试全部通过，其中真实集成测试通过测试代码向终端入口提供上述两轮查询和暂停/继续命令。以下为真实输出节选，完整记录见 [test_output.txt](java/test_output.txt)：

```text
AgentHint=false；输入客户问题；/pause 暂停，/resume 继续，/exit 结束。
...
- **客户编号**：C1001
- **信用评分**：735
- **逾期笔数**：0
...
会话已暂停。
可以继续提问。
...
- **客户编号**：C1001
- **信用评分**：735
- **逾期笔数**：0
```

这是**真实模型、脚本化输入的验收记录**，不是人工交互记录。运行者正常使用时自行输入。当前没有兼容 AgentHint 的推理环境，开启后的暂停/预取/释放服务端执行与 off/on 性能均待验收。

性能程序、Linux 一键脚本、客户端指标与报告阅读指导见 [benchmark README](../../benchmarks/java_agent_hint/README.md)。

官方依据：[v0.1.17 scheduling 示例](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.17/examples/kv-cache-demo/src/main/java/examples/AscendAffinitySchedulingDemo.java)、[AgentHint](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.17/core/src/main/java/com/openjiuwen/core/kvcache/AgentHint.java)。官方示例的 mock gateway 仅作 API 参考，本例未复制其假模型。
