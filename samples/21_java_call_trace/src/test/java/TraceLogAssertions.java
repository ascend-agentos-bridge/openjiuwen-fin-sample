import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Acceptance assertions belong to tests; production only writes the events. */
final class TraceLogAssertions {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private TraceLogAssertions() {
    }

    static List<JsonNode> readMatchingLogFiles(Path directory) throws Exception {
        List<JsonNode> records = new ArrayList<>();
        for (String line : Files.readAllLines(directory.resolve("call_trace.jsonl"))) {
            if (!line.isBlank()) {
                records.add(JSON.readTree(line));
            }
        }
        assertFalse(records.isEmpty(), "JSONL must contain this run's events");
        List<JsonNode> readableRecords = new ArrayList<>();
        for (String line : Files.readAllLines(directory.resolve("call_trace.log"))) {
            String message = line.substring(line.indexOf(" - ") + 3);
            readableRecords.add(JSON.readTree(message.substring(message.indexOf(' ') + 1)));
        }
        assertEquals(records, readableRecords, "Text and JSONL logs must contain the same events in order");
        assertEquals(1, records.stream().map(record -> record.path("runId").asText()).distinct().count());
        assertEquals(1, records.stream().map(record -> record.path("sessionId").asText()).distinct().count());
        for (JsonNode record : records) {
            assertEquals(1, record.path("schemaVersion").asInt());
            assertEquals("credit_c1001", record.path("caseId").asText());
            assertFalse(record.path("runId").asText().isBlank());
            assertFalse(record.path("sessionId").asText().isBlank());
            Instant.parse(record.path("timestamp").asText());
            assertFalse(List.of("VALIDATION", "SUCCESS").contains(record.path("event").asText()),
                    "The demonstration must not write acceptance events");
        }
        JsonNode config = event(records, "CONFIG");
        assertTrue(config.path("traceId").isNull());
        assertConfiguration(config.path("data"));
        return records;
    }

    static void assertConfiguration(JsonNode config) throws Exception {
        assertFalse(config.path("prompt").asText().isBlank());
        assertTrue(config.path("tools").isArray() && !config.path("tools").isEmpty());
        assertTrue(config.path("modelRequestConfig").isObject() && !config.path("modelRequestConfig").isEmpty());
        assertEquals(sha256(config.path("prompt").asText()), config.path("promptSha256").asText());
        Map<String, Object> tool = JSON.convertValue(config.path("tools").get(0), new TypeReference<>() {});
        String canonical = JSON.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(tool);
        assertEquals(sha256(canonical), config.path("toolSchemaSha256").asText());
        if (!config.path("sampleSourceSha256").isNull()) {
            assertTrue(config.path("sampleSourceSha256").asText().matches("[0-9a-f]{64}"));
        }
        assertFalse(config.has("apiKey"));
        assertFalse(config.has("api_key"));
    }

    static Map<String, JsonNode> latestTraceEvents(List<JsonNode> records) {
        Map<String, JsonNode> latest = new LinkedHashMap<>();
        for (JsonNode record : records) {
            if ("TRACE".equals(record.path("event").asText())) {
                JsonNode span = record.path("data").path("span");
                assertFalse(span.path("invokeId").asText().isBlank());
                latest.put(span.path("invokeId").asText(), record);
            }
        }
        assertFalse(latest.isEmpty(), "The real execution must produce official trace spans");
        return latest;
    }

    static JsonNode assertTraceRelationships(List<JsonNode> records, Map<String, JsonNode> latest) {
        List<JsonNode> roots = latest.values().stream().map(record -> record.path("data").path("span"))
                .filter(span -> "chain".equals(span.path("invokeType").asText())
                        && "credit_trace_agent".equals(span.path("name").asText())).toList();
        assertEquals(1, roots.size(), "There must be exactly one Agent root span");
        JsonNode root = roots.get(0);
        assertFalse(root.path("traceId").asText().isBlank());
        assertTrue(root.path("parentInvokeId").isNull() || root.path("parentInvokeId").asText().isBlank());
        for (JsonNode record : records) {
            if (!"TRACE".equals(record.path("event").asText())) {
                continue;
            }
            JsonNode data = record.path("data");
            JsonNode span = data.path("span");
            assertEquals(root.path("traceId"), record.path("traceId"));
            assertEquals(root.path("traceId"), span.path("traceId"));
            for (String field : List.of("inputs", "outputs", "error", "metaData", "startTime", "endTime")) {
                assertTrue(span.has(field), "Official span field must be preserved: " + field);
            }
            if (!root.path("invokeId").equals(span.path("invokeId"))) {
                assertEquals(root.path("invokeId"), span.path("parentInvokeId"));
            }
            if (!span.path("endTime").isNull()) {
                assertTrue(data.path("durationMs").isNumber() && data.path("durationMs").asLong() >= 0);
            }
            if ("llm".equals(span.path("invokeType").asText())) {
                assertTrue(data.has("usage"));
                assertTrue(List.of("reported_by_sdk", "not_reported_or_zero").contains(data.path("usageStatus").asText()));
                assertTrue(span.path("inputs").path("messages").isArray()
                        && !span.path("inputs").path("messages").isEmpty());
            }
        }
        return root;
    }

