"""验收真实 Java sample 写出的日志，不生成或模拟任何模型响应。"""
import argparse
import hashlib
import json
from pathlib import Path
import re


def verify(log_dir: Path, expect_error: bool = False) -> None:
    jsonl = log_dir / "call_trace.jsonl"
    assert jsonl.exists(), "缺少 call_trace.jsonl，尚未输出完整结构化轨迹"
    records = [json.loads(line) for line in jsonl.read_text().splitlines() if line]
    assert records, "JSONL 轨迹不能为空"
    assert len({r["runId"] for r in records}) == 1
    assert len({r["sessionId"] for r in records}) == 1
    assert all(r["schemaVersion"] == 1 and r["caseId"] == "credit_c1001" for r in records)
    text_lines = (log_dir / "call_trace.log").read_text().splitlines()
    text_records = [json.loads(line.split(" - ", 1)[1].split(" ", 1)[1]) for line in text_lines]
    assert text_records == records, "可读日志与 JSONL 事件必须逐条一致"

    config = next(r["data"] for r in records if r["event"] == "CONFIG")
    for key in ("promptSha256", "toolSchemaSha256"):
        assert re.fullmatch(r"[0-9a-f]{64}", config[key]), f"缺少配置指纹: {key}"
    if config["sampleSourceSha256"] is not None:
        assert re.fullmatch(r"[0-9a-f]{64}", config["sampleSourceSha256"])
    assert config["prompt"] and config["tools"] and config["modelRequestConfig"]
    assert config["promptSha256"] == hashlib.sha256(config["prompt"].encode()).hexdigest()
    canonical_tool = json.dumps(config["tools"][0], sort_keys=True, ensure_ascii=False, separators=(",", ":"))
    assert config["toolSchemaSha256"] == hashlib.sha256(canonical_tool.encode()).hexdigest(), "工具 schema 指纹必须与键顺序无关"
    assert "apiKey" not in config and "api_key" not in config

    trace_records = [r for r in records if r["event"] == "TRACE"]
    assert trace_records
    assert len({r["traceId"] for r in trace_records}) == 1
    latest = {r["data"]["span"]["invokeId"]: r for r in trace_records}
    spans = [r["data"]["span"] for r in latest.values()]
    root = next(s for s in spans if s["invokeType"] == "chain")
    for record in trace_records:
        span = record["data"]["span"]
        assert record["traceId"] == span["traceId"] == root["traceId"]
        assert all(key in span for key in ("inputs", "outputs", "error", "metaData", "startTime", "endTime"))
        if span["invokeId"] != root["invokeId"]:
            assert span["parentInvokeId"] == root["invokeId"]
        if span["endTime"]:
            assert record["data"]["durationMs"] >= 0

    if expect_error:
        assert root["status"] == "error"
        assert any(s["invokeType"] == "llm" and s["error"].get("message") for s in spans if s["error"])
        failure = next(r for r in records if r["event"] == "RUN_ERROR")
        assert failure["traceId"] == root["traceId"] and failure["data"]["stackTrace"]
        assert not any(r["event"] == "SUCCESS" for r in records)
    else:
        assert all(s["status"] == "finish" and s["outputs"] is not None for s in spans)
        llms = [r["data"] for r in latest.values() if r["data"]["span"]["invokeType"] == "llm"]
        assert len(llms) >= 2 and all(r["span"]["inputs"]["messages"] for r in llms)
        assert all("usage" in r and r["usageStatus"] in ("reported_by_sdk", "not_reported_or_zero") for r in llms)
        assert any(r["span"]["outputs"]["outputs"].get("tool_calls") for r in llms)
        tool = next(s for s in spans if s["invokeType"] == "plugin")
        arguments = tool["inputs"]["inputs"]
        if isinstance(arguments, str):
            arguments = json.loads(arguments)
        assert arguments["customer_id"] == "C1001"
        report = tool["outputs"]["outputs"]["report_id"]
        result = next(r for r in records if r["event"] == "RESULT")
        assert result["traceId"] == root["traceId"] and report in json.dumps(result["data"], ensure_ascii=False)
        validation = next(r for r in records if r["event"] == "VALIDATION")
        assert validation["data"]["scope"] == "sample_contract" and validation["data"]["passed"]
        assert records[-1]["event"] == "SUCCESS"
    print(f"PASS: {len(records)} 条真实日志事件，内容/父子关系/配置/落盘一致，expect_error={expect_error}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("log_dir", nargs="?", type=Path, default=Path(__file__).parent / "logs")
    parser.add_argument("--expect-error", action="store_true")
    args = parser.parse_args()
    verify(args.log_dir, args.expect_error)
