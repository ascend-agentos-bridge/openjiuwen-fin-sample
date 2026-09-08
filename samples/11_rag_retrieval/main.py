"""样例 11 · RAG 检索（真实 openjiuwen API）—— 信贷政策知识库问答。

使用开源 openjiuwen Agent Core（v0.1.16）真实 API:
- APIEmbedding: 真实 embedding 模型（LM Studio nomic-embed, 768 维）
- create_vector_store(VectorStoreConfig(store_provider="chroma")): 检索向量库
- vector_store.add / search: 文档入库与向量检索
- （VectorStoreConfig/数据键名: embedding 是向量字段, content 是文本字段——框架默认名）

场景: 5 条信贷政策文档入库, 问"提前还款要不要违约金",
真实 embedding 语义检索应把 doc-001(消费贷免违约金)排进前二。

运行: python main.py
"""
import asyncio
import os
import shutil
import tempfile

from openjiuwen.core.foundation.store.base_embedding import EmbeddingConfig
from openjiuwen.core.retrieval import APIEmbedding
from openjiuwen.core.retrieval.vector_store.store import (VectorStoreConfig,
                                                          create_vector_store)

API_BASE = os.getenv("API_BASE", "http://127.0.0.1:1234/v1")
API_KEY = os.getenv("API_KEY", "lm-studio")
EMBED_MODEL = os.getenv("EMBED_MODEL", "text-embedding-nomic-embed-text-v1.5")

POLICY = [
    ("doc-001", "《个人消费贷管理办法》: 提前还款可申请部分或全部提前结清，免收违约金，需提前3个工作日预约。"),
    ("doc-002", "《住房按揭贷款规程》: 放款后12个月内提前结清收取还款金额1%的补偿金，满12个月后免收。"),
    ("doc-003", "《信用卡分期须知》: 分期手续费按期收取，提前结清剩余手续费不退还。"),
    ("doc-004", "《个人经营贷细则》: 单户额度上限300万元，需提供经营流水。"),
    ("doc-005", "《征信管理要求》: 贷款审批前必须查询征信报告，硬查询1个月内不超过3次。"),
]


async def main():
    emb = APIEmbedding(EmbeddingConfig(model_name=EMBED_MODEL,
                                       base_url=f"{API_BASE}/embeddings", api_key=API_KEY))
    db_dir = os.path.join(tempfile.gettempdir(), "ojw_rag_11")
    shutil.rmtree(db_dir, ignore_errors=True)
    vs = create_vector_store(VectorStoreConfig(store_provider="chroma",
                                               collection_name="credit_policy"),
                             chroma_path=db_dir)

    # ---- 1. 文档入库: 真实 embedding 向量化 ----
    doc_vecs = await emb.embed_documents([text for _, text in POLICY])
    data = [{"id": did, "embedding": vec, "content": text, "metadata": {"source": did}}
            for (did, text), vec in zip(POLICY, doc_vecs)]
    await vs.add(data)
    print(f"=== 入库 === {len(data)} 篇政策文档, 向量维度 {len(doc_vecs[0])}")

    # ---- 2. 语义检索: 真实 query embedding ----
    for query in ["提前还款要不要违约金", "申请贷款会查征信吗"]:
        hits = await vs.search(await emb.embed_query(query), top_k=3)
        print(f"\n=== 检索: {query} ===")
        for h in hits:
            print(f"  {h.id} score={h.score:.4f} {h.text[:38]}")
        assert hits and len(hits) <= 3

    # ---- 3. 验收断言: 语义排序正确性 ----
    hits = await vs.search(await emb.embed_query("提前还款要不要违约金"), top_k=2)
    top_ids = {h.id for h in hits}
    assert "doc-001" in top_ids, f"消费贷免违约金条款应进前二, 实际: {top_ids}"
    assert hits[0].score > 0.85, f"语义相关性应显著: {hits[0].score:.4f}"
    print("\nSUCCESS: 真实embedding入库+检索, 违约金政策文档进前二 验收通过")


if __name__ == "__main__":
    asyncio.run(main())
