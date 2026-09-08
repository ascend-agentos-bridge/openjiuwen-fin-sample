# 样例 14 · 安全护栏 Rail —— 工具黑名单拦截

> 使用**开源 openjiuwen Agent Core 真实 API**（`openjiuwen==0.1.16`），对齐官方 `security_rail_demo`。对应特性清单 §15 Guardrail。

## 场景

信贷助手带高危工具 `delete_customer`：`DenyListRail` 在 `BEFORE_TOOL_CALL` 拦截黑名单工具（拒绝原因回传给模型转述给用户）；正常余额查询放行。

## 运行

```bash
python main.py
```

## 验收方式

1. 场景 1（删除请求）：日志出现 `[rail] 拦截高危工具: delete_customer`，最终回复是"已被系统限制（黑名单中）"类话术，**且不含"已删除"**——工具真实未执行；
2. 场景 2（正常查询）：余额 58.0 万正常返回；
3. 最后一行 `SUCCESS: ...`，退出码 0。

实测输出（真实运行）：

```text
  [rail] 拦截高危工具: delete_customer
=== 场景1: 删除请求 ===
{'output': '该操作需要系统管理员权限才能执行，当前工具已被系统限制（黑名单中）。请联系银行系统管理员处理客户档案删除请求。', 'result_type': 'answer'}

=== 场景2: 正常查询 ===
{'output': 'C1001客户的贷款余额为58.0万元。', 'result_type': 'answer'}

SUCCESS: 黑名单拒绝 + 正常放行 验收通过
```

## 代码讲解（真实 API）

| 关键点 | API | 说明 |
|---|---|---|
| 护栏基类 | `BaseSecurityRail`（`openjiuwen.harness.rails.security.base_security_rail`） | core 层 `AgentRail` 的安全封装 |
| 挂载点 | `supported_events = {AgentCallbackEvent.BEFORE_TOOL_CALL}` | 11 种生命周期事件可选 |
| 检查逻辑 | `async def run_security_check(ctx) -> SecurityDecision` | 返回 `self.allow()` / `self.reject(message)` / `self.interrupt(request)`（转人工） |
| 注册 | `await agent.register_rail(rail)` | **是 async，必须 await** |

## 开发者注意（真实踩坑）

- **`register_rail` 不 await 护栏根本不生效**（只打 RuntimeWarning）——本样例第一版就踩了：删除请求被模型真实"执行"。这是护栏类代码最危险的一类 bug，上线前务必用高危工具实测拦截；
- reject 后拒绝原因作为工具观察回传给模型，模型会向用户转述——**拒绝也要可对话**；
- `self.interrupt(request)` 可把决策升级为人机协同（复用样例 13 的 InteractiveInput 恢复流程）；
- 高频检查（正则/黑名单）放 rail，重检查（审核模型）建议 `AFTER_MODEL_CALL` 异步旁路，别堵主链路。
