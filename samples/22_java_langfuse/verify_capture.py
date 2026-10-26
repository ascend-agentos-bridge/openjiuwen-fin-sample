"""验收实际收到的 OTLP 数据；不模拟模型或 Langfuse 平台。"""
import json
from pathlib import Path


def verify(path: Path) -> dict:
    assert path.exists(), "缺少实际 OTLP 收包记录"
    capture = json.loads(path.read_text())
    assert capture["path"] == "/api/public/otel/v1/traces"
    assert capture["authorizationMatched"] is True
    assert capture["ingestionVersion"] == "4"
    assert capture["contentType"].startswith("application/x-protobuf")
    spans = capture["spans"]
    assert len(spans) == 4, f"应收到 Agent/2 LLM/工具共 4 个 span，实际 {len(spans)}"
    assert len({s["traceId"] for s in spans}) == 1
    roots = [s for s in spans if not s["parentSpanId"]]
    assert len(roots) == 1
    root = roots[0]
    for span in spans:
        attrs = span["attributes"]
        assert span["endTimeUnixNano"] > span["startTimeUnixNano"]
        assert span["statusCode"] == 1
        assert attrs["langfuse.session.id"] == root["attributes"]["langfuse.session.id"]
        assert attrs["langfuse.trace.name"] == "credit_langfuse_review"
        assert attrs["langfuse.trace.metadata.case_id"] == "credit_c1001"
        assert attrs["openjiuwen.trace.id"]
        assert json.loads(attrs["langfuse.observation.input"]) is not None
        assert json.loads(attrs["langfuse.observation.output"]) is not None
        if span is not root:
            assert span["parentSpanId"] == root["spanId"]
    assert root["attributes"]["langfuse.observation.type"] == "agent"
    llms = [s for s in spans if s["attributes"]["langfuse.observation.type"] == "generation"]
    tools = [s for s in spans if s["attributes"]["langfuse.observation.type"] == "tool"]
    assert len(llms) == 2 and len(tools) == 1
    assert all(s["attributes"]["gen_ai.request.model"] for s in llms)
    assert all("gen_ai.usage.total_tokens" in s["attributes"] for s in llms)
    tool = tools[0]["attributes"]
    args = json.loads(tool["langfuse.observation.input"])
    if isinstance(args, str):
        args = json.loads(args)
    assert args["customer_id"] == "C1001"
    result = json.loads(tool["langfuse.observation.output"])
    report_id = result["report_id"]
    assert report_id in root["attributes"]["langfuse.observation.output"]
    return {"traceId": root["traceId"], "spanCount": len(spans),
            "types": [s["attributes"]["langfuse.observation.type"] for s in spans],
            "totalTokens": [s["attributes"]["gen_ai.usage.total_tokens"] for s in llms],
            "reportId": report_id}


if __name__ == "__main__":
    print(json.dumps(verify(Path(__file__).parent / "logs/local_otlp_capture.json"), ensure_ascii=False))
