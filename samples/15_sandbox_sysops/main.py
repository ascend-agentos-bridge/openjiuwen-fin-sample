"""样例 15 · 系统操作与沙箱（真实 openjiuwen API）—— 信贷报表的受控执行环境。

使用开源 openjiuwen Agent Core（v0.1.16）真实 API:
- SysOperation(SysOperationCard): 系统操作入口（fs / shell / code 三类）
- LocalWorkConfig(sandbox_root=[...], restrict_to_sandbox=True): 本地沙箱配置
- 沙箱外路径 → Access denied [199003]（框架真实路径防护）
- shell_allowlist / dangerous_patterns: shell 与代码执行的策略控制

运行: python main.py
"""
import asyncio
import os
import tempfile

from openjiuwen.core.sys_operation import (LocalWorkConfig, OperationMode,
                                           SysOperation, SysOperationCard)


async def main():
    root = os.path.join(tempfile.gettempdir(), "ojw_sys15")
    os.makedirs(root, exist_ok=True)
    card = SysOperationCard(
        id="credit_report_ops", name="credit_report_ops", description="信贷报表执行环境",
        mode=OperationMode.LOCAL,
        work_config=LocalWorkConfig(
            sandbox_root=[root], restrict_to_sandbox=True,
            shell_allowlist=["echo", "dir"],          # shell 白名单
            dangerous_patterns=["import os", "import subprocess", "__import__"]))
    so = SysOperation(card)
    fs, shell, code = so.fs(), so.shell(), so.code()

    # ---- 1. 沙箱内文件读写 ----
    inner = os.path.join(root, "loans", "report.txt")
    w = await fs.write_file(path=inner, content="贷款质量周报：不良率 62.0%（120/193.5 万元）")
    print("=== 沙箱内写文件 ===")
    print(f"code={w.code}")
    r = await fs.read_file(path=inner)
    print(f"读回: {r.data if hasattr(r, 'data') else str(r)[:60]}")

    # ---- 2. 沙箱外路径 → 真实防护拦截 ----
    outside = await fs.write_file(path=os.path.join(tempfile.gettempdir(),
                                                    "ojw_escape.txt", "x.txt"),
                                  content="escape")
    print("\n=== 沙箱外路径（真实防护） ===")
    print(f"code={outside.code}, message={str(outside.message)[:80]}")
    assert outside.code != 0, "沙箱外写入应被拒绝"

    # ---- 3. shell 白名单执行（CWD 必须在沙箱内——框架真实校验） ----
    sh = await shell.execute_cmd(command="echo hello-from-sandbox", cwd=root)
    print("\n=== shell（白名单内, cwd=沙箱） ===")
    print(f"code={sh.code}, stdout={sh.data.stdout.strip() if sh.data and sh.data.stdout else ''}")

    # ---- 4. code 执行: 本地模式跑通脚本, 并实测其真实安全边界 ----
    ok_code = await code.execute_code(code="print('sum:', 120 + 73.5)")
    print("\n=== code（正常脚本） ===")
    print(f"code={ok_code.code}, stdout={ok_code.data.stdout.strip() if ok_code.data else ''}")
    assert ok_code.code == 0 and "193.5" in (ok_code.data.stdout or "")

    # 真实安全边界（实测）: LOCAL 模式的 code 执行是本机 Python 子进程,
    # 能读到沙箱外文件 —— 文件防护只覆盖 fs/shell。不可信代码必须用
    # OperationMode.SANDBOX（外部沙箱网关）或容器级隔离。
    leak = await code.execute_code(code="import os; print(os.path.exists(r'C:/Windows/win.ini'))")
    outside_visible = (leak.data.stdout or "").strip().endswith("True") if leak.data else False
    print("\n=== code 的真实隔离边界（LOCAL 模式） ===")
    print(f"code 能否读到沙箱外文件: {outside_visible}（能 → LOCAL 模式不做代码级文件隔离）")

    # ---- 5. 验收断言（全部基于真实行为） ----
    assert w.code == 0 and "62.0%" in str(r.data if hasattr(r, 'data') else r)
    assert outside.code != 0
    assert sh.code == 0
    assert outside_visible, "LOCAL 模式 code 可见沙箱外文件（如实验证安全边界）"
    print("\nSUCCESS: 沙箱读写 + 越界拒绝 + shell白名单/CWD校验 + code边界实测 验收通过")


if __name__ == "__main__":
    asyncio.run(main())
