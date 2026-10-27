import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.UsageMetadata;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.base.TagMatchStrategy;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.core.session.stream.TraceSchema;
import com.openjiuwen.core.session.tracer.TraceAgentSpan;
import com.openjiuwen.core.singleagent.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.extensions.tracerotel.OtelRail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 使用官方 ReActAgent、LocalFunction 和 OtelRail 查询演示征信并记录完整调用链。 */
public final class CreditCallTrace {
    private static final Logger LOG = LoggerFactory.getLogger("sample.call_trace");
    private static final String AGENT_ID = "credit_trace_agent";
    private static final String TOOL_ID = "query_credit_report";
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final String SYSTEM_PROMPT = "你是银行贷前审查助手。必须先调用 query_credit_report 查询客户征信，"
            + "每次只调用一次，获得结果后不要再次调用工具。"
            + "用中文简短回答，必须原样包含报告编号、客户编号和信用评分。"
            + "不得编造征信记录，不得作出实际放款承诺。";

    public static void main(String[] args) throws Exception {
        String runId = UUID.randomUUID().toString();
        String sessionId = "credit-trace-" + runId;
        String traceId = null;
        Tool tool = null;
        Iterator<Object> stream = null;
        Files.createDirectories(Path.of("logs"));
        try (BufferedWriter jsonl = Files.newBufferedWriter(Path.of("logs", "call_trace.jsonl"), StandardCharsets.UTF_8)) {
            try {
                String apiBase = env("API_BASE", "http://127.0.0.1:1234/v1");
                ReActAgentConfig config = creditAgentConfig(apiBase);
                ReActAgent agent = createCreditAgent(config);
                tool = createCreditReportTool("CR-" + UUID.randomUUID().toString().substring(0, 8));
                Runner.resourceMgr().addTool(tool, AGENT_ID);
                agent.getAbilityManager().add(tool.getCard());

                writeEvent(jsonl, runId, sessionId, null, "CONFIG", configurationData(apiBase, config, tool));

                stream = Runner.runAgentStreaming(agent, Map.of("query",
                        "请查询客户 C1001 的征信档案，给出简短的贷前核验摘要，保留报告编号。",
                        "conversation_id", sessionId), null, null, List.of(StreamMode.TRACE, StreamMode.OUTPUT));
                while (stream.hasNext()) {
                    Object frame = stream.next();
                    if (frame instanceof TraceSchema trace && trace.getPayload() instanceof TraceAgentSpan span) {
                        traceId = span.getTraceId();
                        writeEvent(jsonl, runId, sessionId, traceId, "TRACE", traceData(span));
                    } else if (frame instanceof OutputSchema output) {
                        writeAgentOutput(jsonl, runId, sessionId, traceId, output);
                    }
                }
            } catch (Exception error) {
                writeEvent(jsonl, runId, sessionId, traceId, "RUN_ERROR", runErrorData(error));
                throw error;
            } finally {
                try {
                    if (stream instanceof AutoCloseable closeable) {
                        closeable.close();
                    }
                } finally {
                    if (tool != null) {
                        Runner.resourceMgr().removeTool(TOOL_ID, AGENT_ID, TagMatchStrategy.ALL, true);
                    }
                    Runner.release(sessionId);
                    Runner.stop();
                }
            }
        }
    }

    static ReActAgentConfig creditAgentConfig(String apiBase) {
        ReActAgentConfig config = ReActAgentConfig.builder()
                .promptTemplate(List.of(Map.of("role", "system", "content", SYSTEM_PROMPT)))
                .maxIterations(3).build()
                .configureModelClient("openai", env("API_KEY", "lm-studio"),
                        apiBase, env("MODEL_NAME", "google/gemma-4-e4b"), true);
        config.getModelConfigObj().setTemperature(0.1);
        config.getModelConfigObj().setMaxTokens(512);
        config.getModelConfigObj().setExtraField("stream_options", Map.of("include_usage", true));
        return config;
    }

    private static ReActAgent createCreditAgent(ReActAgentConfig config) {
        ReActAgent agent = new ReActAgent(AgentCard.builder()
                .id(AGENT_ID).name(AGENT_ID).description("贷前征信查询助手").build());
        agent.configure(config);
        // 官方 rail 自动创建 Agent、LLM 和工具 span；无需外部 OTLP 服务。
        agent.registerRail(new OtelRail());
        return agent;
    }

