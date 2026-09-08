# 样例 11 · RAG 检索 —— 信贷政策知识库（真实 embedding）

> 使用**开源 openjiuwen Agent Core 真实 API**（`openjiuwen==0.1.16`）。对应特性清单 §8 Retrieval/RAG。

## 场景

5 条信贷政策文档经**真实 embedding 模型**（LM Studio `nomic-embed-text`，768 维）入库 chroma 向量库；两组查询验证语义排序。

## 运行

```bash
python main.py   # 需要 embedding 模型（默认本地 127.0.0.1:1234）
```

## 验收方式

1. 5 篇文档入库（向量维度 768）；
2. "提前还款要不要违约金" → **doc-001（消费贷免违约金）第一（0.8971）**，按揭补偿金条款进前三；
3. "申请贷款会查征信吗" → **doc-005（征信管理要求）第一（0.8970）**；
4. 最后一行 `SUCCESS: ...`，退出码 0。

实测输出（真实运行）：

```text
=== 入库 === 5 篇政策文档, 向量维度 768

=== 检索: 提前还款要不要违约金 ===
  doc-001 score=0.8971 《个人消费贷管理办法》: 提前还款可申请部分或全部提前结清，免收违约金，需提
  doc-005 score=0.8617 《征信管理要求》: ...
  doc-002 score=0.8616 《住房按揭贷款规程》: ...

=== 检索: 申请贷款会查征信吗 ===
  doc-005 score=0.8970 《征信管理要求》: ...

SUCCESS: 真实embedding入库+检索, 违约金政策文档进前二 验收通过
```

## 代码讲解（真实 API）

| 关键点 | API | 说明 |
|---|---|---|
| Embedding | `APIEmbedding(EmbeddingConfig(model_name, base_url="{API}/embeddings", api_key))` | `embed_documents` / `embed_query`，**base_url 要写到 `/embeddings` 端点** |
| 向量库 | `create_vector_store(VectorStoreConfig(store_provider="chroma"), chroma_path=...)` | 内置后端：chroma / milvus / gaussvector |
| 入库 | `await vs.add([{id, embedding, content, metadata}])` | **字段名是框架默认约定**：向量字段 `embedding`、文本字段 `content` |
| 检索 | `await vs.search(query_vec, top_k=3)` | 返回 `SearchResult(id, text, score, metadata)`——**文本在 `.text` 不是 `.content`** |

## 开发者注意（真实踩坑）

- 数据键名是隐性契约：向量键写 `vector` 会被静默跳过（日志 `Node has no embedding, skipping`），文本键要用 `content`；`SearchResult` 取文本用 `.text`——三处命名不一致，以本样例为准；
- `HybridRetriever`（向量+稀疏混合）在本机 chroma 后端下 retrieve 会阻塞——纯向量模式 `vs.search` 是稳定路径；混合检索建议 milvus 后端；
- 生产换 milvus 只改 `store_provider="milvus"` + 连接参数，embedding 与检索代码不变。
