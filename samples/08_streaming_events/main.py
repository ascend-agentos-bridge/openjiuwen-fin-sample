"""样例 08 · 流式输出（真实 openjiuwen API）—— 信贷问答的实时渲染。

使用开源 openjiuwen Agent Core（v0.1.16）真实 API:
- Runner.run_agent_streaming(..., stream_modes=None): 异步迭代事件流
- 事件类型 OutputSchema.type:
    llm_reasoning —— thinking 模型的推理增量（前端可折叠展示）
    llm_output    —— 正文增量（实时渲染给用户）
    llm_usage     —— 用量汇总
- token 级流式由框架直通, 业务代码只管按 type 分流

运行: python main.py
"""
import asyncio
import os

from openjiuwen.core.foundation.llm import ModelClientConfig, ModelRequestConfig
from openjiuwen.core.runner import Runner
from openjiuwen.core.single_agent import AgentCard, ReActAgent, ReActAgentConfig

API_BASE = os.getenv("API_BASE", "http://127.0.0.1:1234/v1")
API_KEY = os.getenv("API_KEY", "lm-studio")
MODEL_NAME = os.getenv("MODEL_NAME", "google/gemma-4-e2b")
MODEL_PROVIDER = os.getenv("MODEL_PROVIDER", "openai")


async def main():
    agent = ReActAgent(card=AgentCard(id="stream_agent", description="流式信贷问答")).configure(
        ReActAgentConfig(
            model_client_config=ModelClientConfig(
                client_provider=MODEL_PROVIDER, api_base=API_BASE, api_key=API_KEY,
                timeout=600, max_retries=1, verify_ssl=False),
            model_config_obj=ModelRequestConfig(model_name=MODEL_NAME, temperature=0.2),
            prompt_template=[{"role": "system",
                              "content": "你是信贷产品说明员，回答控制在两句话以内。"}],
            max_iterations=3))

    reasoning, output, usage_events = [], [], 0
    print("=== 事件流（前几条原始事件） ===")
    shown = 0
    async for ev in Runner.run_agent_streaming(
            agent=agent,
            inputs={"query": "提前还房贷有什么要注意的？", "conversation_id": "sample-08"},
            stream_modes=None):
        etype = getattr(ev, "type", "?")
        content = (getattr(ev, "payload", {}) or {}).get("content", "")
        if etype == "llm_reasoning":
            reasoning.append(content)
            if shown < 3:
                print(f"  [{etype}] {content!r}")
                shown += 1
        elif etype == "llm_output":
            output.append(content)
        elif etype == "llm_usage":
            usage_events += 1

    answer = "".join(output)
    print("  ...")
    print("\n=== 实时拼接的最终答案 ===")
    print(answer)
    print(f"\n=== 统计 === reasoning增量={len(reasoning)}条, 正文增量={len(output)}条, "
          f"usage事件={usage_events}次")

    # ---- 验收断言 ----
    assert reasoning, "thinking 模型应产生 llm_reasoning 增量"
    assert answer.strip(), "llm_output 增量应拼出非空答案"
    assert usage_events >= 1, "应有 llm_usage 事件"
    print("\nSUCCESS: token 级流式(reasoning/output/usage 三类事件) 验收通过")


if __name__ == "__main__":
    asyncio.run(main())
