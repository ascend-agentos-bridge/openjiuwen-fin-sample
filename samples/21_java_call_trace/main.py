"""样例 21 的回归入口；Agent、模型调用及追踪均由官方 Java SDK 执行。"""
import os
from pathlib import Path
import shutil
import subprocess
import sys


def main() -> int:
    maven = os.getenv("MAVEN_CMD", "mvn")
    if shutil.which(maven) is None:
        print("需要 Java 17+ 和 Maven 3.9+；将 mvn 加入 PATH，或设置 MAVEN_CMD。", file=sys.stderr)
        return 1
    try:
        return subprocess.call(
            [maven, "-q", "--batch-mode", "compile", "exec:java"],
            cwd=Path(__file__).resolve().parent,
            timeout=300,
        )
    except subprocess.TimeoutExpired:
        print("Java sample 超过 300 秒仍未完成，请检查 Maven 下载和 LM Studio 服务。", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
