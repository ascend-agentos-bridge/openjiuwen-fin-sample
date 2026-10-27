"""验收入口：运行离线单元测试和调用真实模型的 JUnit 集成测试。"""
import os
from pathlib import Path
import subprocess
import sys


if __name__ == "__main__":
    sys.exit(subprocess.call(
        [os.getenv("MAVEN_CMD", "mvn"), "-q", "--batch-mode", "-Preal-model", "test"],
        cwd=Path(__file__).resolve().parent,
    ))
