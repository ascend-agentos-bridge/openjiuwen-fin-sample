# Java Ascend Affinity Implementation Plan

> 实施方式：本会话直接实现。用户已要求开始编码；按每个任务测试与验收。

**Goal:** 提供开启样例及生成结论性测试报告的 Java benchmark，先真实跑通普通模型。

**Architecture:** 两个独立 Maven 工程，直接使用官方 SDK。benchmark 将运行组织、配置和统计报告分开，无核心 API 自封装。

**Tech Stack:** Java 17, Maven, agent-core-java 0.1.16, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-10-27-java-ascend-affinity-design.md`

## Global Constraints

- 仅真实模型；缺失指标不填零；normal 不能声称验证昇腾加速。
- Java 入口与 JUnit 验收分开；亲和 provider 的 API_BASE 不带 /v1。

## Review Focus

- 所有请求失败也生成报告且非零退出。
- SDK 默认 token 零值不能误判成有效用量/零命中。
- 流式片段不能当 token，usage 不能重复累加。
- A/B 请求参数、负载、预热、测量窗口与缓存污染限制必须可追溯。
- 并发 worker 异常及资源清理不能造成静默遗漏请求。

## Tasks

- [x] 1. 新增样例配置、信贷工具和多轮调用；测试普通/亲和开关与真实调用。
- [x] 2. 先测试统计与 CLI，再实现 benchmark 两类负载、并发和 JSONL。
- [x] 3. 实现报告结论、指标可用性、CSV 和配置快照；测试缺失/失败/比较边界。
- [x] 4. 真实普通模型执行、故障路径验收、索引与运行说明更新。
- [x] 5. 独立代码审查，修复重要问题并完成回归。

## Execution ledger

- Baseline: sample 23 离线 JUnit 已通过；当前工作区初始无修改。
- Decision: 按用户要求直接在当前工作区实现，默认 normal，不探测或假设昇腾服务存在。

- Task 1: 3 个配置测试和 1 个真实三轮集成测试通过；main 真实三轮输出已保存。
- Tasks 2-3: 统计/参数/usage/worker/完成性共 13 项离线测试通过；正式 benchmark 12/12 通过，4 次预热单列；独立 Python 复算均值、吞吐和调用数一致。
- Review: 独立 reviewer 确认官方 API 与 usage 处理正确，指出无限等待、worker/清理丢数据、Agent 截断判定及虚报并发；均已修正并补回归测试。
- Timeout: 独立批次截止时间 + 取消/异步关闭；保留已完成轮次，未完成指标填 null，并停止后续批次；Runner 清理也受限时控制。
- Failure acceptance: 真实不可达端点 CLI 退出码 1，完整失败报告已保存。
- Scope: 当前只验证普通模型基线；服务端缓存与 NPU 指标未采集，亲和生效/收益未验证。
- Final verification: 最终 benchmark JUnit 15/15（含 2 项真实集成/失败路径），样例 4/4；正式 benchmark 12/12；git diff --check 和本地文档链接检查通过。
