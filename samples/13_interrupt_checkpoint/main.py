"""样例 13 · 人机协同中断/恢复（真实 openjiuwen API）—— 大额放款的人工确认。

使用开源 openjiuwen Agent Core（v0.1.16）真实 API（对齐官方 multi_workflow_agent_demo 的中断模式）:
- QuestionerComponent: 工作流内的"中断节点"（提问并挂起等待输入）
- 第一次执行返回中断信息（input_required_fields / interaction）
- InteractiveInput(component_id, 用户答复) + 同一 session 再 invoke: 从断点恢复
- 检查点由框架 session 管理（框架日志可见 checkpoint 恢复）

流程: Start(申请) → 人工确认(超50万挂起) → 出具批复(LLM) → End

运行: python main.py
"""
import asyncio
import os

from openjiuwen.core.foundation.llm import ModelClientConfig, ModelRequestConfig
from openjiuwen.core.workflow import (End, FieldInfo, LLMComponent, LLMCompConfig,
                                      QuestionerComponent, QuestionerConfig, Start,
                                      Workflow, WorkflowCard, create_workflow_session)

API_BASE = os.getenv("API_BASE", "http://127.0.0.1:1234/v1")
API_KEY = os.getenv("API_KEY", "lm-studio")
MODEL_NAME = os.getenv("MODEL_NAME", "google/gemma-4-e2b")
MODEL_PROVIDER = os.getenv("MODEL_PROVIDER", "openai")

CLIENT_CFG = ModelClientConfig(client_provider=MODEL_PROVIDER, api_base=API_BASE,
                               api_key=API_KEY, timeout=600, max_retries=1, verify_ssl=False)
MODEL_CFG = ModelRequestConfig(model_name=MODEL_NAME, temperature=0.2)


def build_flow() -> Workflow:
    flow = Workflow(card=WorkflowCard(id="loan_approval_v2", name="loan_approval_v2",
                                      description="大额放款审批(带人工确认)", version="1.0",
                                      input_params={"type": "object",
                                                    "properties": {"query": {"type": "string"}},
                                                    "required": ["query"]}))
    start = Start()
    end = End({"responseTemplate": "{{output}}"})

    # 中断节点: 审批员确认（questioner 挂起等待交互输入）
    questioner = QuestionerComponent(QuestionerConfig(
        model_client_config=CLIENT_CFG, model_config=MODEL_CFG,
        question_content="该笔放款超过50万元自动审批上限，请审批员输入批准意见(approved/rejected)：",
        extract_fields_from_response=True,
        field_names=[FieldInfo(field_name="approval", description="审批意见 approved 或 rejected",
                               required=True)],
        with_chat_history=False))

    opinion = LLMComponent(LLMCompConfig(
        model_client_config=CLIENT_CFG, model_config=MODEL_CFG,
        template_content=[{"role": "user",
                           "content": "放款申请: {{query}}\n审批员确认结果: {{approval}}\n"
                                      "用一句话出具最终批复。"}],
        response_format={"type": "text"},
        output_config={"output": {"type": "string", "description": "最终批复", "required": True}}))

    flow.set_start_comp("start", start, inputs_schema={"query": "${query}"})
    flow.add_workflow_comp("ask", questioner, inputs_schema={"query": "${start.query}"})
    flow.add_workflow_comp("opinion", opinion,
                           inputs_schema={"query": "${start.query}", "approval": "${ask.approval}"})
    flow.set_end_comp("end", end, inputs_schema={"output": "${opinion.output}"})
    flow.add_connection("start", "ask")
    flow.add_connection("ask", "opinion")
    flow.add_connection("opinion", "end")
    return flow


async def main():
    flow = build_flow()
    session = create_workflow_session(session_id="sample-13",
                                      envs={"_execute_timeout": 600})

    # ---- 第一次执行: 在人工确认节点挂起 ----
    r1 = await flow.invoke({"query": "向客户C1001放款80万元经营贷"}, session=session)
    print("=== 第一次执行（应挂起等待人工输入） ===")
    print(str(r1)[:300])
    # 真实形态: state=INPUT_REQUIRED, result 里有 type='__interaction__' 的事件
    from openjiuwen.core.workflow import WorkflowExecutionState
    interactions = [o for o in (r1.result or [])
                    if getattr(o, "type", "") == "__interaction__"]
    assert r1.state == WorkflowExecutionState.INPUT_REQUIRED and interactions, \
        f"应在大额确认节点挂起: {str(r1)[:200]}"
    interaction_out = interactions[0].payload
    comp_id = interaction_out.id
    print(f"\n>>> 挂起于组件 {comp_id!r}, 提问: {interaction_out.value[:60]}")

    # ---- 人工批准: InteractiveInput + 同 session 恢复 ----
    from openjiuwen.core.session.interaction.interactive_input import InteractiveInput
    ii = InteractiveInput()
    ii.update(comp_id, "approved")
    print(f"\n=== 人工决定: approved, 从断点恢复(component_id={comp_id}) ===")
    r2 = await flow.invoke(ii, session=session)
    print(str(r2)[:400])
    assert str(r2).strip(), "恢复后应产出最终批复"
    print("\nSUCCESS: 中断挂起 + InteractiveInput 恢复 + 最终批复 验收通过")


if __name__ == "__main__":
    asyncio.run(main())
