import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.tool.Tool;
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
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 官方 Java Agent Core → 官方 OTel span/exporter → Langfuse OTLP/HTTP。 */
public final class CreditLangfuse {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AGENT_ID = "credit_langfuse_agent";
    private static final String TOOL_ID = "query_credit_report";
    private static final String HANDLER_ID = "sample22_langfuse";

    public static void main(String[] args) throws Exception {
        // 配置缺失时立即失败，不调用模型，也不假装已连接 Langfuse。
        String publicKey = required("LANGFUSE_PUBLIC_KEY");
        String secretKey = required("LANGFUSE_SECRET_KEY");
        String baseUrl = env("LANGFUSE_BASE_URL", "http://localhost:3000").replaceAll("/+$", "");
        String endpoint = baseUrl + "/api/public/otel";
        String auth = "Basic " + Base64.getEncoder().encodeToString(
                (publicKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8));
        String sessionId = "credit-langfuse-" + UUID.randomUUID();
        String reportId = "CR-" + UUID.randomUUID().toString().substring(0, 8);

        OtelTracerConfig otelConfig = OtelTracerConfig.builder()
                .exporterType("otlp").protocol("http").exporterEndpoint(endpoint)
                .headers(Map.of("Authorization", auth, "x-langfuse-ingestion-version", "4"))
                .isRedactionEnabled(false).maxAttrLength(32768).build();
        InMemorySpanExporter memory = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .setResource(Resource.getDefault().merge(Resource.create(Attributes.builder()
                        .put("service.name", "openjiuwen-fin-sample22").put("service.version", "0.1.16").build())))
                .addSpanProcessor(SimpleSpanProcessor.create(memory)).build();
        SpanExporter remote = OtelTracerSetup.createOtlpExporter(otelConfig);
        Tool tool = null;
        try {
            TracerHandlerRegistry.registerHandler(HANDLER_ID,
                    new OtelAgentHandler(provider.get("openjiuwen.sample.langfuse"), otelConfig));
            String modelName = env("MODEL_NAME", "google/gemma-4-e4b");
            ReActAgent agent = new ReActAgent(AgentCard.builder().id(AGENT_ID).name(AGENT_ID)
                    .description("贷前征信核验与 Langfuse 追踪").build());
            ReActAgentConfig config = ReActAgentConfig.builder()
                    .promptTemplate(List.of(Map.of("role", "system", "content",
                            "你是银行贷前核验助手。必须先调用 query_credit_report 查询客户征信，"
                                    + "只调用一次；获得结果后用中文简短回答，并原样包含报告编号、客户编号和评分。"
                                    + "数据是演示档案，不得作出实际放款承诺。")))
                    .maxIterations(3).build().configureModelClient("openai", env("API_KEY", "lm-studio"),
                            env("API_BASE", "http://127.0.0.1:1234/v1"), modelName, true);
            config.getModelConfigObj().setTemperature(0.1);
            config.getModelConfigObj().setMaxTokens(512);
            var client = config.getModelClientConfig();
            config.setModelClientConfig(ModelClientConfig.builder()
                    .clientProvider(client.getClientProvider()).apiKey(client.getApiKey()).apiBase(client.getApiBase())
                    .verifySsl(client.isVerifySsl())
                    .timeout(Double.parseDouble(env("MODEL_TIMEOUT_SECONDS", "180"))).build());
            agent.configure(config);
            agent.registerRail(new OtelRail());

            // 官方业务生命周期回调仅补 Langfuse 展示属性；span 仍由官方 OtelRail/Handler 创建和结束。
            // 默认 priority=50，在 OtelRail(priority=0) 结束 span 前执行。
            agent.registerRail(new AgentRail() {
                @Override
                public void afterInvoke(AgentCallbackContext ctx) {
                    if (ctx.getInputs() instanceof InvokeInputs input) {
                        labelCurrentSpan(sessionId, "agent", Map.of("query", input.getQuery()), input.getResult());
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
            });

            tool = new LocalFunction(ToolCard.builder().id(TOOL_ID).name(TOOL_ID)
                    .description("查询客户的演示征信档案")
                    .inputParams(Map.of("type", "object", "properties", Map.of("customer_id", Map.of(
                            "type", "string", "description", "客户编号，例如 C1001")),
                            "required", List.of("customer_id"))).build(), inputs -> {
                        if (!"C1001".equals(inputs.get("customer_id"))) {
                            throw new IllegalArgumentException("只提供客户 C1001 的演示档案");
                        }
                        return Map.of("customer_id", "C1001", "credit_score", 735,
                                "overdue_months", 0, "report_id", reportId, "data_source", "演示数据");
                    });
            Runner.resourceMgr().addTool(tool, AGENT_ID);
            agent.getAbilityManager().add(tool.getCard());
            System.out.println("CONFIG: sdk=0.1.16 model=" + modelName + " endpoint=" + endpoint + "/v1/traces");
            Object result = Runner.runAgent(agent, Map.of("query",
                    "请查询客户 C1001 的征信并给出摘要，保留报告编号。", "conversation_id", sessionId), null, null);
            if (!(result instanceof Map<?, ?> answer) || !"answer".equals(answer.get("result_type"))
                    || !String.valueOf(answer.get("output")).contains(reportId)) {
                throw new IllegalStateException("模型没有回传真实工具生成的报告编号: " + result);
            }

            provider.forceFlush().join(10, TimeUnit.SECONDS);
            List<SpanData> spans = memory.getFinishedSpanItems();
            if (spans.size() != 4 || spans.stream().anyMatch(s -> s.getStatus().getStatusCode() != StatusCode.OK)) {
                throw new IllegalStateException("必须得到 4 个成功完成的 OTel span，实际为 " + spans.size());
            }
            SpanData root = spans.stream().filter(s -> !s.getParentSpanContext().isValid()).findFirst().orElseThrow();
            if (spans.stream().anyMatch(s -> !root.getTraceId().equals(s.getTraceId()))) {
                throw new IllegalStateException("OTel trace ID 不一致");
            }
            System.out.println("RESULT: " + json(result));
            System.out.println("OTEL_TRACE_ID: " + root.getTraceId() + " spans=" + spans.size());
            // 本例任务短且 span 有界，显式批量上传并检查结果，不能把业务成功当作遥测上传成功。
            var upload = remote.export(spans).join(30, TimeUnit.SECONDS);
            if (!upload.isSuccess()) {
                throw new IllegalStateException("OTLP/HTTP 导出失败或超时，请检查 Langfuse 地址、项目密钥和接收端日志");
            }
            System.out.println("SUCCESS: OTLP/HTTP 接收端已确认收到 " + spans.size() + " 个 span；traceId=" + root.getTraceId());
        } finally {
            try {
                if (tool != null) {
                    Runner.resourceMgr().removeTool(TOOL_ID, AGENT_ID, TagMatchStrategy.ALL, true);
                }
                TracerHandlerRegistry.unregisterHandler(HANDLER_ID);
                Runner.release(sessionId);
                Runner.stop();
            } finally {
                provider.shutdown().join(10, TimeUnit.SECONDS);
                remote.shutdown().join(10, TimeUnit.SECONDS);
            }
        }
    }

    private static void labelCurrentSpan(String sessionId, String type, Object input, Object output) {
        Span span = Span.current();
        if (!span.getSpanContext().isValid()) {
            throw new IllegalStateException("官方 OtelRail 没有激活当前 span");
        }
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
            throw new IllegalArgumentException("请配置 " + key + "；本地协议验证请用 main.py --local-test");
        }
        return value;
    }
}
