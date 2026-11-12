# 29 · 信贷客户记忆助手（Java Agent Core 分层记忆样例）

> **状态：Java 实现已落地并完成真实模型实测（2026-11-12）。** 包含一次性产品原型、Java 后端 + 页面、
> SDK 调研与 Milvus 本地基础设施；Java 版用 `agent-core-java:0.1.16 + agent-core-memory-java:0.1.16`
> 全配对，长期记忆 SQLite，图记忆按方案 B 使用 `InMemoryGraphStore`。Python 版暂不实现。

## 场景

客户周砚清与信贷智能体对话，咨询 80 万 / 36 期经营贷。智能体从对话里自动记住用户画像、语义记忆、
情景记忆、摘要记忆和变量记忆；换一个新对话后仍能召回；同时把对话中的实体与关系写入**图记忆**（当前演示为进程内 InMemory；Milvus 待 0.1.17），
支持实体/关系/情节维度检索。记忆加工（LLM 抽取、去重合并、落库）以加工流水线的方式可视化，并提供
**记忆模型服务配置**入口。

## 当前交付

| 文件 / 目录 | 内容 |
|---|---|
| [java/](java/README.md) | **真实 Java 实现**：内置 HttpServer + 原生页面；对话模型/记忆模型分开配置；LongTermMemory + SQLite、GraphMemory（InMemory）、多路召回、异步加工流水线；含真实截图、三步演示脚本与实测输出（见 [java/README.md](java/README.md) 的「演示脚本」） |
| [prototype/](prototype/README.md) | 可双击打开的产品原型：左侧历史会话 + 客户↔智能体对话；右侧三个 tab（长期记忆 / 图记忆 / 加工流程）+ 模型设置弹层；含 1440×900 截图 |
| [docker-compose.yml](docker-compose.yml) | 本地 Milvus standalone（etcd + MinIO + Milvus），**为 0.1.17 后的图记忆持久化准备**；当前按方案 B 用 InMemoryGraphStore |
| [research/RESEARCH-MEMORY.md](research/RESEARCH-MEMORY.md) | Java SDK 记忆 API、LongTermMemory / GraphMemory 事实与限制 |
| [research/RESEARCH-STORAGE.md](research/RESEARCH-STORAGE.md) | 本地 SQLite 全链路 probe（长期记忆部分） |

## 基础设施：Milvus（Docker Compose，为 0.1.17 准备）

> 当前样例按 **方案 B**：0.1.16 全配对 + `InMemoryGraphStore` 演示图记忆；下列 Milvus 基础设施已备好，
> 等 `agent-core-memory-java:0.1.17` 发布后切换图存储后端。

```bash
cd samples/29_credit_customer_memory
docker compose up -d          # 首次会拉取 etcd / MinIO / Milvus 镜像
docker compose ps

# Milvus:      localhost:19530
# 健康检查:    http://localhost:9091/healthz
# 数据目录:    ./.data/{etcd,minio,milvus}（已 gitignore）

docker compose down           # 停止，保留数据
docker compose down -v        # 清空容器数据（.data 目录可用 rm -rf 清掉）
```

- 默认镜像 `milvusdb/milvus:v2.6.0`（与 Java SDK `milvus-sdk-java 2.6.22` 配套）；可用
  `MILVUS_IMAGE=milvusdb/milvus:v2.5.x docker compose up -d` 覆盖。
- `docker compose config -q` 已校验通过；本机未预拉镜像，实际启动会在首次拉取时耗时。
- MinIO 端口不映射到宿主机，只供容器内部使用；Milvus 只暴露 SDK 端口和健康检查端口。

## 版本 / 能力约束（实现前必读）

1. **长期记忆**：Maven Central 上 `agent-core-memory-java` 只发布到 0.1.16，且与 `agent-core-java:0.1.17`
   二进制不兼容（23 个消费类 / 119 处符号无法解析）。完整 LongTermMemory 只能
   `agent-core-java:0.1.16` + `agent-core-memory-java:0.1.16` 全配对（storage probe 已实测 SQLite）。
2. **图记忆（方案 B）**：memory 0.1.16 已包含 `GraphMemory`，但 **0.1.16 core 只有 `InMemoryGraphStore`**；
   当前先用进程内图存储演示（重启后图数据需重建）。`MilvusGraphStore` 是 0.1.17 core 才加入、memory 0.1.17
   尚未发布，因此本目录的 Docker Compose 作为**升级目标**先备好；等 0.1.17 发布后再切换，不采用源码构建/自研后端。
3. **模型服务**：记忆抽取需要独立配置 LLM + Embedding；Milvus 地址是 0.1.17 切换图存储时的配置项。
   原型里的模型设置是演示态、只存在页面内存；真实 Java 样例从环境变量或 `java/config/model.local.json`
   读取，页面「模型设置」分对话模型/记忆模型两组，API Key 不入库、不提交。
4. **图关系兼容层（实测）**：0.1.16 `GraphMemory` 关系 prompt/parser 字段不一致，原始关系会被丢弃；
   样例用 `GraphRelationCompatModel` 做临时字段适配后，实测关系边已出现（如经营/OWNS）。
   这是兼容层，不是框架能力，等 0.1.17 或上游 PR 修复后删除；详见 [java/README.md](java/README.md)。

## 目录

```text
29_credit_customer_memory/
├── README.md                      本文件
├── docker-compose.yml             Milvus standalone 本地基础设施
├── .gitignore                     .data/ 等本地数据
├── python/                        Python 版（待实现，README 如实标注）
├── java/                          真实 Java 实现 + JUnit + 真实截图
│   ├── README.md / API.md
│   ├── config/model.example.json
│   ├── screenshots/
│   └── src/
├── prototype/                     一次性产品原型（throwaway，不合并 main）
│   ├── index.html
│   ├── README.md
│   ├── screenshots/
│   └── src/
└── research/                      SDK 事实与存储 probe（可独立阅读）
    ├── RESEARCH-MEMORY.md
    └── RESEARCH-STORAGE.md
```

## 下一步

1. **已完成**：Java 0.1.16 实现、页面、规则单测与真实模型集成测试；实测输出见 [java/README.md](java/README.md#实测输出2026-11-12)；
2. 图关系兼容层：0.1.16 GraphMemory 的 prompt/parser 字段不一致；样例已加临时适配器让关系边可用，
   待 agent-core 0.1.17 / 上游 PR 修复后删除；
3. `agent-core-memory-java:0.1.17` 发布后成对 bump、切 Milvus 图存储（Compose 已备好）并回归；
4. 根 README / `FEATURES.md` 登记 Java 样例（待评审后提交）。
