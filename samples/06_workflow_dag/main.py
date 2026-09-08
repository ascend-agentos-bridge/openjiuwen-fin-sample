"""样例 06 · Workflow 工作流（真实 openjiuwen API）—— 消费贷审批流。

使用开源 openjiuwen Agent Core（v0.1.16）真实 API（对齐官方 workflow_agent 示例）:
- Start / End: 起止组件（End 的 responseTemplate 决定最终返回）
- LLMComponent + LLMCompConfig: 抽参组件（output_config 声明结构化输出字段）
- ToolComponent + RestfulApi: 工具组件（调用授信评分 HTTP API）
- flow.set_start_comp / add_workflow_comp(inputs_schema=${ref.field}) / add_connection
- WorkflowAgent.bind_workflows + invoke: 以 Agent 形态驱动工作流

流程: Start(query) → 抽取客户号(LLM) → 查评分(Tool) → 生成审批意见(LLM) → End

运行: python main.py
"""
import asyncio
import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

os.environ.setdefault("SSRF_PROTECT_ENABLED", "false")  # 本地 mock API 在环回地址

from openjiuwen.core.application.workflow_agent import WorkflowAgent, WorkflowAgentConfig
from openjiuwen.core.foundation.llm import ModelClientConfig, ModelRequestConfig
from openjiuwen.core.foundation.tool import RestfulApi, RestfulApiCard
from openjiuwen.core.runner import Runner

from openjiuwen.core.workflow import (End, LLMComponent, LLMCompConfig, Start,
                                      ToolComponent, ToolComponentConfig, Workflow,
                                      WorkflowCard, create_workflow_session)

API_BASE = os.getenv("API_BASE", "http://127.0.0.1:1234/v1")
API_KEY = os.getenv("API_KEY", "lm-studio")
MODEL_NAME = os.getenv("MODEL_NAME", "google/gemma-4-e2b")
MODEL_PROVIDER = os.getenv("MODEL_PROVIDER", "openai")


def model_client() -> ModelClientConfig:
    return ModelClientConfig(client_provider=MODEL_PROVIDER, api_base=API_BASE,
                             api_key=API_KEY, timeout=600, verify_ssl=False)


def make_score_tool(port: int) -> RestfulApi:
    """授信评分 mock API → RestfulApi 工具（固定 id 供 ToolComponent 引用）。"""

    class ScoreAPI(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            q = parse_qs(urlparse(self.path).query).get("q", [""])[0]
            score = {"C1001": 82, "C1002": 55}.get(q, 50)
            body = json.dumps({"customer_id": q, "score": score}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    server = ThreadingHTTPServer(("127.0.0.1", 0), ScoreAPI)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    actual_port = server.server_address[1]

    tool = RestfulApi(card=RestfulApiCard(
        id="credit_score_tool", name="credit_score", description="按客户号查询授信评分",
        input_params={"type": "object",
                      "properties": {"q": {"description": "客户号", "type": "string"}},
                      "required": ["q"]},
        url=f"http://127.0.0.1:{actual_port}/score", headers={}, method="GET"))
    Runner.resource_mgr.add_tool(tool)
    return tool


def build_flow() -> Workflow:
    flow = Workflow(card=WorkflowCard(id="loan_approval", name="loan_approval",
                                      description="消费贷审批工作流", version="1.0",
                                      input_params={"type": "object",
                                                    "properties": {"query": {"description": "客户请求",
                                                                             "type": "string"}},
                                                    "required": ["query"]}))

    start = Start()  # 0.1.16: Start 无参构造, 输入通过 set_start_comp(inputs_schema) 声明
    end = End({"responseTemplate": "{{output}}"})

    # 组件1: LLM 抽取客户号（response_format=json 走 JSON 解析, output_config 校验字段）
    extract = LLMComponent(LLMCompConfig(
        model_client_config=model_client(),
        model_config=ModelRequestConfig(model_name=MODEL_NAME, temperature=0.2),
        template_content=[{"role": "user", "content":
                           "从下面的客户请求中提取客户号（C 开头的编号），只输出 JSON。"
                           '格式: {"customer_id": "..."}\n请求: {{query}}'}],
        response_format={"type": "json"},
        output_config={"customer_id": {"type": "string", "description": "客户号",
                                       "required": True}}))

    # 组件2: Tool 查评分（inputs_schema 从上一组件取值）
    score_comp = ToolComponent(ToolComponentConfig(tool_id="credit_score_tool"))
    # 组件3: LLM 生成审批意见
    opinion = LLMComponent(LLMCompConfig(
        model_client_config=model_client(),
        model_config=ModelRequestConfig(model_name=MODEL_NAME, temperature=0.2),
        template_content=[{"role": "user", "content":
                           "你是信贷审批助理。客户请求: {{query}}\n授信评分结果: {{score}}\n"
                           "评分≥70 建议自动批复, <70 建议人工复审。用两句话给出审批建议。"}],
        response_format={"type": "text"},
        output_config={"opinion": {"type": "string", "description": "审批意见",
                                   "required": True}}))

    # 注册组件 + 数据流映射（${组件.字段}）
    flow.set_start_comp("start", start, inputs_schema={"query": "${query}"})
    flow.add_workflow_comp("extract", extract, inputs_schema={"query": "${start.query}"})
    flow.add_workflow_comp("score", score_comp, inputs_schema={"q": "${extract.customer_id}"})
    flow.add_workflow_comp("opinion", opinion,
                           inputs_schema={"query": "${start.query}", "score": "${score.data}"})
    flow.set_end_comp("end", end, inputs_schema={"output": "${opinion.opinion}"})

    # 拓扑连接
    flow.add_connection("start", "extract")
    flow.add_connection("extract", "score")
    flow.add_connection("score", "opinion")
    flow.add_connection("opinion", "end")
    return flow


async def main():
    make_score_tool(0)  # 工具已注册进 Runner.resource_mgr
    flow = build_flow()

    print("=== 运行工作流: 客户 C1001 ===")
    # 本地 4B thinking 模型较慢, 通过 session envs 调大工作流执行超时(框架默认 60s)。
    # 键名是框架常量 WORKFLOW_EXECUTE_TIMEOUT 的值 "_execute_timeout"
    session = create_workflow_session(session_id="wf-001",
                                      envs={"_execute_timeout": 600})
    result = await flow.invoke({"query": "客户C1001申请消费贷30万，请审批"}, session=session)
    output = getattr(result, "output", None) or str(result)
    print("=== 工作流最终输出 ===")
    print(str(output)[:400])

    assert output and str(output).strip(), "工作流应产出最终意见"
    assert "评分" in str(output) or "审批" in str(output) or "70" in str(output), \
        f"意见应引用评分或审批结论: {str(output)[:150]}"
    print("\nSUCCESS: Start→LLM抽参→Tool评分→LLM意见→End 全链路执行 验收通过")


if __name__ == "__main__":
    asyncio.run(main())
