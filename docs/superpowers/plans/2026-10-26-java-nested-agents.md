# Java 三层智能体实现计划

> 按 writing-plans / executing-plans 在本会话执行，保留原工作区供用户直接审阅。

**Goal:** 新增 Java 官方 SDK 三层委派样例，真实调用本机 LM Studio。

**Architecture:** `HierarchicalToolsTeam` 注入父子委派工具；三层均为 `ReActAgent`。`main.py` 仅启动 Maven，接入现有回归入口。

**Tech Stack:** Java 17+, Maven 3.9+, agent-core-java 0.1.16, LM Studio OpenAI 兼容接口。

**Spec:** `docs/superpowers/specs/2026-10-26-java-nested-agents-design.md`

## 约束与检查重点

- 官方 release/tag 与 Maven Central 版本一致；参考官方 examples/react_agent 和 examples/multi_agent/hierarchical。
- 子 Agent 入参 schema 使用 `query`，与 ReActAgent.invoke 对齐。
- 回归检查真实嵌套顺序、三层模型调用及随机报告编号回传；不用模型假响应。
- 模型未启用、工具调用失败、遗漏委派、缺少 JDK/Maven 必须失败，不输出伪成功。
- 保持 Python 样例不变；Java 启动器传播退出码。

## 执行

- [x] 新增 samples/20_java_nested_agents 的 Java 验收断言、Maven 配置和 Python 启动器，验证无委派轨迹时断言失败（实际运行空轨迹，退出码 1，`三层嵌套轨迹不符: []`）。
- [x] 使用官方 ReActAgent / HierarchicalToolsTeam / LocalFunction 和回调实现三层调用，运行真实 LM Studio 验收（SDK 0.1.16，Gemma 4 e4b，三层各 2 次模型调用，报告编号 `CR-2ca87ea7` 回传，Maven 退出码 0）。
- [x] 保存真实日志，完善 README、根 README、FEATURES.md；运行 `python3 run_all_samples.py 20`（79.5 秒，1/1 通过）和 diff 检查。启动器缺失 Maven 的场景返回退出码 1，Python 语法检查通过。

不创建提交或发布；本次仅交付可审阅的工作区修改。
