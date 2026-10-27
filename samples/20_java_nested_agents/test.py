"""回归入口：启动 JUnit 单元测试及真实模型集成测试。"""
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
        [maven, "-q", "--batch-mode", "-Preal-model", "test"],
        cwd=Path(__file__).resolve().parent,
    )


if __name__ == "__main__":
    sys.exit(main())
