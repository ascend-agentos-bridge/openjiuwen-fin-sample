"""业务示例启动器；验收测试请运行 test.py。"""
import os
from pathlib import Path
import subprocess
import sys


if __name__ == "__main__":
    sys.exit(subprocess.call(
        [os.getenv("MAVEN_CMD", "mvn"), "-q", "--batch-mode", "compile", "exec:java"],
        cwd=Path(__file__).resolve().parent,
    ))
