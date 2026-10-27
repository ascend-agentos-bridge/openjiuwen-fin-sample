import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.List;

class BenchmarkWorkersTest {
    @Test
    void workerFailurePreservesCompletedPeersAndPartialRows() throws Exception {
        var row = BenchmarkReportTest.row(true, true, 10, null, null);
        var failed = new BenchmarkWorkers.Job("failed", job -> {
            job.add(row);
            throw new IllegalStateException("cleanup failure");
        });
        var good = new BenchmarkWorkers.Job("good", job -> job.add(row));
        var result = BenchmarkWorkers.run(List.of(failed, good), 2, 1000);
        assertEquals(1, result.jobs().get(0).samples().size());
        assertNotNull(result.jobs().get(0).error());
        assertEquals(1, result.jobs().get(1).samples().size());
        assertNull(result.jobs().get(1).error());
    }

    @Test
    void deadlineKeepsPartialDataAndSealsLateWrites() throws Exception {
        var row = BenchmarkReportTest.row(true, true, 10, null, null);
        var stalled = new BenchmarkWorkers.Job("stalled", job -> {
            job.add(row);
            Thread.sleep(10000);
        });
        long start = System.nanoTime();
        var result = BenchmarkWorkers.run(List.of(stalled), 1, 100);
        assertTrue((System.nanoTime() - start) / 1e9 < 2);
        assertTrue(result.jobs().get(0).error().contains("TIMEOUT"));
        assertEquals(1, result.jobs().get(0).samples().size());
        stalled.add(row);
        assertEquals(1, stalled.snapshot().size());
    }
}
