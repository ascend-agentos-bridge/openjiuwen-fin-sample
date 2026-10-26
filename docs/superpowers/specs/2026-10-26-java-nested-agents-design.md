# Java 三层智能体样例设计

用户要求：核实 Java Agent Core 是否允许子智能体再次委派、最大层数，并在支持时新增可接本机 LM Studio 的样例。

采用官方 `com.openjiuwen:agent-core-java:0.1.16` 的 `HierarchicalToolsTeam`，注册审批主管 → 风控专员 → 征信核验员。三者均为真实 `ReActAgent`；只有核验员能调用本地演示征信数据工具。主管只调用风控专员，风控专员再调用核验员，结果逐级返回。

方案选择：`HierarchicalToolsTeam` 直接表达父子关系且只需三个 ReActAgent；`DeepAgent` 的嵌套 `SubAgentConfig` 也可表达该关系，但会引入本例不需要的 harness/workspace 能力。采用前者，这是实现选择。

约束：只调用官方 API，不封装框架、不模拟模型。Java 请求优先于仓库原有 Python 语言约定；保留 `main.py` 作为回归脚本的 Java 启动入口。Java 17+、Maven 3.9+，默认 LM Studio `/v1` 和本机已发现模型 `google/gemma-4-e4b`，支持环境变量覆盖。

验收：官方回调记录嵌套进入/返回顺序及各 Agent 成功模型调用；征信工具生成每次运行独有的报告编号，最终主管回答必须包含该编号，证明信息经过叶节点再回传。退出码非零即失败。README 实测输出只收录真实运行。

层数结论必须限定实现：`HierarchicalToolsTeam` 无配置树深度校验，共享团队会话的 `TeamRuntime.send` 有 `MAX_DISPATCH_DEPTH=64` 防护，根节点占一次 dispatch；不能把该常量当作所有 Agent API 的统一层数承诺。`DeepAgent` 的递归配置/委派路径未发现独立最大层数配置。
