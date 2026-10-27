# Agent Core 开发者特性与样例索引

Python 样例 01–19 适配 `openjiuwen==0.1.16`；Java 样例 20–24 适配 `com.openjiuwen:agent-core-java:0.1.16`。本表列出现有样例覆盖的特性，未覆盖能力见根 README 与历史核验记录，不将未运行的能力标为已验收。

| 特性 | 样例 |
|---|---|
| ReAct 推理、工具调用、作答 | [01 hello_react_agent](samples/01_hello_react_agent/) |
| 本地函数工具与 REST 工具 | [02 function_tool](samples/02_function_tool/) |
| Prompt 模板与组装 | [03 prompt_template](samples/03_prompt_template/) |
| 结构化模型输出 | [04 structured_output](samples/04_structured_output/) |
| 模型客户端与调用参数 | [05 model_client](samples/05_model_client/) |
| Workflow DAG 编排 | [06 workflow_dag](samples/06_workflow_dag/) |
| Session 与会话状态 | [07 session_state](samples/07_session_state/) |
| 流式事件 | [08 streaming_events](samples/08_streaming_events/) |
| 长期记忆与语义检索 | [09 memory](samples/09_memory/) |
| Context 窗口管理 | [10 context_engine](samples/10_context_engine/) |
| Embedding、向量库与 RAG | [11 rag_retrieval](samples/11_rag_retrieval/) |
| 多 Agent handoff 协作 | [12 multi_agent_team](samples/12_multi_agent_team/) |
| 中断、检查点与恢复 | [13 interrupt_checkpoint](samples/13_interrupt_checkpoint/) |
| 工具安全护栏 | [14 security_guardrail](samples/14_security_guardrail/) |
| 受控文件、Shell 与代码执行 | [15 sandbox_sysops](samples/15_sandbox_sysops/) |
| A2A 跨系统协作 | [16 a2a_interop](samples/16_a2a_interop/) |
| LLM-as-Judge 评测 | [17 evaluation_optimizer](samples/17_evaluation_optimizer/) |
| MCP 工具协议 | [18 mcp_tools](samples/18_mcp_tools/) |
| Agent Skill 注册与加载 | [19 skill_use](samples/19_skill_use/) |
| Java 层级式 Agent-as-Tool：Master → SubAgent → SubAgent | [20 java_nested_agents](samples/20_java_nested_agents/) |
| Java 完整调用链日志：OtelRail、输入输出、用量、错误、配置指纹与 JSONL | [21 java_call_trace](samples/21_java_call_trace/) |
| Java Langfuse 对接：官方 OTel handler、OTLP/HTTP、Langfuse 属性映射；本地导出已验收 | [22 java_langfuse](samples/22_java_langfuse/) |
| Java 自定义工具业务校验、审批中断恢复与异常重试；JUnit 独立验收 | [23 java_tool_rails](samples/23_java_tool_rails/) |
| Java KV Cache 亲和开关、上下文压缩；普通模型流程验收，昇腾效果未验证 | [24 java_ascend_affinity](samples/24_java_ascend_affinity/) |

性能实验工具：[Java 算力亲和 benchmark](benchmarks/java_ascend_affinity/README.md)，自动生成客户端指标、服务端指标获取位置及结论报告。
