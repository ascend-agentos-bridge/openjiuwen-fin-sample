# Java AgentHint Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 交付 Java 0.1.17 交互样例、Linux 一键 benchmark 及确定性报告。

**Architecture:** 样例与 benchmark 独立 Maven 工程，直接调用官方 SDK；模型仅用于业务回答，统计与报告由普通代码生成。

**Tech Stack:** Java 17、Maven 3.9、JUnit、Bash 4。

**Spec:** ../specs/2026-11-10-java-agent-hint-design.md

## Global Constraints

- 用户已明确开始实现，无需重复设计审批；不提交或推送未经要求的更改。
- 模型服务密钥仅用于真实请求，不落盘、不输出、不进入报告。
- Python 待实现；无 AgentHint 环境时如实标注未验收，不 mock 模型。
- 保留现有无关改动；业务样例与测试分离。

## Review Focus

- 缺失 usage 不得变成零命中率。
- 暂停会话的波次不得形成 worker 屏障死锁。
- baseline 不产生 Hint 已执行或性能收益归因。
- 脚本错误和中断须保留退出码，不打印不存在的报告。
- 单组报告合并须验证参数及配对一致。

## Task 1: 样例

- [x] 先写配置和业务规则 JUnit，观察失败，再实现单文件入口。
- [x] 按官方 examples 装配 Model、ReActAgent、Session、Runtime。
- [x] 真实 DeepSeek 多轮查询验收，保存脱敏记录并编写 README。

## Task 2: benchmark 与脚本

- [x] 先写参数、统计、结论边界、失败与配对合并测试，再实现。
- [x] 实现 continuous 与 idle-resume 及原始记录、确定性报告。
- [x] 实现 Linux 一键构建运行、配置模板、帮助与退出码。
- [x] 真实 DeepSeek baseline smoke 与不可达端点验收，不伪造 Hint 实测。

## Task 3: 文档与集成

- [x] 根 README、Java 指南、FEATURES 新增 28 与 benchmark 索引。
- [x] 核对实际交付文件链接、命令、真实验收记录。
- [x] 全部新增 JUnit 与脚本检查通过后汇总限制。

## Implementation rulings

- SDK 0.1.17 的流式队列消费后终结；benchmark 每轮按官方 Runner 的行为新建 AgentSession facade，复用相同 session ID、Agent 与 KVCacheRuntime，以保留会话身份而不复用已关闭的队列。非流式业务样例仍复用同一 facade。由真实多轮集成测试验证。
- Linux 验证使用本机 Docker 的 Maven/JDK17 镜像，不将 Mac 的脚本语法检查冒充 Linux 运行。
- 结论中的服务端证据永久明确区分“附件已提供但未经程序验证”，不把用户声明自动升级为执行证明。
- 官方 Agent callback 可稳定取得模型完成耗时与最终 usage，不能可靠取得每个模型调用的首 token；该字段保留 null。业务轮首正文由 Runner 流测量，README 明确两者不同，不将 null 描述成已测得。

## 验证结果

- Linux Docker JDK17 / Maven3.9.16：样例 3 项测试通过；benchmark 25 项测试通过（24 离线 + 1 真实模型并发集成，8 业务轮）。
- Linux 一键脚本真实 DeepSeek 基线：两类负载合计4个正式轮、2个预热轮全部通过。
- Linux脚本：帮助0，不可达模型1，未知参数2，缺Maven2，拒绝基线合并2；从/tmp启动及含空格路径已验证。
- 独立审阅未发现阻止交付的P1/P2；AgentHint服务端执行与性能仍待环境。
