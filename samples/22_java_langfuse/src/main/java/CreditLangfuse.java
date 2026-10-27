import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.base.TagMatchStrategy;
import com.openjiuwen.core.session.tracer.TracerHandlerRegistry;
import com.openjiuwen.core.singleagent.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.InvokeInputs;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.extensions.tracerotel.OtelAgentHandler;
import com.openjiuwen.extensions.tracerotel.OtelRail;
import com.openjiuwen.extensions.tracerotel.OtelTracerConfig;
import com.openjiuwen.extensions.tracerotel.OtelTracerSetup;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 业务入口：配置 Langfuse，调用征信 Agent，输出结果并释放资源。 */
public final class CreditLangfuse {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AGENT_ID = "credit_langfuse_agent";
    private static final String TOOL_ID = "query_credit_report";
    private static final String HANDLER_ID = "sample22_langfuse";

    public static void main(String[] args) throws Exception {
        String sessionId = "credit-langfuse-" + UUID.randomUUID();
        var config = tracingConfig(env("LANGFUSE_BASE_URL", "http://localhost:3000"),
                required("LANGFUSE_PUBLIC_KEY"), required("LANGFUSE_SECRET_KEY"));
        try (SdkTracerProvider telemetry = tracing(config).build()) {
            TracerHandlerRegistry.registerHandler(HANDLER_ID,
                    new OtelAgentHandler(telemetry.get("openjiuwen.sample.langfuse"), config));
            try {
                ReActAgent agent = createAgent(sessionId);
                Object result = Runner.runAgent(agent, Map.of("query", "请查询客户 C1001 的征信并给出摘要，保留报告编号。",
                        "conversation_id", sessionId), null, null);
                printResult(result);
            } finally {
                Runner.resourceMgr().removeTool(TOOL_ID, AGENT_ID, TagMatchStrategy.ALL, true);
                Runner.release(sessionId);
                Runner.stop();
                TracerHandlerRegistry.unregisterHandler(HANDLER_ID);
                telemetry.forceFlush().join(10, TimeUnit.SECONDS);
            }
        }
    }

    static void printResult(Object result) {
        if (result instanceof Map<?, ?> response && "error".equals(response.get("result_type"))) {
            throw new IllegalStateException("征信核验失败: " + response.get("output"));
        }
        System.out.println("RESULT: " + json(result));
    }

    /** 使用标准批处理器持续导出遥测。 */
    static SdkTracerProviderBuilder tracing(OtelTracerConfig config) {
        return SdkTracerProvider.builder()
                .setResource(Resource.getDefault().merge(Resource.create(Attributes.builder()
                        .put("service.name", "openjiuwen-fin-sample22").put("service.version", "0.1.16").build())))
                .addSpanProcessor(BatchSpanProcessor.builder(OtelTracerSetup.createOtlpExporter(config)).build());
    }

    static OtelTracerConfig tracingConfig(String baseUrl, String publicKey, String secretKey) {
        String auth = "Basic " + Base64.getEncoder().encodeToString(
                (publicKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8));
        return OtelTracerConfig.builder().exporterType("otlp").protocol("http")
                .exporterEndpoint(baseUrl.replaceAll("/+$", "") + "/api/public/otel")
                .headers(Map.of("Authorization", auth, "x-langfuse-ingestion-version", "4"))
                .isRedactionEnabled(false).maxAttrLength(32768).build();
    }

