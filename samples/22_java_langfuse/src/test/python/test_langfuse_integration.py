"""真实 LM Studio + 本地 OTLP 协议集成测试；所有断言只在测试中执行。"""
import json
import os
from pathlib import Path
import subprocess
import unittest

from local_otlp import LocalReceiver
from verify_capture import verify

ROOT = Path(__file__).resolve().parents[3]


class LangfuseIntegrationTest(unittest.TestCase):
    def run_sample(self, reject=False):
        evidence = ROOT / "target/acceptance"
        evidence.mkdir(parents=True, exist_ok=True)
        capture = evidence / ("rejected_otlp_capture.json" if reject else "local_otlp_capture.json")
        capture.unlink(missing_ok=True)
        with LocalReceiver(capture, reject=reject) as receiver:
            environ = os.environ.copy()
            environ.update(LANGFUSE_BASE_URL=receiver.base_url,
                           LANGFUSE_PUBLIC_KEY=receiver.public_key, LANGFUSE_SECRET_KEY=receiver.secret_key)
            result = subprocess.run([environ.get("MAVEN_CMD", "mvn"), "-q", "--batch-mode", "compile", "exec:java"],
                                    cwd=ROOT, env=environ, capture_output=True, text=True, timeout=300)
        output = result.stdout + result.stderr
        (evidence / ("rejected_run.log" if reject else "run.log")).write_text(output)
        self.assertIsNone(receiver.failure, output)
        self.assertIsNotNone(receiver.capture, output)
        return result, capture, output

    def test_business_succeeds_even_when_tracing_is_rejected(self):
        result, capture, output = self.run_sample(reject=True)
        self.assertEqual(0, result.returncode, "遥测上传失败不应被验收逻辑变成业务失败:\n" + output)
        self.assertIn("RESULT:", output)
        self.assertIn("401", output)
        self.assertNotIn("SUCCESS:", output)
        self.assertEqual(401, json.loads(capture.read_text())["responseStatus"])
        print("TRACING_FAILURE: HTTP 401 已记录，业务正常完成")

    def test_real_agent_exports_langfuse_observations(self):
        result, capture, output = self.run_sample()
        self.assertEqual(0, result.returncode, output)
        self.assertIn("RESULT:", output)
        self.assertNotIn("SUCCESS:", output)
        summary = verify(capture)
        self.assertTrue(all(tokens > 0 for tokens in summary["totalTokens"]))
        print("REAL_OTLP: " + json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    unittest.main()