    static void assertCompletedTraceAndPrintEvidence(List<JsonNode> records) throws Exception {
        Map<String, JsonNode> latest = latestTraceEvents(records);
        JsonNode root = assertTraceRelationships(records, latest);
        List<JsonNode> llms = new ArrayList<>();
        List<JsonNode> tools = new ArrayList<>();
        for (JsonNode record : latest.values()) {
            JsonNode span = record.path("data").path("span");
            assertEquals("finish", span.path("status").asText());
            assertTrue(span.path("error").isNull());
            assertFalse(span.path("endTime").isNull());
            assertFalse(span.path("outputs").isNull());
            if ("llm".equals(span.path("invokeType").asText())) {
                llms.add(record.path("data"));
            }
            if ("plugin".equals(span.path("invokeType").asText())) {
                tools.add(span);
            }
        }
        assertTrue(llms.size() >= 2, "Both tool selection and summary must use the real model");
        assertTrue(llms.stream().anyMatch(data -> !data.path("span").path("outputs")
                .path("outputs").path("tool_calls").isEmpty()), "The real model must request the credit tool");
        assertEquals(1, tools.size(), "The credit report is queried exactly once");
        JsonNode tool = tools.get(0);
        assertEquals("query_credit_report", tool.path("name").asText());
        JsonNode arguments = tool.path("inputs").path("inputs");
        if (arguments.isTextual()) {
            arguments = JSON.readTree(arguments.asText());
        }
        assertEquals("C1001", arguments.path("customer_id").asText());
        JsonNode report = tool.path("outputs").path("outputs");
        assertEquals("C1001", report.path("customer_id").asText());
        assertEquals(735, report.path("credit_score").asInt());
        String reportId = report.path("report_id").asText();
        assertTrue(reportId.matches("CR-[0-9a-f]{8}"));
        JsonNode resultEvent = event(records, "RESULT");
        assertEquals(1, records.stream().filter(record -> "RESULT".equals(record.path("event").asText())).count());
        assertEquals(root.path("traceId"), resultEvent.path("traceId"));
        JsonNode result = resultEvent.path("data");
        assertEquals("answer", result.path("result_type").asText());
        assertEquals(root.path("outputs").path("outputs"), result);
        String answer = result.path("output").asText();
        assertTrue(answer.contains(reportId), "The model must preserve this run's actual tool report ID");
        assertTrue(answer.contains("C1001"));
        assertTrue(answer.contains("735"));
        assertFalse(records.stream().anyMatch(record -> "RUN_ERROR".equals(record.path("event").asText())));

        long inputTokens = 0;
        long outputTokens = 0;
        long totalTokens = 0;
        int reportedCalls = 0;
        for (JsonNode llm : llms) {
            JsonNode usage = llm.path("usage");
            boolean reported = usage.path("input_tokens").asLong() > 0 || usage.path("output_tokens").asLong() > 0
                    || usage.path("total_tokens").asLong() > 0;
            assertEquals(reported ? "reported_by_sdk" : "not_reported_or_zero", llm.path("usageStatus").asText());
            if (reported) {
                reportedCalls++;
                inputTokens += usage.path("input_tokens").asLong();
                outputTokens += usage.path("output_tokens").asLong();
                totalTokens += usage.path("total_tokens").asLong();
            }
        }
        System.out.println("RESULT: " + answer);
        System.out.printf("TRACE SUMMARY: spanCount=%d, llmCalls=%d, toolCalls=%d%n", latest.size(), llms.size(), tools.size());
        System.out.printf("USAGE SUMMARY: inputTokens=%d, outputTokens=%d, totalTokens=%d, reportedCalls=%d/%d%n",
                inputTokens, outputTokens, totalTokens, reportedCalls, llms.size());
        if (reportedCalls < llms.size()) {
            System.out.println("USAGE NOTE: missing or zero SDK counts are not measured zero consumption.");
        }
    }

    static void assertFailedTrace(List<JsonNode> records) {
        Map<String, JsonNode> latest = latestTraceEvents(records);
        JsonNode root = assertTraceRelationships(records, latest);
        assertEquals("error", root.path("status").asText());
        assertTrue(latest.values().stream().map(record -> record.path("data").path("span"))
                .anyMatch(span -> "llm".equals(span.path("invokeType").asText())
                        && !span.path("error").path("message").asText().isBlank()));
        JsonNode failure = event(records, "RUN_ERROR");
        assertEquals(root.path("traceId"), failure.path("traceId"));
        assertFalse(failure.path("data").path("type").asText().isBlank());
        assertFalse(failure.path("data").path("message").asText().isBlank());
        assertFalse(failure.path("data").path("stackTrace").asText().isBlank());
        assertFalse(records.stream().anyMatch(record -> "RESULT".equals(record.path("event").asText())));
        System.out.println("ERROR TRACE: " + failure.path("data").path("message").asText());
    }

    static JsonNode event(List<JsonNode> records, String name) {
        JsonNode result = records.stream().filter(record -> name.equals(record.path("event").asText())).findFirst().orElse(null);
        assertNotNull(result, "Missing event: " + name);
        return result;
    }

    static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
