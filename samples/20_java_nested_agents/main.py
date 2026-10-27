"""演示入口：仅启动 Java 业务主程序，调用真实模型。"""
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
