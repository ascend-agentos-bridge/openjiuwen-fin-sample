# 29 · 信贷客户记忆助手（Agent Memory + MemoryRail 双回答）

> **实现状态（2026-11-12）**：openJiuwen **agent-memory** 作为独立记忆服务端（HTTP API + Web Portal），
> Java Agent Core 通过 `ExternalMemoryRail` + 自定义 `MemoryProvider` 接入；每轮同时返回
> **有记忆 / 无记忆基线** 两个真实回答，并可在 Portal 直接查看、纠正、演进记忆。
> Python 版待实现；旧的 SQLite/InMemoryGraphStore 内嵌方案保留为 Legacy 参考，不再是主入口。

## 目录

```text
29_credit_customer_memory/
├── docker-compose.yml          Agent Memory + Java 应用（一键启动）
├── docker-compose.milvus.yml   旧 Milvus 基础设施（历史参考）
├── memory-service/             Agent Memory 服务配置 / Dockerfile / 说明
├── scripts/start-agent-memory.sh   启动并打印 Agent Memory Web（Portal）地址
├── java/                       Java Agent Core 接入 + 双回答页面 + JUnit + 真实截图
├── prototype/                  产品原型（演示数据；双回答 + 记忆演进/召回；旧关联图仅为设计稿）
│   └── legacy/                 旧三 tab 原型
├── python/                     Python 版（待实现，README 如实标注）
└── research/                   SDK/存储调研
```

## 快速启动

```bash
cd samples/29_credit_customer_memory
export LLM_API_KEY=sk-...   # DeepSeek API Key；对话/记忆默认模型 deepseek-flash
export EMBEDDER_BASE_URL=http://127.0.0.1:1234/v1           # 本机 LM Studio / 任意 embedding
./scripts/start-agent-memory.sh                            # 启动 Agent Memory 并打印 Web Portal
# 另一个终端：启动 Java 业务页面
cd java && mvn -q -DskipTests compile && mvn -q exec:java
```

启动后脚本打印 Agent Memory API / Web Portal 地址；Java 启动日志再打印业务页面：

- **Agent Memory API**：`http://127.0.0.1:8137`
- **Agent Memory Web Portal**：`http://127.0.0.1:8137/portal/#access=...`（直接看记忆原始数据）
- **业务页面**：`http://127.0.0.1:8099/`（双回答 + 记忆工作台）

> `docker-compose.yml` 也提供了 agent-memory 容器化配置；但 upstream 当前 dev auth 强制
> loopback 绑定，容器内 Portal 在 macOS 无法对外暴露。本机验证以上面脚本为准；容器化需等
> upstream 支持 API Key/trusted 认证并调整绑定策略。

## 这轮解决的问题

| 需求 | 落点 |
|---|---|
| Agent Memory 作为 REST 记忆服务端，Agent Core 接入 | `memory-service/` + `AgentMemoryClient` / `AgentMemoryProvider` |
| 若未预集成，做两者集成 | `ExternalMemoryRail` 驱动 `MemoryProvider`；`DualAnswerRunner` 真实跑通 |
| 依赖用 docker compose 管理 | `docker-compose.yml`：agent-memory + credit-agent |
| 展示抽取/更新/合并/关联/演进 | `add(infer=true)` / `update` SUPERSEDE / `evolve`；Portal + 右侧工作台 |
| 对话回答同时给「有召回 / 无召回」两个结果 | `DualAnswerRunner`：同模型两 Agent，一挂 rail 一不挂；真实双回答 |
| 基于当前界面重新设计原型 + 演示脚本 | `prototype/` 根 index.html 新设计；README/页内 4 步脚本 |
| 启动时告诉 Agent Memory Web 地址 | 启动脚本 / Java 启动日志打印 Portal URL |

## 演示脚本

完整「输入 / 预期记忆变化 / 预期召回 / 预期双回答差异」见：
[prototype/README.md](prototype/README.md)（原型演示）与
[java/README.md](java/README.md#演示脚本每步输入--预期记忆--预期双回答)（真实 Java 接入）。
页面左下有一个**演示脚本选择框**（跨会话保留），4 个脚本分别覆盖：

1. **跨会话召回**：首轮陈述 → 新会话追问额度/期限/还款方式；有记忆准确复述，基线反问。
2. **偏好召回 · 个性化推荐**：写入客户偏好 → 自动追问方案 → 有记忆按偏好推荐，基线重新询问。
3. **风险召回 · 主动提示**：写入逾期已结清 → 自动追问审批注意点 → 有记忆主动复用风险信息。
4. **关系召回 · 免重复询问**：写入共同借款人林岚 → 自动追问材料 → 有记忆直接复用关系。

每条记忆支持「遗忘」；右上角「模型设置」配置
对话模型 + Agent Memory 模型；「清空记忆」一键 purge 当前 scope 并重开演示。

## 版本 / 限制

- Java 侧：`agent-core-java:0.1.16` + `agent-core-memory-java:0.1.16`；Agent Memory 固定上游 commit
  `2a77650c16f8fe073d8713bc91fc703a7233359d`（见 `memory-service/Dockerfile`）。
- Agent Memory 默认单容器：SQLite 真源 + 进程内向量/全文/图索引，无 Redis/ES/Milvus 依赖；
  dev auth 仅本机演示，Portal token 一次性。
- 旧的嵌入式 SQLite/InMemoryGraphStore 方案不再作为主入口；`docker-compose.milvus.yml` 仅为历史参考。
- Python 版待实现，未提供虚构运行输出。
