# Java 样例 API 契约（29 · 信贷客户记忆助手）

前后端同源，静态页面由 Java 内置 HttpServer 提供（默认 `http://127.0.0.1:8099/`）。
所有 JSON 使用 UTF-8；时间字符串 `HH:mm` 或 ISO 均可，前端只展示。

## GET /api/bootstrap

```json
{
  "configured": true,
  "chat": { "provider": "OpenAI 兼容", "model": "google/gemma-4-e4b", "apiBase": "http://127.0.0.1:1234/v1", "temperature": 0.2 },
  "memory": { "model": "google/gemma-4-e4b", "embeddingModel": "text-embedding-nomic-embed-text-v1.5", "apiBase": "http://127.0.0.1:1234/v1", "embeddingApiBase": "http://127.0.0.1:1234/v1/embeddings" },
  "graphStore": "InMemoryGraphStore（演示，重启后重建）",
  "activeSessionId": "s-20261112-001",
  "sessions": [
    { "id": "s-20261112-001", "title": "经营贷咨询", "createdAt": "2026-11-12 10:20", "messageCount": 2, "preview": "客户：你好，我想申请…" }
  ]
}
```

## POST /api/sessions

请求：`{}`。响应：`{ "sessionId": "s-..." , "title": "新的咨询" }`。
新会话只影响对话列表；长期记忆/图记忆继续保留。

## GET /api/sessions/{id}/state

返回该会话当前完整状态（前端轮询用）：

```json
{
  "session": { "id": "s-...", "title": "经营贷咨询", "createdAt": "2026-11-12 10:20" },
  "messages": [
    { "id": "m1", "role": "customer", "text": "你好…", "ts": "10:20" },
    { "id": "m2", "role": "agent", "text": "您好…", "ts": "10:20", "cites": 3, "graphCites": 2, "processed": { "added": 2, "graph": 3 } }
  ],
  "memories": [
    { "id": "mem-...", "layer": "profile", "content": "周砚清，杭州精密制造企业主", "version": 1, "createdAt": "10:21", "updatedFrom": null }
  ],
  "variables": [ { "name": "plannedYears", "value": "5 年", "previous": null } ],
  "graph": {
    "entities": [ { "id": "e1", "label": "周砚清", "type": "person" } ],
    "relations": [ { "id": "r1", "source": "e1", "target": "e2", "label": "申请" } ],
    "episodes": [ { "id": "ep1", "label": "2026-11-12 首次咨询经营贷" } ]
  },
  "recall": {
    "query": "还款方式",
    "channels": { "vector": 2, "kv": 1, "graph": 2 },
    "entries": [ { "source": "向量", "label": "…" }, { "source": "KV", "label": "…" }, { "source": "图", "label": "…" } ]
  },
  "graphRecall": { "hits": [ { "kind": "entity", "label": "周砚清", "type": "person" } ] },
  "pipeline": {
    "running": true,
    "runs": [
      { "id": "run-1", "trigger": "对话后异步触发", "at": "10:20:31", "status": "running",
        "stats": { "llmCalls": 0, "memoriesAdded": 0, "graphEntitiesAdded": 0, "graphRelationsAdded": 0, "graphEpisodesAdded": 0, "memoriesUpdated": 0, "memoriesDeleted": 0, "writeTargets": [] },
        "sample": { "input": ["客户：…"], "outputs": { "memories": [], "graph": [] } } }
    ]
  }
}
```

`layer` 取值：`profile` / `semantic` / `episodic` / `summary` / `variable`（变量也放 memories 或单独 variables，前端可合并显示）。
`pipeline.runs[0]` 是最近一次；运行中也要返回。前端每 800–1200ms 轮询 `/state`，直到 `pipeline.running=false`。

## POST /api/chat

请求：`{ "sessionId": "s-...", "message": "客户说的话" }`。
响应（同步返回聊天回复，不等待记忆抽取）：

```json
{ "sessionId": "s-...", "reply": "智能体回复文本", "recall": { ...同上... }, "processing": true }
```

后端在响应后异步执行记忆加工：LongTermMemory.addMessages(gen_mem=true) + GraphMemory.addMemory，
期间 `/state` 的 pipeline.running=true，完成后更新 memories/graph/pipeline，前端轮询可见。

## POST /api/settings

请求（两个模型配置分开；apiKey 不返回）：

```json
{
  "chat": { "provider": "OpenAI 兼容", "apiBase": "http://127.0.0.1:1234/v1", "apiKey": "lm-studio", "model": "google/gemma-4-e4b", "temperature": 0.2 },
  "memory": { "provider": "OpenAI 兼容", "apiBase": "http://127.0.0.1:1234/v1", "apiKey": "lm-studio", "model": "google/gemma-4-e4b", "embeddingApiBase": "http://127.0.0.1:1234/v1/embeddings", "embeddingApiKey": "lm-studio", "embeddingModel": "text-embedding-nomic-embed-text-v1.5" },
  "graphStore": "in_memory"
}
```

响应：`{ "ok": true, "configured": true, "chat": {...}, "memory": {...} }`。
保存到本机 `java/config/model.local.json`（gitignored）；API Key 只在本次运行/本机配置中使用。
`apiKey` 字段缺省、空字符串或 `******` 时后端保留原 Key，不回显也不覆盖。

`graph.relations` 由 0.1.16 GraphMemory 抽取；样例内置 `GraphRelationCompatModel` 临时修复关系字段名，
实测可返回关系边（模型/措辞不同可能波动）；前端按普通边渲染，空列表时显示实体/情节。

## 前端要求（web/）

- 最左侧历史会话（可收起/展开），中间客户↔智能体对话，右侧三个 tab：长期记忆 / 图记忆 / 加工流程。
- 图记忆 tab：SVG 实体关系图（点击节点看关系）、图召回列表、情节；当前 InMemoryGraphStore。
- 加工流程 tab：最近一次加工 stages + stats + 加工样本（输入上下文 → 产出记忆/图对象）+ 历史；轮询刷新。
- 顶部「模型设置」：**对话模型**与**记忆模型**两组配置，另有图存储说明（InMemory / Milvus 待 0.1.17）；保存后调用 `/api/settings`。
- 与原型保持一致视觉：浅色、银行红强调、中文；无外部 CDN / 构建步骤；只用原生 JS/CSS/SVG。
