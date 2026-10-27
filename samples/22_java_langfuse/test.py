"""测试入口：JUnit 单元测试 + 真实模型/OTLP 集成测试。"""
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent


if __name__ == "__main__":
    result = subprocess.call([os.getenv("MAVEN_CMD", "mvn"), "-q", "--batch-mode", "test"], cwd=ROOT)
    if result == 0:
        result = subprocess.call(
            [sys.executable, "-m", "unittest", "discover", "-s", "src/test/python", "-p", "test_*_integration.py", "-v"],
            cwd=ROOT,
        )
    sys.exit(result)
