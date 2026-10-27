import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.UsageMetadata;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.session.tracer.TraceAgentSpan;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Offline tests exercise business data and log formatting; no model or recorded answer is used. */
class CreditCallTraceTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Test
    void eventIsValidJsonAndPreservesCorrelationAndEscapedBusinessText() throws Exception {
        StringWriter sink = new StringWriter();
        String businessText = "客户\"C1001\"\n征信\\档案";
        CreditCallTrace.writeEvent(new BufferedWriter(sink), "run-1", "session-1", null,
                "CONFIG", Map.of("text", businessText));

        JsonNode record = JSON.readTree(sink.toString());
        assertEquals(1, sink.toString().lines().count(), "A JSONL event occupies exactly one line");
        assertEquals(1, record.path("schemaVersion").asInt());
        Instant.parse(record.path("timestamp").asText());
        assertEquals("run-1", record.path("runId").asText());
        assertEquals("session-1", record.path("sessionId").asText());
        assertEquals("credit_c1001", record.path("caseId").asText());
        assertTrue(record.path("traceId").isNull());
        assertEquals(businessText, record.path("data").path("text").asText());
    }

    @Test
    void answerOutputIsPrintedAsTheBusinessResultWithoutACompletedTrace() throws Exception {
        StringWriter sink = new StringWriter();
        Map<String, Object> payload = Map.of("result_type", "answer", "output", "日志格式测试文本");

        CreditCallTrace.writeAgentOutput(new BufferedWriter(sink), "run", "session", "trace",
                new OutputSchema("answer", 0, payload));

        JsonNode record = JSON.readTree(sink.toString());
        assertEquals("RESULT", record.path("event").asText());
        assertEquals("trace", record.path("traceId").asText());
        assertEquals(JSON.valueToTree(payload), record.path("data"));
    }

    @Test
    void partialModelOutputDoesNotBecomeAnAdditionalBusinessResult() throws Exception {
        StringWriter sink = new StringWriter();
        CreditCallTrace.writeAgentOutput(new BufferedWriter(sink), "run", "session", "trace",
                new OutputSchema("llm_output", 0, Map.of("content", "部分内容")));
        assertTrue(sink.toString().isEmpty());
    }

    @Test
    void streamFailurePropagatesAndKeepsItsMessageAndStackInErrorJson() throws Exception {
        StringWriter sink = new StringWriter();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> CreditCallTrace.writeAgentOutput(new BufferedWriter(sink), "run", "session", "trace",
                        new OutputSchema("error", 0, Map.of("output", "连接失败\n详情"))));
        assertTrue(sink.toString().isEmpty(), "An error must not emit a successful business result");

        CreditCallTrace.writeEvent(new BufferedWriter(sink), "run", "session", "trace", "RUN_ERROR",
                CreditCallTrace.runErrorData(failure));
        JsonNode error = JSON.readTree(sink.toString());
        assertEquals("RUN_ERROR", error.path("event").asText());
        assertEquals("java.lang.IllegalStateException", error.path("data").path("type").asText());
        assertTrue(error.path("data").path("message").asText().contains("连接失败\n详情"));
        assertTrue(error.path("data").path("stackTrace").asText().contains("CreditCallTrace.writeAgentOutput"));
    }

    @Test
    void errorResultInsideAnAnswerEventIsStillAnExecutionFailure() {
        StringWriter sink = new StringWriter();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> CreditCallTrace.writeAgentOutput(new BufferedWriter(sink), "run", "session", "trace",
                        new OutputSchema("answer", 0, Map.of("result_type", "error", "output", "模型调用失败"))));
        assertTrue(failure.getMessage().contains("模型调用失败"));
        assertTrue(sink.toString().isEmpty(), "Execution errors must not be emitted as business results");
    }

    @Test
    void traceJsonPreservesOfficialInputsOutputsErrorAndElapsedMilliseconds() throws Exception {
        TraceAgentSpan span = new TraceAgentSpan("trace", "tool-invoke", "agent-invoke");
        span.setInvokeType("plugin");
        span.setName("query_credit_report");
        span.setStartTime(LocalDateTime.of(2026, 1, 1, 10, 0));
        span.setEndTime(span.getStartTime().plusNanos(125_000_000));
        span.setInputs(Map.of("inputs", "{\"customer_id\":\"C9999\"}"));
        span.setError(Map.of("message", "缺少客户档案"));
        span.setStatus("error");
        span.setMetaData(Map.of("type", "plugin"));
        StringWriter sink = new StringWriter();

        CreditCallTrace.writeEvent(new BufferedWriter(sink), "run", "session", "trace", "TRACE",
                CreditCallTrace.traceData(span));

        JsonNode data = JSON.readTree(sink.toString()).path("data");
        assertEquals(125, data.path("durationMs").asLong());
        assertEquals("{\"customer_id\":\"C9999\"}", data.path("span").path("inputs").path("inputs").asText());
        assertTrue(data.path("span").has("outputs"));
        assertTrue(data.path("span").path("outputs").isNull());
        assertEquals("缺少客户档案", data.path("span").path("error").path("message").asText());
        assertEquals("agent-invoke", data.path("span").path("parentInvokeId").asText());
    }

    @Test
    void llmFormattingKeepsTheUsageReportedByTheOfficialResponse() {
        UsageMetadata usage = UsageMetadata.builder().inputTokens(11).outputTokens(7).totalTokens(18).build();
        Map<String, Object> data = CreditCallTrace.traceData(llmSpan(usage));

        JsonNode json = JSON.valueToTree(data);
        assertEquals("reported_by_sdk", data.get("usageStatus"));
        assertEquals(11, json.path("usage").path("input_tokens").asInt());
        assertEquals(7, json.path("usage").path("output_tokens").asInt());
        assertEquals(18, json.path("usage").path("total_tokens").asInt());
    }

    @Test
    void zeroAndMissingUsageRemainExplicitlyUnreported() {
        Map<String, Object> absent = CreditCallTrace.traceData(llmSpan(null));
        assertNull(absent.get("usage"));
        assertNull(absent.get("durationMs"));
        assertEquals("not_reported_or_zero", absent.get("usageStatus"));

        Map<String, Object> zero = CreditCallTrace.traceData(llmSpan(new UsageMetadata()));
        assertInstanceOf(UsageMetadata.class, zero.get("usage"));
        assertEquals("not_reported_or_zero", zero.get("usageStatus"));
    }

    @Test
    void configurationIncludesReproducibleFingerprintsWithoutClientCredentials() throws Exception {
        String address = "http://127.0.0.1:1234/v1";
        ReActAgentConfig config = CreditCallTrace.creditAgentConfig(address);
        config.configureModelClient("openai", "sensitive-key-not-for-logs", address, "test-model", true);
        JsonNode data = JSON.valueToTree(CreditCallTrace.configurationData(address, config,
                CreditCallTrace.createCreditReportTool("CR-unit")));

        TraceLogAssertions.assertConfiguration(data);
        assertEquals("0.1.16", data.path("sdkVersion").asText());
        assertFalse(data.toString().contains("sensitive-key-not-for-logs"));
        assertFalse(data.toString().contains("apiKey"));
        assertFalse(data.toString().contains("api_key"));
        assertEquals(TraceLogAssertions.sha256(java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/CreditCallTrace.java"))), data.path("sampleSourceSha256").asText());
    }

    @Test
    void toolSchemaFingerprintIgnoresMapOrderAndChangesWithTheSchema() throws Exception {
        Map<String, Object> forward = new LinkedHashMap<>();
        forward.put("type", "object");
        forward.put("required", List.of("customer_id"));
        Map<String, Object> reverse = new LinkedHashMap<>();
        reverse.put("required", List.of("customer_id"));
        reverse.put("type", "object");

        assertEquals(toolFingerprint(forward), toolFingerprint(reverse));
        assertNotEquals(toolFingerprint(forward), toolFingerprint(Map.of("type", "object")));
    }

    @Test
    void creditReportKeepsTheRunIdentifierAndRejectsUnknownCustomers() throws Exception {
        Tool tool = CreditCallTrace.createCreditReportTool("CR-unit-case");
        assertEquals(Map.of("customer_id", "C1001", "credit_score", 735, "overdue_months", 0,
                "report_id", "CR-unit-case", "data_source", "演示数据"), tool.invoke(Map.of("customer_id", "C1001")));
        assertThrows(IllegalArgumentException.class, () -> tool.invoke(Map.of("customer_id", "C9999")));
    }

    private TraceAgentSpan llmSpan(UsageMetadata usage) {
        TraceAgentSpan span = new TraceAgentSpan("trace", "llm-invoke", "agent-invoke");
        span.setInvokeType("llm");
        AssistantMessage response = new AssistantMessage("格式化测试数据");
        response.setUsageMetadata(usage);
        span.setOutputs(Map.of("outputs", response));
        return span;
    }

    private Object toolFingerprint(Map<String, Object> schema) throws Exception {
        Tool tool = new LocalFunction(ToolCard.builder().id("schema-test").name("schema-test")
                .description("schema fingerprint test").inputParams(schema).build(), inputs -> inputs);
        return CreditCallTrace.configurationData("http://localhost/v1",
                CreditCallTrace.creditAgentConfig("http://localhost/v1"), tool).get("toolSchemaSha256");
    }
}
