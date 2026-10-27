import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Runs the actual demonstration in a fresh JVM, against a real endpoint; never replays model answers. */
@Tag("integration")
@Timeout(260)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CreditCallTraceIntegrationTest {
    @Test
    @Order(1)
    void unavailableModelServiceProducesCorrelatedErrorLogsAndNonzeroExit() throws Exception {
        Path output = Path.of("target", "unavailable-service-output.txt");
        assertNotEquals(0, runDemonstration(output, "http://127.0.0.1:1/v1"), Files.readString(output));
        TraceLogAssertions.assertFailedTrace(TraceLogAssertions.readMatchingLogFiles(Path.of("logs")));
    }

    @Test
    @Order(2)
    void realModelPreservesTheGeneratedReportAndWritesACompleteCallTrace() throws Exception {
        Path output = Path.of("target", "real-model-output.txt");
        assertEquals(0, runDemonstration(output, null), Files.readString(output));
        TraceLogAssertions.assertCompletedTraceAndPrintEvidence(
                TraceLogAssertions.readMatchingLogFiles(Path.of("logs")));
    }

    private int runDemonstration(Path output, String apiBase) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        ProcessBuilder process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dlogback.configurationFile=logback-credit-trace.xml", "-cp", classpath, "CreditCallTrace");
        if (apiBase != null) {
            process.environment().put("API_BASE", apiBase);
        }
        process.redirectErrorStream(true).redirectOutput(output.toFile());
        Process child = process.start();
        boolean completed = child.waitFor(240, TimeUnit.SECONDS);
        if (!completed) {
            child.destroyForcibly();
        }
        assertTrue(completed, "Demonstration exceeded 240 seconds; inspect " + output.toAbsolutePath());
        return child.exitValue();
    }
}