    static ReActAgent createAgent(String sessionId) {
        ReActAgentConfig config = ReActAgentConfig.builder()
                .promptTemplate(List.of(Map.of("role", "system", "content",
                        "你是银行贷前核验助手。必须先调用 query_credit_report 查询客户征信，"
                                + "只调用一次；获得结果后用中文简短回答，并原样包含报告编号、客户编号和评分。"
                                + "数据是演示档案，不得作出实际放款承诺。")))
                .maxIterations(3).build().configureModelClient("openai", env("API_KEY", "lm-studio"),
                        env("API_BASE", "http://127.0.0.1:1234/v1"), env("MODEL_NAME", "google/gemma-4-e4b"), true);
        config.getModelConfigObj().setTemperature(0.1);
        config.getModelConfigObj().setMaxTokens(512);
        var client = config.getModelClientConfig();
        config.setModelClientConfig(ModelClientConfig.builder()
                .clientProvider(client.getClientProvider()).apiKey(client.getApiKey()).apiBase(client.getApiBase())
                .verifySsl(client.isVerifySsl())
                .timeout(Double.parseDouble(env("MODEL_TIMEOUT_SECONDS", "180"))).build());
        ReActAgent agent = new ReActAgent(AgentCard.builder().id(AGENT_ID).name(AGENT_ID)
                .description("贷前征信核验与 Langfuse 追踪").build());
        agent.configure(config);
        agent.registerRail(new OtelRail());
        agent.registerRail(langfuseLabels(sessionId, config));
        LocalFunction tool = creditTool();
        Runner.resourceMgr().addTool(tool, AGENT_ID);
        agent.getAbilityManager().add(tool.getCard());
        return agent;
    }

    private static LocalFunction creditTool() {
        return new LocalFunction(ToolCard.builder().id(TOOL_ID).name(TOOL_ID)
                .description("查询客户的演示征信档案")
                .inputParams(Map.of("type", "object", "properties", Map.of("customer_id", Map.of(
                        "type", "string", "description", "客户编号，例如 C1001")),
                        "required", List.of("customer_id"))).build(), inputs -> {
                    if (!"C1001".equals(inputs.get("customer_id"))) {
                        throw new IllegalArgumentException("只提供客户 C1001 的演示档案");
                    }
                    return Map.of("customer_id", "C1001", "credit_score", 735, "overdue_months", 0,
                            "report_id", "CR-" + UUID.randomUUID().toString().substring(0, 8), "data_source", "演示数据");
                });
    }

    private static AgentRail langfuseLabels(String sessionId, ReActAgentConfig config) {
        return new AgentRail() {
            @Override
            public void afterInvoke(AgentCallbackContext ctx) {
                if (ctx.getInputs() instanceof InvokeInputs input) {
                    labelCurrentSpan(sessionId, "agent", Map.of("query", input.getQuery()), input.getResult());
                    System.out.println("OTEL_TRACE_ID: " + Span.current().getSpanContext().getTraceId());
                }
            }

            @Override
            public void afterModelCall(AgentCallbackContext ctx) {
                if (ctx.getException() == null && ctx.getInputs() instanceof ModelCallInputs input) {
                    labelCurrentSpan(sessionId, "generation", input.getMessages(), input.getResponse());
                    Span.current().setAttribute("langfuse.observation.model.parameters", json(config.getModelConfigObj()));
                }
            }

            @Override
            public void afterToolCall(AgentCallbackContext ctx) {
                if (ctx.getException() == null && ctx.getInputs() instanceof ToolCallInputs input) {
                    labelCurrentSpan(sessionId, "tool", input.getToolArgs(), input.getToolResult());
                }
            }
        };
    }

    static void labelCurrentSpan(String sessionId, String type, Object input, Object output) {
        Span span = Span.current();
        span.setAttribute("langfuse.observation.type", type);
        span.setAttribute("langfuse.observation.input", json(input));
        span.setAttribute("langfuse.observation.output", json(output));
        span.setAttribute("langfuse.session.id", sessionId);
        span.setAttribute("langfuse.trace.name", "credit_langfuse_review");
        span.setAttribute("langfuse.trace.metadata.case_id", "credit_c1001");
        span.setAttribute("langfuse.version", "sample22-v1");
        span.setAttribute("langfuse.environment", "sample");
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("无法序列化遥测属性", error);
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("请配置 " + key);
        }
        return value;
    }
}
