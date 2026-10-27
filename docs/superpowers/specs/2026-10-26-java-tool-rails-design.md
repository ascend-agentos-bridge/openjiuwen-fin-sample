# Java 工具校验、中断恢复与重试样例

- 用户要求：提供 Java sample，入口保持简单，验收代码全部放进 UT。
- 场景：客户 C1001 申请放款 50000 元；金额必须在 (0, 100000]，超过 20000 元需审批。
- 采用单个 Java 文件直接使用官方 `AgentRail`、`BaseInterruptRail`、`ReActAgent` 和内存检查点。两个 Rail 分别负责业务校验/审批、临时错误的一次重试。
- `main` 只创建 Agent、发起申请、提交演示审批结果、打印结果与清理资源。银行工具使用明确标注的本地演示实现，第一次返回 BANK_BUSY，第二次成功；模型必须为真实服务。
- 单个串联场景便于阅读；拆成多个完整项目会重复配置和注册；实现自定义执行器违反仓库约束。
- JUnit 覆盖金额边界、非法参数、审批放行/拒绝、重试成功/耗尽/不重试。真实模型验收也放在 JUnit，通过 `real-model` profile 运行。
- Java 样例只提供 Maven/Java 入口，不增加 Python 启动器；根目录回归直接识别 `pom.xml` 并调用 Maven。
- 新增 `samples/23_java_tool_rails`、README 与运行输出，同步根 README 和 FEATURES 索引；不改写已有 sample。
