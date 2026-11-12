# 29 · 信贷客户记忆助手

openJiuwen **Agent Memory** 作为独立记忆服务，Java Agent Core 通过 `ExternalMemoryRail` 接入。
页面把同一个客户问题分别交给「有记忆」和「无记忆」两个 Agent，并排展示回答，用来核对记忆召回是否真的改善了客户体验。

## 实现入口

- Java 接入与运行： [java/README.md](java/README.md)
- Agent Memory 服务： [memory-service/README.md](memory-service/README.md)
- 集成说明与接口细节： [java/AGENT-MEMORY-INTEGRATION.md](java/AGENT-MEMORY-INTEGRATION.md)
- 交互原型（演示数据）： [prototype/README.md](prototype/README.md)

## 版本

- `agent-core-java:0.1.16`
- `agent-core-memory-java:0.1.16`
- Agent Memory：上游 commit `2a77650c16f8fe073d8713bc91fc703a7233359d`
- 对话模型默认：DeepSeek `deepseek-flash`
- Embedding 默认：本机 LM Studio `text-embedding-nomic-embed-text-v1.5`

## 目录

```text
29_credit_customer_memory/
├── memory-service/                  Agent Memory 配置、Dockerfile、说明
├── scripts/start-agent-memory.sh    启动 Agent Memory 并打印 REST/Portal 地址
├── java/                            Java Agent Core 接入、页面、JUnit、截图
├── prototype/                       产品原型（演示数据，不连接真实后端）
├── python/                          Python 版，待实现
├── docker-compose.yml               Agent Memory 容器配置（当前本机不以此为主路径）
└── docker-compose.milvus.yml        旧 Milvus 方案，历史参考
```

## 启动

先启动 Agent Memory：

```bash
cd samples/29_credit_customer_memory
export LLM_API_KEY=sk-...                                      # DeepSeek API Key
export EMBEDDER_BASE_URL=http://127.0.0.1:1234/v1               # LM Studio / OpenAI 兼容 embedding
./scripts/start-agent-memory.sh
```

脚本会打印：

```text
Agent Memory API : http://127.0.0.1:8137
Agent Memory Web : http://127.0.0.1:8137/portal/#access=...
```

再启动 Java 页面：

```bash
cd samples/29_credit_customer_memory/java
export CHAT_API_BASE=https://api.deepseek.com
export CHAT_API_KEY="$LLM_API_KEY"
export CHAT_MODEL=deepseek-flash
export AGENT_MEMORY_BASE_URL=http://127.0.0.1:8137
mvn -q -DskipTests compile
mvn -q exec:java
```

访问：

- 业务页面：`http://127.0.0.1:8099/`
- Agent Memory Portal：`http://127.0.0.1:8137/portal/`
- Agent Memory REST：`http://127.0.0.1:8137/healthz`、`/v1/*`

本机验证用上面的 Python 启动脚本。`docker-compose.yml` 也能构建 Agent Memory 镜像，但 Agent Memory 当前
dev auth 强制 loopback 绑定，容器内 Portal 在 macOS 无法对外暴露，因此不作为默认启动路径。

## 页面

- 左侧：Agent Memory 连接状态、会话、4 个演示脚本。
- 中间：客户问题后的双回答。
  - 有记忆：`ExternalMemoryRail` 注入 `<memory-context>` 后回答。
  - 无记忆：同模型、同 prompt，但不挂 rail。
- 右侧：
  - 记忆演进：按 Agent Memory 的 `tier/target` 展示用户画像、语义、情景、摘要、变量五组记忆；
  - 本轮召回：展示本轮检索命中的记忆；
  - 服务：Agent Memory 地址、Portal、配置状态。

顶部还提供「模型设置」和「清空记忆」。

## 演示脚本

页面左下可选 4 个脚本：

| 脚本 | 操作 | 观察点 |
|---|---|---|
| ① 跨会话召回 | 首轮陈述 → 新建会话 → 追问额度/期限/还款方式 | 有记忆能复述历史，基线重新询问 |
| ② 偏好召回 | 一键写入偏好 → 自动追问还款方案 | 有记忆按「等额本息」推荐，基线重新问偏好 |
| ③ 风险召回 | 一键写入「2023 年逾期已结清」→ 自动追问审批注意点 | 有记忆主动提到逾期已结清，基线给泛化清单 |
| ④ 关系召回 | 一键写入「太太林岚是共同借款人」→ 自动追问材料 | 有记忆直接复用林岚和共同借款人关系，基线重新确认 |

②③④ 的背景记忆是召回场景的示例数据，直接写入 Agent Memory；追问和两个回答都由真实模型生成。
脚本没有实现「让 Agent Memory 自动 supersede」的演示：当前 dedup/evolver 对这类纠正更倾向 ADD，
不保证自动生成版本链。

## 模型配置

「模型设置」分两组：

- 对话模型：Java Agent Core 使用；
- Agent Memory 模型：LLM 与 Embedding。

默认已填 DeepSeek 官方地址和 `deepseek-flash`，API Key 留空。保存后：

- 对话配置写入 `java/config/model.local.json`；
- Agent Memory 配置写入 `memory-service/model.local.env`；
- 重启 Java 进程 / Agent Memory 后生效。

DeepSeek 不提供 Embedding，默认仍使用本机 LM Studio。两个文件都已 gitignore。

## 清空记忆

「清空记忆」调用 Agent Memory `DELETE /v1/delete`，按真实 scope 分组、以 `mode=purge` 清空当前身份下
全部状态的个人记忆（active / superseded / forgotten / archived），然后重建会话。

## 测试

```bash
cd java
mvn test
```

当前本地结果：34 个测试通过，其中 2 个是真实模型/Agent Memory 集成测试。

## 限制

- Agent Memory 以 dev auth 启动，固定 `local/developer` 身份，仅用于本机演示。
- Portal access token 一次性，获取方式见 `memory-service/README.md`。
- SQLite 是记忆真源；进程内索引重启后重建，召回结果可能有差异。
- 旧的内嵌 SQLite / InMemoryGraphStore 实现只作为历史代码保留，不是当前入口。
- Python 版未实现。
