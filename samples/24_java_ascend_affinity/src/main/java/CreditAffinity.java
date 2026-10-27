import com.openjiuwen.core.context.ContextEngine;
import com.openjiuwen.core.context.processor.compressor.MicroCompactProcessorConfig;
import com.openjiuwen.core.context.schema.ContextEngineConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.base.TagMatchStrategy;
import com.openjiuwen.core.singleagent.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Official KV cache cooperation switch in a three-round credit review. */
public final class CreditAffinity {
    static final String AGENT_ID = "credit_affinity";
    static final String TOOL_ID = "read_credit_file";

    private CreditAffinity() { }

    public static void main(String[] args) {
        boolean affinity = enabled(env("AFFINITY_ENABLED", "false"));
        String session = "credit-affinity-" + UUID.randomUUID();
        try {
            var agent = createAgent(affinity);
            System.out.println("SDK: com.openjiuwen:agent-core-java:0.1.16");
            System.out.println("模式: " + (affinity ? "InferenceAffinity，已配置共享/释放，服务端生效需监控确认"
                    : "普通真实模型调用；昇腾亲和未验证"));
            for (int round = 1; round <= 3; round++) {
                var result = (Map<?, ?>) Runner.runAgent(agent,
                        Map.of("query", query(round), "conversation_id", session), null, null);
                if (!"answer".equals(result.get("result_type")) || result.get("output") == null
                        || String.valueOf(result.get("output")).isBlank()) {
                    throw new IllegalStateException("模型未完成回答: " + result.get("result_type"));
                }
                System.out.println("第 " + round + " 轮: " + result.get("output"));
            }
        } finally {
            Runner.resourceMgr().removeTool(TOOL_ID, AGENT_ID, TagMatchStrategy.ALL, true);
            Runner.release(session);
            Runner.stop();
        }
    }

    static String query(int round) {
        return "第" + round + "轮复核：请重新调用 read_credit_file 查询 C1001，不得复用先前查询结果。"
                + "只回答客户编号和信用评分，不要输出完整明细。";
    }

    static ReActAgent createAgent(boolean affinity) {
        String base = env("API_BASE", affinity ? "" : "http://127.0.0.1:1234/v1");
        var agent = new ReActAgent(AgentCard.builder().id(AGENT_ID).name("贷前复核助手")
                .description("演示官方上下文压缩与 KV Cache 亲和开关").build());
        agent.configure(config(affinity, base, env("API_KEY", "lm-studio"),
                env("MODEL_NAME", "google/gemma-4-e4b")));
        var tool = new LocalFunction(ToolCard.builder().id(TOOL_ID).name(TOOL_ID)
                .description("每轮重新读取 C1001 的演示征信档案")
                .inputParams(Map.of("type", "object", "properties", Map.of(
                        "customer_id", Map.of("type", "string")), "required", List.of("customer_id"))).build(),
                inputs -> {
                    if (!"C1001".equals(inputs.get("customer_id"))) {
                        throw new IllegalArgumentException("仅有 C1001 演示数据");
                    }
                    return Map.of("customer_id", "C1001", "credit_score", 735,
                            "source", "固定演示银行数据", "details", "按期还款，无逾期；".repeat(256));
                });
        Runner.resourceMgr().addTool(tool, AGENT_ID);
        agent.getAbilityManager().add(tool.getCard());
        return agent;
    }

    static ReActAgentConfig config(boolean affinity, String base, String key, String model) {
        if (base == null || base.isBlank()) {
            throw new IllegalArgumentException("亲和模式必须设置 API_BASE 为插件服务根地址");
        }
        if (affinity && base.replaceAll("/+$", "").endsWith("/v1")) {
            throw new IllegalArgumentException("InferenceAffinity 的 API_BASE 不带 /v1");
        }
        var context = ContextEngineConfig.builder().maxContextMessageNum(200)
                .defaultWindowRoundNum(10).enableReload(false).enableKvCacheRelease(affinity).build();
        var compression = MicroCompactProcessorConfig.builder().triggerThreshold(1)
                .keepRecentPerTool(1).compactableToolNames(List.of(TOOL_ID)).build();
        var config = ReActAgentConfig.builder().modelName(model)
                .modelClientConfig(ModelClientConfig.builder()
                        .clientProvider(affinity ? "InferenceAffinity" : "openai")
                        .apiBase(base).apiKey(key).timeout(60).maxRetries(0).build())
                .modelConfigObj(ModelRequestConfig.builder().modelName(model).temperature(0.0).topP(1.0).maxTokens(512).build())
                .promptTemplate(List.of(Map.of("role", "system", "content",
                        "你是银行贷前复核助手。每轮必须先调用一次 read_credit_file，严格使用用户指定的客户编号。"
                                + "工具返回后简短回答，原样保留客户编号和信用评分，不再调用工具。")))
                .maxIterations(3).maxParallelToolCalls(1).streamMaxRetries(0).build()
                .configureContextProcessors(List.of(new ContextEngine.ProcessorSpec("MicroCompactProcessor", compression)));
        config.setContextEngineConfig(context);
        return config;
    }

    static boolean enabled(String value) {
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("AFFINITY_ENABLED 只能为 true 或 false");
        }
        return Boolean.parseBoolean(value);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
