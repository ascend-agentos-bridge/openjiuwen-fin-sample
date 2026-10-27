# Java 版 Agent Core 样例（银行金融场景）

本指南对应 **Java 版 openJiuwen Agent Core**，当前样例适配 **`com.openjiuwen:agent-core-java:0.1.16`**，覆盖 `samples/20_*` 至 `samples/24_*`。Python 版的 `pip install openjiuwen`、`main.py` 目录约定不适用于 Java 样例。

Java 业务入口在 `src/main/java/`，验收在 `src/test/java/`，通过 Maven 分别运行。返回[仓库总览与 Python 指南](README.md)。

## 先跑通 23：工具校验、审批恢复与异常重试

前提：**JDK 17+、Maven 3.9+**，以及支持工具调用的真实模型服务。样例默认连接本机 LM Studio：`http://127.0.0.1:1234/v1`，模型为 `google/gemma-4-e4b`。

在仓库根目录执行：

```bash
java -version
mvn -version
cd samples/23_java_tool_rails
mvn -q compile exec:java
```

流程是：申请放款 50000 元 → 自定义金额校验 → 暂停等审批 → 提交演示审批结果 → 恢复原会话 → 临时错误重试一次 → 返回结果。银行工具是本地演示业务，不连接真实账户；模型调用是真实的。

切换到其他 OpenAI 兼容模型服务时，在执行 Maven 前设置：

```bash
export API_BASE="https://你的网关/v1"
export API_KEY="你的模型服务密钥"
export MODEL_NAME="支持工具调用的模型名"
mvn -q compile exec:java
```

完整代码和实测输出见 [23 java_tool_rails](samples/23_java_tool_rails/README.md)。

## Java 样例索引

| 样例 | 业务入口 | 演示能力 |
|---|---|---|
| [20 java_nested_agents](samples/20_java_nested_agents/README.md) | `CreditNestedAgents.main` | 审批主管 → 风控专员 → 征信核验员，三层 Agent 委派 |
| [21 java_call_trace](samples/21_java_call_trace/README.md) | `CreditCallTrace.main` | Agent、模型、工具的完整调用链日志与 JSONL |
| [22 java_langfuse](samples/22_java_langfuse/README.md) | `CreditLangfuse.main` | 使用官方 OTel handler 向 Langfuse 导出轨迹 |
| [23 java_tool_rails](samples/23_java_tool_rails/README.md) | `CreditToolRails.main` | 自定义工具校验、审批中断恢复、异常重试 |
| [24 java_ascend_affinity](samples/24_java_ascend_affinity/README.md) | `CreditAffinity.main` | 官方 KV Cache 亲和开关及多轮压缩；普通调用验收 |

五个样例均可在各自目录通过 `mvn -q compile exec:java` 启动，入口类由该目录的 `pom.xml` 配置。样例 22 还需要 Langfuse 配置，见下文。

## SDK 依赖与环境变量

各样例的 `pom.xml` 直接依赖官方 Java SDK：

```xml
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>agent-core-java</artifactId>
    <version>0.1.16</version>
</dependency>
```

