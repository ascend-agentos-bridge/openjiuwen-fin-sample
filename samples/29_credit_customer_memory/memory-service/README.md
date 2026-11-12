# Agent Memory 服务

样例 29 使用 openJiuwen [agent-memory](https://atomgit.com/openJiuwen/agent-memory) 作为独立记忆服务。
默认使用 SQLite 真源和进程内索引，不依赖 Redis/ES/Milvus。

## 启动

```bash
cd samples/29_credit_customer_memory
export LLM_API_KEY=sk-...                 # DeepSeek API Key
export EMBEDDER_BASE_URL=http://127.0.0.1:1234/v1
./scripts/start-agent-memory.sh
```

脚本负责：

1. 首次 clone 固定 commit 的 agent-memory，创建 Python 3.11 venv 并安装；
2. 启动 HTTP 服务；
3. 获取并打印一次性 Portal 地址。

```text
Agent Memory API : http://127.0.0.1:8137
Agent Memory Web : http://127.0.0.1:8137/portal/#access=...
```

## 地址和接口

- 健康检查：`GET /healthz`
- 记忆接口：`POST /v1/search`、`/v1/add`、`/v1/list`、`/v1/update`、`/v1/delete`、`/v1/evolve`
- Portal：`GET /portal/`
- Portal 启动 token：

```bash
curl -X POST http://127.0.0.1:8137/portal/api/launch \
  -H 'Content-Type: application/json' \
  -H 'Origin: http://127.0.0.1:8137' -d '{}'
```

## 数据

- config：`memory-service/config.yml`
- 本机 SQLite：由 `MEMORY_DB_PATH` 指定，默认在 `.runtime/agent-memory/data/memory.db`
- 容器路径：`/data/memory.db`（`docker-compose.yml` 的 volume）

## 配置

模型默认值：

- LLM：`https://api.deepseek.com` + `deepseek-flash`
- Embedding：`http://127.0.0.1:1234/v1` + `text-embedding-nomic-embed-text-v1.5`

页面「模型设置」会写入 `memory-service/model.local.env`，启动脚本会自动读取；重启 Agent Memory 后生效。
该文件已 gitignore，不要提交 API Key。

## docker compose

`docker-compose.yml` 提供 Agent Memory 容器配置，但当前 upstream dev auth 强制 loopback 绑定，
容器内 Portal 在 macOS 无法对外暴露。因此本机演示以 `scripts/start-agent-memory.sh` 为准；
容器化需要 upstream 支持 API Key/trusted 认证后再启用。

dev auth 仅用于本机演示，不要暴露到共享网络或生产环境。
