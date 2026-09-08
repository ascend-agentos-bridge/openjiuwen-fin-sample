# 样例 07 · Session 会话续聊 —— 两轮贷款咨询

> 使用**开源 openjiuwen Agent Core 真实 API**（`openjiuwen==0.1.16`）。对应特性清单 §12.1 Session / §12.2 State。

## 场景

第一轮问"客户 C1001 能贷多少"（工具查得 80 万）；第二轮**不重复给客户号**，直接问"我刚才说的客户号是多少？额度够不够 50 万？"——验证框架会话记忆。

## 运行

```bash
python main.py
```

## 验收方式

1. 第一轮回答含 `80`（工具真实调用）；
2. 第二轮回答同时含 `C1001`（跨轮记忆）与额度判断；
3. 框架日志出现 `checkpoint_restore`（会话状态真实恢复，非业务代码拼历史）；
4. 最后一行 `SUCCESS: ...`，退出码 0。

实测输出（真实运行）：

```text
=== 第一轮 ===
您的最高可贷额度为80万元。

=== 第二轮（同一会话, 框架自动恢复上下文） ===
您的客户号是C1001，当前最高可贷额度为80万元，因此50万元的贷款额度在您的可贷范围内，足够办理。

SUCCESS: conversation_id 驱动的会话续聊 + 跨轮记忆 验收通过
```

## 代码讲解（真实 API）

| 关键点 | API | 说明 |
|---|---|---|
| 会话标识 | `inputs={"conversation_id": CONV_ID}` | **同一个 conversation_id 的多次 run_agent 自动续聊**——框架 checkpointer 存取会话状态 |
| 会话恢复 | 框架日志 `checkpoint_restore` | 默认 inmemory 存储；生产可替换持久化后端 |
| 工具 | `Runner.resource_mgr.add_tool` + `agent.ability_manager.add(card)` | 同样例 01 |

## 开发者注意（真实踩坑）

- **会话记忆是框架能力，不要自己拼历史消息数组**——传同一 `conversation_id` 即可；不同客户/不同业务线用不同 conversation_id 隔离；
- conversation_id 建议带业务前缀（如 `sample-07-conv`），排障时能在日志里直接过滤；
- `run_agent` 的 `session` 参数可传入显式 `Session` 对象做更细的控制（跨 Agent 共享会话等），常规续聊用 conversation_id 就够。
