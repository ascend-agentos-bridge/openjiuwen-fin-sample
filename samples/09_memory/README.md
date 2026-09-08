# 样例 09 · 长期记忆 —— 跨会话记住客户还款偏好

> 使用**开源 openjiuwen Agent Core 真实 API**（`openjiuwen==0.1.16`）。对应特性清单 §9.2/§9.3 Memory。

## 场景

会话一客户陈述偏好（"等额本息、5 年内结清、月供压力小"），框架 LLM **自动抽取摘要记忆**并写入向量库；新会话语义检索命中；最后删除验证生命周期。

存储栈：`InMemoryKVStore` + `chroma`（本地持久化）+ LM Studio `nomic-embed`（768 维真实 embedding）。

## 运行

```bash
python main.py   # 需要 LLM 网关 + embedding 模型（默认本地 127.0.0.1:1234）
```

## 验收方式

1. 写入阶段 LLM 生成摘要记忆（`[summary] 客户C1001偏好等额本息还款方式...`）；
2. 新会话检索命中，**score≈0.92**（真实语义相似度），内容含 `等额本息`；
3. `delete_mem_by_id` 后同查询返回 0 条；
4. 最后一行 `SUCCESS: ...`，退出码 0。

实测输出（真实运行）：

```text
=== 记忆写入（LLM 自动摘要） ===
  [summary] 客户C1001偏好等额本息还款方式，计划5年内结清贷款，希望月供压力较小。

=== 语义检索（新会话） ===
  score=0.9157 客户C1001偏好等额本息还款方式，计划5年内结清贷款，希望月供压力较小。

=== 删除后检索 === 0 条

SUCCESS: 长期记忆写入(LLM摘要) + 语义检索 + 删除管理 验收通过
```

## 代码讲解（真实 API）

| 关键点 | API | 说明 |
|---|---|---|
| 引擎组装 | `ltm.register_store(kv, vector_store=chroma, embedding_model=APIEmbedding)` | **register_store 是 async**；缺任一组件会在运行时报错 |
| 引擎配置 | `ltm.set_config(MemoryEngineConfig(default_model_cfg, default_model_client_cfg))` | 记忆生成用的 LLM；不配则 add 报 `LLM is not initialized` |
| scope 配置 | `await ltm.set_scope_config("__default__", MemoryScopeConfig(embedding_cfg=...))` | **embedding 是 scope 级配置**，不配则检索日志报 `No embedding model available` 且静默返回空 |
| 写入 | `add_messages([BaseMessage], AgentMemoryConfig, user_id, gen_mem=True)` | LLM 从对话抽 summary/semantic/episodic 记忆 |
| 检索 | `search_user_history_summary(query, num, user_id)` | 返回 `MemResult(mem_info, score)`，**score 在 MemResult 上不在 MemInfo 上** |
| 删除 | `delete_mem_by_id(mem_id, user_id)` | 生产环境配合合规要求做数据删除 |

## 开发者注意（真实踩坑）

- LongTermMemory 装配有 4 个必配件（KV/向量库/embedding/LLM），**少一个都是运行时才报错**——按本样例的 build 顺序抄；
- `create_vector_store("chroma")` 参数是 `persist_directory`（不是 collection_name）；
- embedding 端点要写全：`{API_BASE}/embeddings`（APIEmbedding 直接 POST base_url）；
- `gen_mem=False` 只存原文不生成摘要，`search_user_history_summary` 将搜不到东西；
- 金融场景注意：记忆含客户信息，chroma 持久化目录要纳入数据安全管控（框架支持 crypto_key 加速码配置 `MemoryEngineConfig.crypto_key`）。
