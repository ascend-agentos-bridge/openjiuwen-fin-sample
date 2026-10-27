import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class BenchmarkCompletionTest {
    @Test
    void partialFactsDoNotMakeTruncatedOrFilteredResponsesComplete() {
        assertTrue(AffinityBenchmark.completeAnswer("stop", "C1001 735"));
        assertFalse(AffinityBenchmark.completeAnswer("length", "C1001 735"));
        assertFalse(AffinityBenchmark.completeAnswer("content_filter", "C1001 735"));
        assertFalse(AffinityBenchmark.completeAnswer(null, "C1001 735"));
        assertFalse(AffinityBenchmark.completeAnswer("stop", ""));
    }
}
