import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class CreditLangfuseTest {
    @Test
    void frameworkErrorResultIsAnApplicationFailure() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> CreditLangfuse.printResult(Map.of("result_type", "error", "output", "模型迭代耗尽")));
        assertTrue(error.getMessage().contains("模型迭代耗尽"));
    }

    @Test
    void ordinaryAnswerDoesNotNeedAcceptanceMarkers() {
        assertDoesNotThrow(() -> CreditLangfuse.printResult(
                Map.of("result_type", "answer", "output", "普通业务结果")));
    }

    @Test
    void mapsBusinessFieldsOntoCurrentSpan() {
        InMemorySpanExporter memory = InMemorySpanExporter.create();
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(memory)).build()) {
            Span span = provider.get("unit-test").spanBuilder("credit-query").startSpan();
            try (Scope ignored = span.makeCurrent()) {
                CreditLangfuse.labelCurrentSpan("session-123", "tool",
                        Map.of("customer_id", "C1001"), Map.of("report_id", "CR-unit"));
            } finally {
                span.end();
            }
            var attrs = memory.getFinishedSpanItems().get(0).getAttributes();
            assertEquals("tool", attrs.get(AttributeKey.stringKey("langfuse.observation.type")));
            assertEquals("session-123", attrs.get(AttributeKey.stringKey("langfuse.session.id")));
            assertTrue(attrs.get(AttributeKey.stringKey("langfuse.observation.input")).contains("C1001"));
            assertTrue(attrs.get(AttributeKey.stringKey("langfuse.observation.output")).contains("CR-unit"));
        }
    }

    @Test
    @Timeout(20)
    void batchProcessorUsesConfiguredEndpointAndProjectAuthentication() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> ingestion = new AtomicReference<>();
        AtomicReference<byte[]> body = new AtomicReference<>();
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/", request -> {
            path.set(request.getRequestURI().getPath());
            authorization.set(request.getRequestHeaders().getFirst("Authorization"));
            ingestion.set(request.getRequestHeaders().getFirst("x-langfuse-ingestion-version"));
            body.set(request.getRequestBody().readAllBytes());
            request.getResponseHeaders().set("Content-Type", "application/x-protobuf");
            request.sendResponseHeaders(200, -1);
            request.close();
        });
        receiver.start();
        try {
            var config = CreditLangfuse.tracingConfig(
                    "http://127.0.0.1:" + receiver.getAddress().getPort(), "public-test", "secret-test");
            try (SdkTracerProvider provider = CreditLangfuse.tracing(config).build()) {
                provider.get("unit-test").spanBuilder("transport-check").startSpan().end();
                assertTrue(provider.forceFlush().join(10, TimeUnit.SECONDS).isSuccess());
            }
            assertEquals("/api/public/otel/v1/traces", path.get());
            assertEquals("public-test:secret-test", new String(
                    Base64.getDecoder().decode(authorization.get().substring("Basic ".length())), StandardCharsets.UTF_8));
            assertEquals("4", ingestion.get());
            assertTrue(body.get().length > 0);
        } finally {
            receiver.stop(0);
        }
    }
}
