"""样例 09 · 长期记忆（真实 openjiuwen API）—— 跨会话记住客户还款偏好。

使用开源 openjiuwen Agent Core（v0.1.16）真实 API:
- LongTermMemory: 框架长期记忆引擎（需注册 KV 存储 + 向量库 + Embedding + LLM）
- add_messages(gen_mem=True): LLM 自动从对话中抽取摘要记忆
- search_user_history_summary: 语义检索历史记忆（真实 embedding）
- delete_mem_by_id: 记忆生命周期管理

存储栈: InMemoryKVStore + chroma(本地持久化) + LM Studio nomic-embed(768维)。

运行: python main.py
"""
import asyncio
import os
import shutil
import tempfile

from openjiuwen.core.foundation.llm import (BaseMessage, ModelClientConfig,
                                            ModelRequestConfig)
from openjiuwen.core.foundation.store import InMemoryKVStore, create_vector_store
from openjiuwen.core.foundation.store.base_embedding import EmbeddingConfig
from openjiuwen.core.memory import (AgentMemoryConfig, LongTermMemory,
                                    MemoryEngineConfig, MemoryScopeConfig)
from openjiuwen.core.retrieval import APIEmbedding

API_BASE = os.getenv("API_BASE", "http://127.0.0.1:1234/v1")
API_KEY = os.getenv("API_KEY", "lm-studio")
MODEL_NAME = os.getenv("MODEL_NAME", "google/gemma-4-e2b")
EMBED_MODEL = os.getenv("EMBED_MODEL", "text-embedding-nomic-embed-text-v1.5")


async def build_ltm(db_dir: str) -> LongTermMemory:
    emb_cfg = EmbeddingConfig(model_name=EMBED_MODEL,
                              base_url=f"{API_BASE}/embeddings", api_key=API_KEY)
    emb = APIEmbedding(emb_cfg)
    ltm = LongTermMemory()
    await ltm.register_store(InMemoryKVStore(),
                             vector_store=create_vector_store("chroma", persist_directory=db_dir),
                             embedding_model=emb)
    ltm.set_config(MemoryEngineConfig(
        default_model_cfg=ModelRequestConfig(model_name=MODEL_NAME, temperature=0.2),
        default_model_client_cfg=ModelClientConfig(client_provider="openai", api_base=API_BASE,
                                                   api_key=API_KEY, timeout=600, verify_ssl=False)))
    await ltm.set_scope_config("__default__", MemoryScopeConfig(embedding_cfg=emb_cfg))
    return ltm


async def main():
    db_dir = os.path.join(tempfile.gettempdir(), "ojw_ltm_09_real")
    shutil.rmtree(db_dir, ignore_errors=True)  # 演示从空库开始
    ltm = await build_ltm(db_dir)
    cfg = AgentMemoryConfig(enable_long_term_mem=True, enable_summary_memory=True)

    # ---- 1. 会话一: 客户陈述偏好 → LLM 自动抽取摘要记忆 ----
    r = await ltm.add_messages(
        [BaseMessage(role="user",
                     content="客户C1001偏好等额本息，计划5年内结清，希望月供压力小一些")],
        cfg, user_id="C1001", gen_mem=True)
    print("=== 记忆写入（LLM 自动摘要） ===")
    for u in r.summary:
        print(f"  [{u.mem_type.value}] {u.summary}")

    # ---- 2. 新会话语义检索: 命中跨会话记忆 ----
    hits = await ltm.search_user_history_summary("C1001 适合什么还款方式",
                                                 num=3, user_id="C1001")
    print("\n=== 语义检索（新会话） ===")
    for h in hits:
        print(f"  score={h.score:.4f} {h.mem_info.content}")
    assert hits, "应检索到客户偏好记忆"
    assert "等额本息" in hits[0].mem_info.content

    # ---- 3. 记忆管理: 删除后不可再检索 ----
    mem_id = hits[0].mem_info.mem_id
    await ltm.delete_mem_by_id(mem_id, user_id="C1001")
    hits2 = await ltm.search_user_history_summary("C1001 适合什么还款方式",
                                                  num=3, user_id="C1001")
    print(f"\n=== 删除后检索 === {len(hits2)} 条")
    assert len(hits2) == 0, "删除后不应再命中"

    print("\nSUCCESS: 长期记忆写入(LLM摘要) + 语义检索 + 删除管理 验收通过")


if __name__ == "__main__":
    asyncio.run(main())
