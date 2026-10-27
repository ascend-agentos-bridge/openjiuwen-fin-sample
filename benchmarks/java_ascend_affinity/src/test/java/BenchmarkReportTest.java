import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class BenchmarkReportTest {
    @TempDir Path temp;

    static BenchmarkSample row(boolean success, boolean correct, double elapsed, Long input, Long output) {
        return new BenchmarkSample("normal", "model", 2, 1, "s", 1, false, success, correct,
                elapsed, success ? 20.0 : null, 1, 0, elapsed, input, output, null,
                success ? null : "timeout", success ? "C1001 735" : "");
    }

    @Test
    void throughputUsesBatchWallTimeAndFailuresDoNotEnterLatency() {
        var summary = BenchmarkReport.summarize(List.of(row(true, true, 100, 10L, 4L),
                row(true, false, 300, 10L, 6L), row(false, false, 2000, null, null)), 2.0);
        assertEquals(3, summary.requests());
        assertEquals(2, summary.succeeded());
        assertEquals(1, summary.correct());
        assertEquals(200, summary.meanMs());
        assertEquals(100, summary.p50Ms());
        assertNull(summary.p95Ms(), "Small samples must not imply stable P95");
        assertEquals(1.0, summary.requestsPerSecond());
        assertEquals(5.0, summary.outputTokensPerSecond());
    }

    @Test
    void missingUsageNeverBecomesZeroOrPartialTokenThroughput() {
        var summary = BenchmarkReport.summarize(List.of(row(true, true, 100, 10L, 4L),
                row(true, true, 200, null, null)), 1.0);
        assertEquals(1, summary.usageKnown());
        assertNull(summary.inputTokens());
        assertNull(summary.outputTokensPerSecond());
        assertNull(summary.cachedTokenRatio());
        assertNull(BenchmarkReport.improvement(0.0, 1.0));
        assertEquals(25.0, BenchmarkReport.improvement(100.0, 75.0));
    }

    @Test
    void allFailedStillWritesReportWithConclusionAndNonPassingOutcome() throws Exception {
        var rows = List.of(row(false, false, 500, null, null));
        var batch = new BenchmarkReport.Batch("normal", "model", 2, 1, 1.0, rows);
        BenchmarkReport.write(temp, Map.of("mode", "normal"), List.of(batch), List.of());
        var report = Files.readString(temp.resolve("report.md"));
        assertTrue(report.contains("结论"));
        assertTrue(report.contains("失败"));
        assertTrue(report.contains("未验证"));
        assertFalse(BenchmarkReport.passed(List.of(batch)));
        assertTrue(Files.exists(temp.resolve("summary.csv")));
        assertTrue(Files.exists(temp.resolve("run-manifest.json")));
    }

    @Test
    void emptyAndWarmupOnlyCannotPass() {
        assertFalse(BenchmarkReport.passed(List.of()));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkReport.summarize(List.of(), 0));
    }

    @Test
    void compareShowsImprovementAndRegressionWithoutClaimingAffinityProof() throws Exception {
        var off = row(true, true, 100, 10L, 4L);
        var on = row(true, true, 80, 10L, 4L);
        BenchmarkReport.write(temp, Map.of("mode", "compare", "cacheIsolation", "uncontrolled"), List.of(
                new BenchmarkReport.Batch("off", "model", 1, 1, 1.0, List.of(off)),
                new BenchmarkReport.Batch("on", "model", 1, 1, .8, List.of(on))), List.of());
        String report = Files.readString(temp.resolve("report.md"));
        assertTrue(report.contains("20.000"));
        assertTrue(report.contains("25.000"));
        assertTrue(report.contains("未验证"));
        assertTrue(report.contains("客户端观察"));
    }

    @Test
    void percentilesFollowNearestRankAndWarmupIsExcluded() {
        var rows = java.util.stream.IntStream.rangeClosed(1, 100)
                .mapToObj(i -> row(true, true, i, null, null)).toList();
        var summary = BenchmarkReport.summarize(rows, 1.0);
        assertEquals(50, summary.p50Ms());
        assertEquals(95, summary.p95Ms());
        assertEquals(99, summary.p99Ms());
        var warm = new BenchmarkSample("normal", "model", 1, 1, "warm", 1, true, true, true,
                10000.0, null, 1, 0, 10000.0, null, null, null, null, "C1001 735");
        var batch = new BenchmarkReport.Batch("normal", "model", 1, 1, 1, List.of(warm));
        assertFalse(BenchmarkReport.passed(List.of(batch)));
        assertEquals(0, BenchmarkReport.summarize(List.of(warm), 1).requests());
    }
}
