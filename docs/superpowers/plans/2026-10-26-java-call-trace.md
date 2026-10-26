# Java 调用链日志 sample

目标：核实最新 Java release 的调用链轨迹能力；支持时新增接本地 LM Studio 的金融场景样例。

实现选择：官方 `ReActAgent` + `LocalFunction` 查询演示征信数据，官方 `OtelRail` 生成 Agent、LLM、工具 span；消费 `StreamMode.TRACE` 事件，用 SLF4J/Logback 输出控制台和文件。无需部署 OTLP 服务。

范围：新增 `samples/21_java_call_trace/`，不修改已有未提交的 sample 20。Java 入口只依赖官方 `com.openjiuwen:agent-core-java:0.1.16`；`main.py` 仅启动 Maven，以便接入现有回归。文档补充版本、环境变量、官方依据和真实日志。

- [x] 先实现验收断言，验证空轨迹必定失败。
- [x] 实现单文件 Java sample、Maven 配置、Logback 配置及 Python 启动入口。
- [x] 用真实 LM Studio 运行，检查完成的根 span、至少两次 LLM 调用、征信工具调用、统一 traceId、正确 parentInvokeId 和最终业务输出；失败返回非零。
- [x] 补 README 和 FEATURES 索引，保存真实运行输出，并通过 `run_all_samples.py 21` 验收。

关注：模型未启动、未实际调用工具、流错误事件、缺失/错误的父子 span、日志未落盘都不得报告成功；不模拟 LLM，不编造输出。

执行记录：空轨迹验收先运行并按预期抛出“必须有且仅有一个 Agent 根 span，实际为 0”；随后完成 Java 实现，首次真实运行通过。配置改为独立 Logback 文件避免与 SDK 自带 logback.xml 重名，并新增日志落盘断言。`python3 run_all_samples.py 21` 在 Corretto 17.0.21 / Maven 3.9.9 / LM Studio google/gemma-4-e4b 上通过（32.3 秒），4 个完成 span，日志与报告编号保存在 sample 的 run_output.txt。仅修改新样例及索引，未运行 01–20 的模型回归。

失败路径：在独立临时工作目录将 API_BASE 指向无服务的 127.0.0.1:1/v1。官方客户端重试 3 次，LLM 和根 span 均 status=error，Maven 退出码 1，无 SUCCESS；成功运行的样例日志未覆盖。
