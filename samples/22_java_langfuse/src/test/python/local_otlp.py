"""本地 OTLP/HTTP 协议验收接收器，不是 Langfuse 服务，不处理模型请求。"""
import base64
import gzip
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import threading

from opentelemetry.proto.collector.trace.v1.trace_service_pb2 import ExportTraceServiceRequest


def any_value(value):
    kind = value.WhichOneof("value")
    if kind == "array_value":
        return [any_value(v) for v in value.array_value.values]
    if kind == "kvlist_value":
        return {v.key: any_value(v.value) for v in value.kvlist_value.values}
    if kind == "bytes_value":
        return value.bytes_value.hex()
    return getattr(value, kind) if kind else None


class LocalReceiver:
    public_key = "pk-lf-local-protocol-test"
    secret_key = "sk-lf-local-protocol-test"

    def __init__(self, capture_path: Path, reject: bool = False):
        self.capture_path = capture_path
        self.capture = None
        self.requests = []
        self.failure = None
        receiver = self
        expected_auth = "Basic " + base64.b64encode(f"{self.public_key}:{self.secret_key}".encode()).decode()

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                try:
                    raw = self.rfile.read(int(self.headers["Content-Length"]))
                    if self.headers.get("Content-Encoding") == "gzip":
                        raw = gzip.decompress(raw)
                    request = ExportTraceServiceRequest.FromString(raw)
                    spans = []
                    for resource in request.resource_spans:
                        for scope in resource.scope_spans:
                            for span in scope.spans:
                                spans.append({
                                    "traceId": span.trace_id.hex(), "spanId": span.span_id.hex(),
                                    "parentSpanId": span.parent_span_id.hex(), "name": span.name,
                                    "startTimeUnixNano": span.start_time_unix_nano,
                                    "endTimeUnixNano": span.end_time_unix_nano,
                                    "statusCode": span.status.code,
                                    "attributes": {a.key: any_value(a.value) for a in span.attributes},
                                })
                    capture = {"path": self.path,
                               "contentType": self.headers.get("Content-Type"),
                               "authorizationMatched": self.headers.get("Authorization") == expected_auth,
                               "ingestionVersion": self.headers.get("x-langfuse-ingestion-version"),
                               "responseStatus": 401 if reject else 200, "spans": spans}
                    if self.path != "/api/public/otel/v1/traces" or not capture["authorizationMatched"]:
                        raise ValueError("OTLP 路径或认证不符")
                    receiver.requests.append({key: value for key, value in capture.items() if key != "spans"})
                    if receiver.capture is not None:
                        previous = {span["spanId"]: span for span in receiver.capture["spans"]}
                        previous.update({span["spanId"]: span for span in spans})
                        capture["spans"] = list(previous.values())
                    capture["requests"] = receiver.requests.copy()
                    receiver.capture = capture
                    capture_path.write_text(json.dumps(capture, ensure_ascii=False, indent=2) + "\n")
                    # 空 protobuf 即合法响应；拒绝模式验证 SDK 报错且业务仍可完成。
                    self.send_response(capture["responseStatus"])
                    self.send_header("Content-Type", "application/x-protobuf")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                except Exception as error:
                    receiver.failure = error
                    self.send_error(400, "local OTLP validation failed")

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    @property
    def base_url(self):
        return f"http://127.0.0.1:{self.server.server_address[1]}"

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *args):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
