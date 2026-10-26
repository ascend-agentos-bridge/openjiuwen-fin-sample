"""Java sample 22 启动入口；本地模式只替换遥测接收端，模型仍由 Java SDK 调用真实 LM Studio。"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parent


def run_java(environ: dict, reject: bool = False) -> int:
    maven = environ.get("MAVEN_CMD", "mvn")
    if shutil.which(maven) is None:
        print("需要 JDK 17+ 和 Maven 3.9+；将 mvn 加入 PATH 或设置 MAVEN_CMD。", file=sys.stderr)
        return 1
    try:
        result = subprocess.run([maven, "-q", "--batch-mode", "compile", "exec:java"],
                                cwd=ROOT, env=environ, capture_output=True, text=True, timeout=300)
    except subprocess.TimeoutExpired:
        print("Java sample 超过 300 秒，请检查 Maven、模型服务和遥测接收端。", file=sys.stderr)
        return 1
    (ROOT / "logs" / ("rejected_run.log" if reject else "run.log")).write_text(result.stdout + result.stderr)
    print(result.stdout, end="")
    print(result.stderr, end="", file=sys.stderr)
    return result.returncode


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--local-test", action="store_true", default=os.getenv("LANGFUSE_LOCAL_TEST") == "1")
    parser.add_argument("--reject-export", action="store_true")
    args = parser.parse_args()
    if args.reject_export and not args.local_test:
        parser.error("--reject-export 仅用于 --local-test")
    (ROOT / "logs").mkdir(exist_ok=True)
    environ = os.environ.copy()
    if not args.local_test:
        if not environ.get("LANGFUSE_PUBLIC_KEY") or not environ.get("LANGFUSE_SECRET_KEY"):
            print("请配置 LANGFUSE_BASE_URL / LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY；"
                  "无实例时用 --local-test 验证实际 OTLP 导出。", file=sys.stderr)
            return 1
        return run_java(environ)

    try:
        from local_otlp import LocalReceiver
        from verify_capture import verify
    except ImportError:
        print("本地协议验证需要：python3 -m pip install -r requirements-local.txt", file=sys.stderr)
        return 1
    capture_path = ROOT / "logs" / ("rejected_otlp_capture.json" if args.reject_export else "local_otlp_capture.json")
    capture_path.unlink(missing_ok=True)
    with LocalReceiver(capture_path, args.reject_export) as receiver:
        environ.update(LANGFUSE_BASE_URL=receiver.base_url,
                       LANGFUSE_PUBLIC_KEY=receiver.public_key, LANGFUSE_SECRET_KEY=receiver.secret_key)
        code = run_java(environ, args.reject_export)
    if receiver.failure:
        raise receiver.failure
    if args.reject_export:
        assert code != 0 and receiver.capture and receiver.capture["responseStatus"] == 401
        assert "SUCCESS:" not in (ROOT / "logs/rejected_run.log").read_text()
        print("NEGATIVE_PASS: 本地接收端拒绝导出，Java 非零退出，未打印 SUCCESS")
        return 0
    if code != 0:
        return code
    print("LOCAL_OTLP_VERIFIED: " + json.dumps(verify(capture_path), ensure_ascii=False))
    print("本轮仅验证本地 OTLP 接收；未验证 Langfuse 平台入库和界面展示。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
