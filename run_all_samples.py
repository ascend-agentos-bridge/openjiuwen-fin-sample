"""一键回归: 顺序运行 samples/ 下全部样例，统计退出码并输出汇总。

用法:
    python run_all_samples.py          # 跑全部
    python run_all_samples.py 01 06    # 只跑指定编号
"""
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def main() -> int:
    args = {a.zfill(2) for a in sys.argv[1:]}
    samples = sorted(p for p in (ROOT / "samples").iterdir()
                     if p.is_dir() and (p / "main.py").exists())
    if args:
        samples = [p for p in samples if p.name[:2] in args]

    results: list[tuple[str, int, float]] = []
    for sample in samples:
        t0 = time.time()
        proc = subprocess.run(
            [sys.executable, "-X", "utf8", "main.py"],
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
