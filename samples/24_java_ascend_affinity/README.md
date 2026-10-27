# 24 Java：开启 KV Cache 算力亲和

适配 **`com.openjiuwen:agent-core-java:0.1.16`**。2026-10-27 已通过 GitHub latest release 核对该版本。需要 JDK 17+、Maven 3.9+ 和支持工具调用的真实模型。

## 场景与代码

同一信贷复核会话连续三轮读取 C1001 的演示征信档案。工具返回较长明细，官方 `MicroCompactProcessor` 保留最近一次工具结果、清理旧内容。开启亲和后，框架可以在上下文变化时协同服务端释放 KV Cache。

业务入口是 [CreditAffinity.java](src/main/java/CreditAffinity.java)，直接使用官方 `ReActAgent`、`LocalFunction`、`ContextEngineConfig` 和 `MicroCompactProcessorConfig`，没有模型/Agent 包装层。银行档案是固定演示数据；模型回答来自真实服务。

## 先跑普通模型

```bash
cd samples/24_java_ascend_affinity
export API_BASE="http://127.0.0.1:1234/v1"
export API_KEY="lm-studio"
export MODEL_NAME="google/gemma-4-e4b"
export AFFINITY_ENABLED=false
mvn -q compile exec:java
```

以上也是默认配置，普通模式可在没有昇腾环境时运行。它只验收模型和多轮工具流程，不代表验证亲和加速。

## 如何开启

官方配置的关键代码如下：

```java
ModelClientConfig.builder()
    .clientProvider("InferenceAffinity")
    .apiBase("http://your-affinity-server:8000") // 服务根地址，不带 /v1
    .apiKey(apiKey).build();

ContextEngineConfig.builder()
    .enableKvCacheRelease(true)
    .build();
```

样例已经把这两个配置连在同一开关上：

```bash
export AFFINITY_ENABLED=true
export API_BASE="http://your-affinity-server:8000"
export API_KEY="your-key"
export MODEL_NAME="your-model"
mvn -q compile exec:java
```

服务端需要支持官方亲和协议。官方 v0.1.16 示例要求安装 `openJiuwen-vllm-affinity` 的 vLLM 服务，暴露 `POST /v1/chat/completions` 和 `POST /release_kv_cache`；只有普通 OpenAI 兼容接口不够。

`Runner` 维持同一 `conversation_id`。官方模型客户端把会话与开关转换为 `cache_salt`、`cache_sharing` 请求字段，`ContextEngine` 检测到需要释放的上下文变化后请求释放。日志中的开关/参数/2xx 是配置与受理证据；真正命中量、释放量及 NPU 收益需服务端监控确认。

## 环境变量与注意点

| 变量 | 默认值 | 含义 |
|---|---|---|
| `AFFINITY_ENABLED` | `false` | 仅接受 true/false；亲和模式同时选择 provider 和上下文开关 |
| `API_BASE` | 普通模式 `http://127.0.0.1:1234/v1` | 亲和模式必须显式提供根地址，不带 `/v1` |
| `API_KEY` | `lm-studio` | 模型服务密钥 |
| `MODEL_NAME` | `google/gemma-4-e4b` | 真实模型名称，需支持工具调用 |

输出预算为 512 token，temperature=0，top_p=1；思考模型也会消耗输出预算。若返回空正文或截断，先检查模型输出上限。样例不自动重试；`main` 展示流程，JUnit 负责事实及每轮工具次数验收。

## 验收

```bash
mvn test                  # 离线配置测试，不伪造模型回答
mvn -Preal-model test     # 配置测试 + 真实模型三轮工具验收
```

2026-10-27 使用本机 `google/gemma-4-e4b` 的真实输出：

```text
SDK: com.openjiuwen:agent-core-java:0.1.16
模式: 普通真实模型调用；昇腾亲和未验证
第 1 轮: 客户编号：C1001，信用评分：735
第 2 轮: 客户编号：C1001，信用评分：735
第 3 轮: 客户编号：C1001，信用评分：735
```

JUnit 共 4 项通过，其中 1 项真实验收包含三轮工具调用。完整真实运行输出见 [run_output.txt](run_output.txt)，验收输出见 [test_output.txt](test_output.txt)。本次验收范围是普通模型，昇腾亲和服务端未运行。性能统计及结论报告使用 [Java benchmark](../../benchmarks/java_ascend_affinity/README.md)。

## 官方依据

- [官方 KV Cache 示例](https://github.com/openJiuwen-ai/agent-core-java/tree/v0.1.16/examples/kv-cache-demo)
- [官方 ReAct 示例](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/examples/react_agent/ReActWeatherAgentExample.java)
- [InferenceAffinityModelClient](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/core/src/main/java/com/openjiuwen/core/foundation/llm/model_clients/InferenceAffinityModelClient.java)
- [Model.buildKvCacheInvokeKwargs](https://github.com/openJiuwen-ai/agent-core-java/blob/v0.1.16/core/src/main/java/com/openjiuwen/core/foundation/llm/Model.java)
