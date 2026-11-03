"""一键回归: 老样例跑 main.py / test.py / JUnit；新结构样例（场景目录 + python/、java/）跑 UT。

用法:
    python run_all_samples.py                       # 跑全部
    python run_all_samples.py 01 06                 # 只跑指定编号（老样例）
    python run_all_samples.py 26_consumer_loan_precheck_ask_user  # 按编号/目录名过滤（新样例）
"""
import os
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def _matches(sample: Path, requested: list[str]) -> bool:
    """编号前缀（01）或目录名片段（26_consumer_loan_precheck_ask_user）都能筛样例。"""
    if not requested:
        return True
    for arg in requested:
        if sample.name.startswith(arg) or arg.zfill(2) == sample.name[:2] or arg in sample.name:
            return True
    return False


def _collect_units(selected: list[Path]) -> list[tuple[str, Path, list[str]]]:
    """把每个样例展开成可执行的回归单元: (显示名, 工作目录, 命令)。

    - 老结构: samples/<编号>_<名称>/ 下直接有 main.py / test.py / pom.xml；
    - 新结构: samples/<编号_场景_技术>/python/ 跑 unittest，同级 java/ 跑 Maven JUnit。
    """
    units: list[tuple[str, Path, list[str]]] = []
    for sample in selected:
        python_dir, java_dir = sample / "python", sample / "java"
        legacy = (sample / "main.py").is_file() or (sample / "pom.xml").is_file()
        if legacy:
            if (sample / "pom.xml").is_file() and not (sample / "main.py").is_file():
                command = [os.getenv("MAVEN_CMD", "mvn"), "-q", "--batch-mode", "-Preal-model", "test"]
            else:
                entry = "test.py" if (sample / "test.py").exists() else "main.py"
                command = [sys.executable, "-X", "utf8", entry]
            units.append((sample.name, sample, command))
            continue

        if (python_dir / "main.py").is_file() or (python_dir / "tests").is_dir():
            units.append((
                f"{sample.name}/python",
                python_dir,
                [sys.executable, "-X", "utf8", "-m", "unittest", "discover", "-s", "tests", "-v"],
            ))
        if (java_dir / "pom.xml").is_file():
            units.append((
                f"{sample.name}/java",
                java_dir,
                [os.getenv("MAVEN_CMD", "mvn"), "-q", "--batch-mode", "-Preal-model", "test"],
            ))
    return units


def main() -> int:
    requested = sys.argv[1:]
    samples = sorted(p for p in (ROOT / "samples").iterdir() if p.is_dir())
    selected = [p for p in samples if _matches(p, requested)]
    units = _collect_units(selected)
    if not units:
        print("没有匹配到可回归的样例")
        return 1

    results: list[tuple[str, int, float]] = []
    for label, workdir, command in units:
        t0 = time.time()
        try:
            proc = subprocess.run(
                command,
                cwd=workdir, capture_output=True, text=True, encoding="utf-8",
                errors="replace")
            returncode, stdout, stderr = proc.returncode, proc.stdout, proc.stderr
        except FileNotFoundError:
            returncode = 127
            stdout = ""
            stderr = (f"命令不存在: {command[0]}。Java 样例需要 JDK 17+ 与 Maven 3.9+，"
                      f"可安装后重跑，或用环境变量 MAVEN_CMD / JAVA_HOME 指定。")
        elapsed = time.time() - t0
        status = "PASS" if returncode == 0 else "FAIL"
        results.append((label, returncode, elapsed))
        suffix = "" if returncode == 0 else "  <-- 见下方输出"
        print(f"[{status}] {label:<32s} ({elapsed:.1f}s){suffix}")
        if returncode != 0:
            print(stdout[-1500:] if stdout else "")
            print(stderr[-1500:] if stderr else "")

    passed = sum(1 for _, rc, _ in results if rc == 0)
    print(f"\n===== 汇总: {passed}/{len(results)} 通过 =====")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
