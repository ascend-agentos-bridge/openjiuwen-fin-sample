"""样例 20 的回归入口；Agent 及模型调用全部由官方 Java SDK 执行。"""
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
    return subprocess.call(
        [maven, "-q", "--batch-mode", "compile", "exec:java"],
        cwd=Path(__file__).resolve().parent,
    )


if __name__ == "__main__":
    sys.exit(main())
