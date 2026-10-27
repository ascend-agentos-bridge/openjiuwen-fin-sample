"""一键回归: 纯 Maven 样例直接运行 JUnit；旧样例保留 Python 入口，按退出码汇总。

用法:
    python run_all_samples.py          # 跑全部
    python run_all_samples.py 01 06    # 只跑指定编号
"""
import os
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def main() -> int:
    args = {a.zfill(2) for a in sys.argv[1:]}
    samples = sorted(p for p in (ROOT / "samples").iterdir()
                     if p.is_dir() and ((p / "main.py").is_file() or (p / "pom.xml").is_file()))
    if args:
        samples = [p for p in samples if p.name[:2] in args]

    results: list[tuple[str, int, float]] = []
    for sample in samples:
        if (sample / "pom.xml").is_file() and not (sample / "main.py").is_file():
            command = [os.getenv("MAVEN_CMD", "mvn"), "-q", "--batch-mode", "-Preal-model", "test"]
        else:
            entry = "test.py" if (sample / "test.py").exists() else "main.py"
            command = [sys.executable, "-X", "utf8", entry]
        t0 = time.time()
        proc = subprocess.run(
            command,
            cwd=sample, capture_output=True, text=True, encoding="utf-8",
            errors="replace")
        elapsed = time.time() - t0
        status = "PASS" if proc.returncode == 0 else "FAIL"
        results.append((sample.name, proc.returncode, elapsed))
        suffix = "" if proc.returncode == 0 else "  <-- 见下方输出"
        print(f"[{status}] {sample.name:<28s} ({elapsed:.1f}s){suffix}")
        if proc.returncode != 0:
            print(proc.stdout[-1500:] if proc.stdout else "")
            print(proc.stderr[-1500:] if proc.stderr else "")

    passed = sum(1 for _, rc, _ in results if rc == 0)
    print(f"\n===== 汇总: {passed}/{len(results)} 通过 =====")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
