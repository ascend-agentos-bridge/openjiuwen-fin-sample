# 样例 12 · 多智能体团队 —— 信贷客服 Handoff 协作

> 使用**开源 openjiuwen Agent Core 真实 API**（`openjiuwen==0.1.16`），对齐官方 `handoff_customer_service` 示例。对应特性清单 §11 Multi-Agent。

## 场景

信贷客服团队：**分流台**（轻量快模型）判断问题类型，通过 handoff 工具转交 **授信专员** / **还款专员**（主模型，各自带业务工具）。

## 运行

```bash
python main.py
```

## 验收方式

1. 分流台正确发起 `transfer_to_credit_officer(reason=..., message=...)` 工具调用；
2. 框架产生 handoff 信号（tool 观察值含 `__handoff_to__: credit_officer`），授信专员的 agent 会话启动（checkpoint_restore）；
3. `team.invoke` 返回 `result_type=answer` 的转交/答复话术；
4. 最后一行 `SUCCESS: ...`，退出码 0。

实测输出（真实运行）：

```text
=== 路由结果 ===
{'output': '您好，C1001先生/女士，请问您的最高授信额度是多少钱呢？', 'result_type': 'answer'}

SUCCESS: Handoff 路由发起 + 转交信号 + 团队返回 验收通过
```

关键日志证据（路由真实发生）：

```text
tool_call: transfer_to_credit_officer({"reason": "客户C1001询问最高授信额度", "message": "客户ID: C1001"})
tool 观察: {'__handoff_to__': 'credit_officer', '__handoff_message__': '客户ID: C1001', ...}
checkpoint_restore: agent_id=__handoff_ep_credit_service_team_credit_officer
```

## 代码讲解（真实 API）

| 关键点 | API | 说明 |
|---|---|---|
| 团队 | `HandoffTeam(card=TeamCard(...), config=HandoffTeamConfig(handoff=HandoffConfig(...)))` | `message_timeout=600.0` 必须调大（默认 30s） |
| 路由 | `HandoffConfig(start_agent=card, max_handoffs=4, routes=[HandoffRoute(source, target)])` | 转交白名单 |
| 注册 | `team.add_agent(card, factory)` | 框架自动为每个成员生成 `transfer_to_<target>` 工具 |
| 执行 | `await team.invoke({"query": ...})` | 返回链上代理的回复 dict |

## 开发者注意（真实踩坑）

- **HandoffTeam.invoke 返回的是链上代理的回复**——小模型在转交后倾向回复"已转交"话术即结束链，专员的最终答复可能到不了 invoke 返回值；需要专员闭环的场景要调 prompt（转交后不再作答）或改用 ControllerAgent 编排（官方 multi_workflow_agent_demo）；
- **每次 `team.invoke` 会重新注册 transfer_to_xxx 工具**，多次 invoke 会出现 `resource already exist` 告警（功能不受影响）；
- **本地慢模型（thinking 4B）单 Agent 一轮 30-90s，handoff 链多跳极易超过默认 30s 的 `message_timeout`**——分流台这类"简单分类决策"换轻量模型（qwen2-0.5b）是标准做法；
- handoff 的 `message` 参数是给下家的上下文载体，但下家是新会话——让专员拿全信息要么靠 message 显式传，要么让专员自己用工具查。
