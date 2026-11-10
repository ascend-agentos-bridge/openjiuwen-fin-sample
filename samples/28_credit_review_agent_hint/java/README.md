# Java 版

依赖 **Java 17+、Maven 3.9+、`com.openjiuwen:agent-core-java:0.1.17`**。

场景、开关配置、交互操作与验收记录见[场景 README](../README.md)。

```bash
export API_BASE='https://api.deepseek.com'
export MODEL_NAME='deepseek-flash'
# API_KEY 通过运行环境提供，不写进文件
export AGENT_HINT_ENABLED=false
mvn compile exec:java
mvn test
mvn -Preal-model test
```

`main` 仅有业务流程与终端输入；JUnit 单独验证。`test_output.txt` 保存 Linux 上的真实 DeepSeek 基线验收：2 项离线规则测试和 1 项两轮模型集成测试。集成测试中的输入由测试代码提供，属于脚本化终端验收；不是人工交互记录。AgentHint 开启后的服务端执行与性能尚未验证。