    static Tool createCreditReportTool(String reportId) {
        return new LocalFunction(ToolCard.builder()
                .id(TOOL_ID).name(TOOL_ID).description("查询客户的演示征信档案")
                .inputParams(Map.of("type", "object", "properties", Map.of(
                        "customer_id", Map.of("type", "string", "description", "客户编号，例如 C1001")),
                        "required", List.of("customer_id")))
                .build(), inputs -> {
                    if (!"C1001".equals(inputs.get("customer_id"))) {
                        throw new IllegalArgumentException("演示档案只包含客户 C1001");
                    }
                    return Map.of("customer_id", "C1001", "credit_score", 735,
                            "overdue_months", 0, "report_id", reportId, "data_source", "演示数据");
                });
    }

    /** 仅记录可复现配置和指纹，不序列化含 API Key 的模型客户端配置。 */
    static Map<String, Object> configurationData(String apiBase, ReActAgentConfig config, Tool tool) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sdkVersion", "0.1.16");
        data.put("modelProvider", "openai");
        data.put("apiBase", apiBase);
        data.put("modelRequestConfig", config.getModelConfigObj());
        data.put("maxIterations", 3);
        data.put("prompt", SYSTEM_PROMPT);
        data.put("promptSha256", sha256(SYSTEM_PROMPT));
        Map<String, Object> toolDefinition = JSON.convertValue(tool.getCard(), new TypeReference<>() {});
        data.put("tools", List.of(toolDefinition));
        data.put("toolSchemaSha256", sha256(JSON.writer()
                .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(toolDefinition)));
        Path source = Path.of("src", "main", "java", "CreditCallTrace.java");
        data.put("sampleSourceSha256", Files.exists(source) ? sha256(Files.readString(source)) : null);
        data.put("javaVersion", System.getProperty("java.version"));
        data.put("timeZone", ZoneId.systemDefault().getId());
        return data;
    }

    static void writeAgentOutput(BufferedWriter writer, String runId, String sessionId,
            String traceId, OutputSchema output) throws Exception {
        if ("error".equals(output.getType()) || output.getPayload() instanceof Map<?, ?> payload
                && "error".equals(payload.get("result_type"))) {
            throw new IllegalStateException("Agent 流执行失败: " + output.getPayload());
        }
        if ("answer".equals(output.getType())) {
            writeEvent(writer, runId, sessionId, traceId, "RESULT", output.getPayload());
        }
    }

    static Map<String, Object> runErrorData(Exception error) {
        StringWriter stack = new StringWriter();
        error.printStackTrace(new PrintWriter(stack));
        return Map.of("type", error.getClass().getName(), "message", String.valueOf(error.getMessage()),
                "stackTrace", stack.toString());
    }

    /** 仅格式化样例日志，直接保留官方 span 和模型响应，不封装框架调用。 */
    static Map<String, Object> traceData(TraceAgentSpan span) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("span", span);
        data.put("durationMs", span.getStartTime() == null || span.getEndTime() == null ? null
                : Duration.between(span.getStartTime(), span.getEndTime()).toMillis());
        if ("llm".equals(span.getInvokeType())) {
            UsageMetadata usage = null;
            if (span.getOutputs() instanceof Map<?, ?> outputs
                    && outputs.get("outputs") instanceof AssistantMessage response) {
                usage = response.getUsageMetadata();
            }
            data.put("usage", usage);
            // SDK 在服务未提供 token 数时也可能填 0；不能据此声称本次消耗为 0。
            data.put("usageStatus", usage != null && (usage.getInputTokens() > 0
                    || usage.getOutputTokens() > 0 || usage.getTotalTokens() > 0)
                    ? "reported_by_sdk" : "not_reported_or_zero");
        }
        return data;
    }

    static void writeEvent(BufferedWriter writer, String runId, String sessionId,
            String traceId, String event, Object data) throws Exception {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("schemaVersion", 1);
        record.put("timestamp", Instant.now().toString());
        record.put("event", event);
        record.put("runId", runId);
        record.put("sessionId", sessionId);
        record.put("caseId", "credit_c1001");
        record.put("traceId", traceId);
        record.put("data", data);
        String line = JSON.writeValueAsString(record);
        LOG.info("{} {}", event, line);
        writer.write(line);
        writer.newLine();
        writer.flush();
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
