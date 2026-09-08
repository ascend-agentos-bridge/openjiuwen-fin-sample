"""样例 10 · Context Engine 上下文窗口（真实 openjiuwen API）—— 长对话的窗口管理。

使用开源 openjiuwen Agent Core（v0.1.16）真实 API:
- ContextEngine(ContextEngineConfig): 上下文引擎
- ContextEngineConfig(max_context_message_num, default_window_message_num):
  硬上限 + 默认窗口, 超限自动丢弃最旧消息（滑动窗口）
- create_context(history_messages=...) / ctx.get_messages(): 观察窗口裁剪结果
- ReActAgentConfig(context_engine_config=...): 引擎配置直接接入 Agent

运行: python main.py
"""
import asyncio
import os

from openjiuwen.core.context_engine import ContextEngine, ContextEngineConfig
from openjiuwen.core.foundation.llm import (ModelClientConfig, ModelRequestConfig,
                                            AssistantMessage, UserMessage)
from openjiuwen.core.runner import Runner
from openjiuwen.core.single_agent import AgentCard, ReActAgent, ReActAgentConfig

API_BASE = os.getenv("API_BASE", "http://127.0.0.1:1234/v1")
API_KEY = os.getenv("API_KEY", "lm-studio")
MODEL_NAME = os.getenv("MODEL_NAME", "google/gemma-4-e2b")
MODEL_PROVIDER = os.getenv("MODEL_PROVIDER", "openai")


async def main():
    # ---- 1. 独立引擎: 20 条历史进入 max=8 的窗口 ----
    engine = ContextEngine(ContextEngineConfig(max_context_message_num=8,
                                               default_window_message_num=6))
    history = []
    for i in range(10):
        history.append(UserMessage(content=f"第{i}轮: 咨询贷款产品{i}号，额度{i}万，利率3.{i}%。"))
        history.append(AssistantMessage(content=f"已记录您咨询产品{i}号（额度{i}万）。"))

    ctx = await engine.create_context(history_messages=history)
    window = ctx.get_messages()
    print("=== 窗口裁剪 ===")
    print(f"历史 {len(history)} 条 -> 窗口 {len(window)} 条")
    print(f"窗口首条: {window[0].content[:26]}")
    print(f"窗口末条: {window[-1].content[:26]}")
    assert len(window) == 8, f"窗口应为 8 条, 实际 {len(window)}"
    assert "第6轮" in window[0].content, "应丢弃最旧消息、保留最近轮次"
    assert "产品9" in window[-1].content

    # ---- 2. 引擎配置接入 Agent: 多轮真实对话 ----
    agent = ReActAgent(card=AgentCard(id="ctx_agent", description="窗口化信贷客服")).configure(
        ReActAgentConfig(
            model_client_config=ModelClientConfig(
                client_provider=MODEL_PROVIDER, api_base=API_BASE, api_key=API_KEY,
                timeout=600, max_retries=1, verify_ssl=False),
            model_config_obj=ModelRequestConfig(model_name=MODEL_NAME, temperature=0.2),
            prompt_template=[{"role": "system", "content": "你是信贷客服，简短回答。"}],
            context_engine_config=ContextEngineConfig(max_context_message_num=30,
                                                      default_window_message_num=20),
            max_iterations=3))

    conv = "sample-10-conv"
    questions = ["个人消费贷最高多少额度？", "我第一个问题问的是什么产品？"]
    answers = []
    for q in questions:
        r = await Runner.run_agent(agent=agent,
                                   inputs={"query": q, "conversation_id": conv})
        answers.append(r.get("output", ""))
        print(f"\n客户: {q}\n客服: {answers[-1][:120]}")

    assert all(a.strip() for a in answers), "两轮都应有回答"
    assert any(k in answers[0] for k in ("万", "额度", "元")), "第一轮应回答额度"
    assert any(k in answers[1] for k in ("消费贷", "额度", "贷款")), \
        f"第二轮应记住第一轮话题: {answers[1][:80]}"
    print("\nSUCCESS: 窗口裁剪(20->8, 保留最近) + 引擎配置接入 Agent 验收通过")


if __name__ == "__main__":
    asyncio.run(main())
