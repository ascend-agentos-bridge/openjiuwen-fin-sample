# Java 版：信贷全流程超级智能体与实时对话树

依赖 `com.openjiuwen:agent-core-java:0.1.17`；JDK17+、Maven3.9+，真实DeepSeek Flash模型。

启动、问题输入、运行者审批/签署按钮、预期树形效果及真实运行记录见 [场景 README](../README.md)。

后端接入方式见 [开发者集成：三层智能体如何装配](../README.md#开发者集成三层智能体如何装配)，包含角色定义、业务工具绑定、团队父子关系和调用入口的代码节选。

2026-11-06实测：5项业务规则测试和3项真实模型集成测试全部通过。真实模型测试使用脚本化输入与显式确认，不是人工交互记录；完整C1001流程得到虚构回执 `DEMO-C1001`、月供23548.08元。浏览器另实测产品咨询仅激活超级智能体与客服。

```bash
mvn compile exec:java
# 打开 http://127.0.0.1:8080
mvn test
mvn -Preal-model test
```

运行前在环境中配置API_KEY；默认API_BASE为DeepSeek官方、MODEL_NAME为deepseek-flash。状态仅保存于内存，服务重启清空。所有金融数据与动作仅用于虚构业务演示。
