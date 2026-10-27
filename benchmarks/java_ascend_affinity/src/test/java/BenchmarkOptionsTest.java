import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class BenchmarkOptionsTest {
    @Test
    void defaultsAreNormalAndBothWorkloads() {
        var options = BenchmarkOptions.parse(new String[0]);
        assertEquals("normal", options.mode());
        assertEquals("both", options.workload());
        assertEquals(1, options.concurrency().get(0));
        assertEquals(512, options.maxTokens());
    }

    @Test
    void rejectsTyposAndInvalidNumbers() {
        for (String[] args : new String[][]{{"--mode", "fake"}, {"--rounds", "0"},
                {"--concurrency", "1,0"}, {"--warmup", "-1"}, {"--unknown", "1"},
                {"--repeats"}, {"--mode", "normal", "--mode", "compare"}}) {
            assertThrows(IllegalArgumentException.class, () -> BenchmarkOptions.parse(args));
        }
        assertThrows(IllegalArgumentException.class,
                () -> BenchmarkOptions.parse(new String[]{"--concurrency", "4", "--sessions", "2"}));
        assertThrows(IllegalArgumentException.class,
                () -> BenchmarkOptions.parse(new String[]{"--batch-timeout-seconds", "NaN"}));
    }
}
