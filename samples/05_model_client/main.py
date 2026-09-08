"""样例 05 · Model 直调与请求参数（真实 openjiuwen API）—— 信贷问答的参数工程。

使用开源 openjiuwen Agent Core（v0.1.16）真实 API:
- Model(model_client_config, model_config): 最轻量的模型直调入口（不建 Agent）
- ModelRequestConfig: model_name / temperature / top_p / max_tokens（默认 temperature=0.95, top_p=0.1）
- AssistantMessage: content / reasoning_content / finish_reason / usage_metadata
- 真实坑（跨模型复现）: thinking 模型的 max_tokens 包含推理 token,
  太小会把额度耗在 reasoning 上, content 为空且 finish_reason=stop（不报错）

运行: python main.py
"""
import asyncio
import os

from openjiuwen.core.foundation.llm import Model, ModelClientConfig, ModelRequestConfig

API_BASE = os.getenv("API_BASE", "http://127.0.0.1:1234/v1")
API_KEY = os.getenv("API_KEY", "lm-studio")
MODEL_NAME = os.getenv("MODEL_NAME", "google/gemma-4-e2b")
MODEL_PROVIDER = os.getenv("MODEL_PROVIDER", "openai")

QUESTION = "用一句话说明什么是等额本息还款。"


def make_model(**req_kwargs) -> Model:
    return Model(
        ModelClientConfig(client_provider=MODEL_PROVIDER, api_base=API_BASE,
                          api_key=API_KEY, timeout=600, max_retries=1,  # 本地慢推理: 超时放大 + 关闭重试
                          verify_ssl=False),
        ModelRequestConfig(model_name=MODEL_NAME, **req_kwargs))


async def main():
    # ---- 1. 正常调用: 显式低温度（风控问答建议） ----
    stable = make_model(temperature=0.2, max_tokens=4096)
    r1 = await stable.invoke([{"role": "user", "content": QUESTION}])
    print("=== 正常调用（temperature=0.2, max_tokens=4096） ===")
    print((r1.content or "(content 为空)")[:150])
    usage1 = r1.usage_metadata
    print(f"usage: input_tokens={usage1.input_tokens}, output_tokens={usage1.output_tokens}, "
          f"model_name={usage1.model_name}")
    assert usage1.model_name == MODEL_NAME
    assert usage1.input_tokens > 0 and usage1.output_tokens > 0

    # ---- 2. 真实坑（跨 thinking 模型复现）: max_tokens 太小被 reasoning 吃光 ----
    tiny = await make_model(temperature=0.2, max_tokens=30).invoke(
        [{"role": "user", "content": QUESTION}])
    print("\n=== max_tokens=30（thinking 模型） ===")
    print(f"finish_reason={tiny.finish_reason}, content_len={len(tiny.content or '')}, "
          f"output_tokens={tiny.usage_metadata.output_tokens}")
    print("=> 真实行为: 推理 token 计入 max_tokens, 额度全花在思考上, content 为空")

    # ---- 3. 验收断言（锚定确定性事实） ----
    assert not (tiny.content or "").strip(), "max_tokens=30 时正文应为空（额度耗在推理）"
    assert tiny.usage_metadata.output_tokens <= 30, "输出 token 不得超过 max_tokens"
    assert (r1.content or "").strip(), "充足额度下应有正文"
    assert len(r1.content) > len(tiny.content or "")
    print("\nSUCCESS: 参数生效(max_tokens/temperature) + usage 统计 + thinking 坑复现 验收通过")


if __name__ == "__main__":
    asyncio.run(main())
