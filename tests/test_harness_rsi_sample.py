"""sample25 的离线 unittest 入口；真实模型验收运行样例 test.py。"""

import importlib.util
from pathlib import Path
import sys

path = Path(__file__).resolve().parents[1] / "samples/25_harness_rsi/test.py"
spec = importlib.util.spec_from_file_location("sample25_checks", path)
checks = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = checks
spec.loader.exec_module(checks)

HarnessRsiStaticContractTest = checks.HarnessRsiStaticContractTest
HarnessRsiContractTest = checks.HarnessRsiContractTest
