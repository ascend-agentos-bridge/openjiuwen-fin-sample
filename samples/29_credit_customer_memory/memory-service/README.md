# Agent Memory 服务（样例 29 依赖）

以 openJiuwen [agent-memory](https://atomgit.com/openJiuwen/agent-memory) 作为记忆服务端：
默认单容器、SQLite 真源、进程内向量/全文/图索引，不额外依赖 Redis/ES/Milvus；
真实 LLM（DeepSeek）负责抽取、更新、合并、关联与演进，Embedding 使用 LM Studio/OpenAI 兼容端点。

## 启动（当前验证方式：本机 Python）

```bash
cd samples/29_credit_customer_memory
export LLM_API_KEY=sk-...                 # DeepSeek / OpenAI 兼容
export EMBEDDER_BASE_URL=http://127.0.0.1:1234/v1
./scripts/start-agent-memory.sh
```

`docker-compose.yml`/`Dockerfile` 提供容器化参考；但 upstream 当前 dev auth 强制 loopback 绑定，
容器内 Portal 在 macOS 无法对外暴露，需等 upstream 支持 API Key/trusted 认证后再作为默认路径。

启动后：

- REST API：`http://127.0.0.1:8137`（`/healthz`、`/v1/*`）
- 记忆管理 Portal：`http://127.0.0.1:8137/portal/`
  - 需要一次性 access token 时：`POST /portal/api/launch`（带 `Origin`）返回 `portal_url`；
    Java 样例启动时会打印完整地址。
- 数据：SQLite `/data/memory.db`（compose volume `agent-memory-data`）。

## 依赖/边界

- `docker compose` 负责 Agent Memory；Java 样例可用同一 compose 的 `credit-agent` 服务，也可本机 Maven 启动。
- dev auth 仅用于本机演示，不得暴露到共享网络或生产。
- 首次构建需拉取 Python 镜像并 git clone 固定 commit，耗时几分钟。