不需要安装 Python 版 `openjiuwen`。Java API 的包名、配置类和方法签名以 [agent-core-java](https://github.com/openJiuwen-ai/agent-core-java) 的官方示例与源码为准。

| 环境变量 | Java 样例默认值 | 作用范围 |
|---|---|---|
| `API_BASE` | `http://127.0.0.1:1234/v1` | 20–24：模型 OpenAI 兼容地址；24 亲和模式使用不带 /v1 的根地址 |
| `API_KEY` | `lm-studio` | 20–24：模型服务密钥 |
| `MODEL_NAME` | `google/gemma-4-e4b` | 20–24：聊天模型名称，需支持工具调用 |
| `MODEL_PROVIDER` | `openai` | 20、23 可配置；21、22 使用 OpenAI 兼容客户端 |
| `JAVA_HOME` | 系统配置 | 指向 JDK 17+，可通过 `mvn -version` 检查 Maven 实际使用的 JDK |

Java 样例的默认模型与 Python 样例不同，不要直接套用 Python 版的默认值。其他专有配置以各样例 README 为准。

## Java 样例目录结构

以不含 Python 启动器的样例 23 为例：

```text
samples/23_java_tool_rails/
├── pom.xml                            Maven 依赖、Java 版本、入口类、测试配置
├── README.md                          场景、代码讲解、运行、验收与实测输出
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── CreditToolRails.java     Java main 与业务示例
│   │   └── resources/
│   │       └── logback-tool-rails.xml  日志配置
│   └── test/
│       └── java/
│           ├── CreditToolRailsTest.java
│           └── CreditToolRailsIntegrationTest.java
├── run_output.txt                     真实演示输出
└── test_output.txt                    真实测试输出
```

`main` 只组织业务流程、展示结果和释放资源；断言、测试观察器、验收计数器放在测试目录。新 Java 样例使用 Java/Maven 原生入口，不为适配统一回归脚本添加 Python 启动器。

现有 20–22 仍保留历史 `main.py` / `test.py` 辅助脚本；它们不代表 Java 项目的目录要求，也不替代 Java 业务入口。22 的本地 OTLP 集成验收还使用测试目录中的 Python 接收器，详见该样例 README。

## 单元测试与真实模型验收

在相应 Java 样例目录执行：

```bash
# 20–24：离线 JUnit 单元测试，不连接模型
mvn test

# 20、21、23、24：单元测试 + 真实模型集成验收
mvn -Preal-model test
```

20、21、23、24 的真实模型验收使用 JUnit `integration` 标签，默认 `mvn test` 排除这些用例，`real-model` profile 显式启用。模型不可达时验收会失败，不用假模型或保存的回答代替执行。

22 的本地 OTLP 端到端验收需要额外测试依赖和接收器，不能套用上述 `real-model` 命令；按 [22 的验收说明](samples/22_java_langfuse/README.md) 执行。

## 22 的 Langfuse 配置

从仓库根目录进入样例 22，再配置并运行 `CreditLangfuse.main`：

```bash
cd samples/22_java_langfuse
export LANGFUSE_BASE_URL="https://cloud.langfuse.com"
export LANGFUSE_PUBLIC_KEY="你的项目 Public Key"
export LANGFUSE_SECRET_KEY="你的项目 Secret Key"
mvn -q compile exec:java
```

`LANGFUSE_BASE_URL` 填实例根地址，不包含 `/api/public/otel`。本地 OTLP 接收验收与真实 Langfuse 平台入库是两个验证范围；平台入库、界面展示仍需配置实际实例后验证。

## 可选：仓库统一回归

Java 业务入口和 JUnit 可以完全通过 Maven 运行。希望一起回归多种语言的样例时，可在仓库根目录使用额外的 Python 调度脚本：

```bash
python3 run_all_samples.py 20 21 23 24
```

脚本对纯 Maven 样例 23、24 直接调用 `mvn -Preal-model test`；对已有 20–22 使用其历史测试入口。22 的额外本地验收前提见其 README。`MAVEN_CMD` 可指定调度脚本使用的 Maven 可执行文件路径，不影响直接执行 `mvn`。

## 算力亲和 benchmark 与结论报告

[Java benchmark](benchmarks/java_ascend_affinity/README.md) 是独立 Maven 工程，支持固定模型请求、多轮工具 Agent、并发、重复、预热及 off/on 对比，结束自动输出 `report.md`、JSONL、CSV 和配置快照。默认 `normal`，只需普通真实模型；`compare` 需要支持官方亲和协议的服务。样例 24 用 `AFFINITY_ENABLED=true` 开启，详见该样例 README。

报告将指标分为客户端可采集统计和服务端两部分，缺失字段标不可用。没有昇腾服务端证据时，结论明确亲和效果未验证。benchmark 不加入默认全样例回归，以免意外执行长时间压测；其真实模型 JUnit 使用 `mvn -Preal-model test` 独立验收。
