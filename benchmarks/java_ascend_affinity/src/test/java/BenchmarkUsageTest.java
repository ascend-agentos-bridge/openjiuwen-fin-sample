import static org.junit.jupiter.api.Assertions.*;
import com.openjiuwen.core.foundation.llm.schema.UsageMetadata;
import org.junit.jupiter.api.Test;

class BenchmarkUsageTest {
    @Test
    void repeatedStreamingSnapshotsAreNotAddedTwice() {
        var usage = new BenchmarkUsage();
        var snapshot = UsageMetadata.builder().inputTokens(20).outputTokens(5).cacheTokens(10).build();
        usage.observe(snapshot);
        usage.observe(snapshot);
        usage.completeCall();
        assertEquals(20L, usage.input());
        assertEquals(5L, usage.output());
        assertEquals(10L, usage.cache());
    }

    @Test
    void missingCallOrDefaultZeroMakesAggregateUnavailable() {
        var usage = new BenchmarkUsage();
        usage.observe(UsageMetadata.builder().inputTokens(20).outputTokens(5).build());
        usage.completeCall();
        assertNull(usage.cache(), "Zero may be a missing field");
        usage.observe(new UsageMetadata());
        usage.completeCall();
        assertNull(usage.input());
        assertNull(usage.output());
    }
}
