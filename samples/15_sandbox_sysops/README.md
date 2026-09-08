# 样例 15 · 系统操作与沙箱 —— 信贷报表的受控执行环境

> 使用**开源 openjiuwen Agent Core 真实 API**（`openjiuwen==0.1.16`）。对应特性清单 §16 System Operation / Sandbox。

## 场景

报表 Agent 的执行环境：沙箱内文件读写、沙箱外路径拒绝、shell 白名单执行、代码执行及其**真实隔离边界实测**。

## 运行

```bash
python main.py
```

## 验收方式

1. 沙箱内写/读 `loans/report.txt` 成功（code=0）；
2. 沙箱外路径写入被拒：`[199003] Access denied: Path ... outside sandbox`；
3. shell（白名单 + `cwd=沙箱`）执行成功输出 `hello-from-sandbox`——**CWD 在沙箱外也会被拒**（`[199004]`）；
4. code 执行 `print('sum:', 120+73.5)` 输出 `193.5`；
5. **安全边界实测**：LOCAL 模式 code 能读到沙箱外文件（`outside-read: True`）——框架对 fs/shell 做路径校验，code 不做；
6. 最后一行 `SUCCESS: ...`，退出码 0。

实测输出（真实运行）：

```text
=== 沙箱外路径（真实防护） ===
code=199003, message=...Access denied: Path ... outside sandbox...

=== shell（白名单内, cwd=沙箱） ===
code=0, stdout=hello-from-sandbox

=== code（正常脚本） ===
code=0, stdout=sum: 193.5

=== code 的真实隔离边界（LOCAL 模式） ===
code 能否读到沙箱外文件: True（能 → LOCAL 模式不做代码级文件隔离）

SUCCESS: 沙箱读写 + 越界拒绝 + shell白名单/CWD校验 + code边界实测 验收通过
```

## 代码讲解（真实 API）

| 关键点 | API | 说明 |
|---|---|---|
| 入口 | `SysOperation(SysOperationCard(mode=OperationMode.LOCAL, work_config=LocalWorkConfig(...)))` | 三类操作：`so.fs() / so.shell() / so.code()` |
| 沙箱 | `LocalWorkConfig(sandbox_root=[绝对路径], restrict_to_sandbox=True)` | fs/shell 全部路径校验 |
| 文件 | `await fs.write_file(path, content)` / `read_file(path)` | 返回结果对象带 `.code/.data/.message` |
| shell | `await shell.execute_cmd(command, cwd=沙箱内路径)` | `shell_allowlist` 白名单；CWD 必须在沙箱内 |
| 代码 | `await code.execute_code(code, language='python')` | 本机子进程执行 |

## 开发者注意（真实踩坑，安全边界重要）

- **LOCAL 模式的隔离边界**（本样例实测）：`fs`/`shell` 严格校验沙箱路径；**`code` 是本机 Python 子进程，可读写沙箱外文件**——`dangerous_patterns` 不拦截 code 执行。运行不可信代码必须用 `OperationMode.SANDBOX`（`SandboxGatewayConfig` 对接外部沙箱网关）或容器级隔离；
- `sandbox_root` 是**列表**且必须绝对路径（相对路径会被解析到进程 CWD 所在盘符）；
- shell 的 CWD 默认是进程 CWD——不在沙箱内直接 `[199004]` 拒绝，记得传 `cwd`；
- 结果对象统一 `code/message/data` 形态，失败**不抛异常**，务必检查 `code != 0`。
