import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;

@Tag("integration")
@Timeout(180)
class AffinityBenchmarkIntegrationTest {
    @TempDir Path temp;

    @Test
    void realModelBothWorkloadsProduceConclusiveReport() throws Exception {
        var output = temp.resolve("real-model");
        var options = BenchmarkOptions.parse(new String[]{"--workload", "both", "--sessions", "1",
                "--rounds", "1", "--repeats", "1", "--warmup", "0", "--output", output.toString()});
        int status = AffinityBenchmark.run(options, AffinityBenchmark.Connection.fromEnvironment("normal"));
        assertEquals(0, status, () -> {
            try { return Files.readString(output.resolve("requests.jsonl")); }
            catch (Exception error) { return error.toString(); }
        });
        var rows = Files.readAllLines(output.resolve("requests.jsonl"));
        assertEquals(2, rows.size());
        for (var row : rows) {
            var json = new ObjectMapper().readTree(row);
            assertTrue(json.get("success").asBoolean());
            assertTrue(json.get("correct").asBoolean());
            assertTrue(json.get("elapsedMs").asDouble() > 0);
        }
        var report = Files.readString(output.resolve("report.md"));
        assertTrue(report.contains("调用及业务校验通过"));
        assertTrue(report.contains("未验证"));
    }

    @Test
    void unreachableRealEndpointStillProducesFailureReport() throws Exception {
        var output = temp.resolve("failure");
        var options = BenchmarkOptions.parse(new String[]{"--workload", "model", "--sessions", "1",
                "--rounds", "1", "--repeats", "1", "--warmup", "0", "--timeout-seconds", "1",
                "--output", output.toString()});
        assertEquals(2, AffinityBenchmark.run(options,
                new AffinityBenchmark.Connection("http://127.0.0.1:1/v1", "unused", "unavailable")));
        assertTrue(Files.readString(output.resolve("report.md")).contains("测试失败"));
        assertTrue(Files.readString(output.resolve("requests.jsonl")).contains("\"success\":false"));
    }
}
