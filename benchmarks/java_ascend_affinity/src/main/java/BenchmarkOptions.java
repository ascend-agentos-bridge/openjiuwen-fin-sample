import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Command-line configuration for the benchmark, not an SDK abstraction. */
record BenchmarkOptions(String mode, String workload, List<Integer> concurrency, int rounds, int sessions,
                        int repeats, int warmup, int payloadChars, int maxTokens, double timeoutSeconds, double batchTimeoutSeconds,
                        String cacheIsolation, Path output) {
    static BenchmarkOptions parse(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        Set<String> known = Set.of("mode", "workload", "concurrency", "rounds", "sessions", "repeats",
                "warmup", "payload-chars", "max-tokens", "timeout-seconds", "batch-timeout-seconds", "cache-isolation", "output");
        for (int index = 0; index < args.length; index += 2) {
            String key = args[index].startsWith("--") ? args[index].substring(2) : "";
            if (!known.contains(key) || index + 1 == args.length || args[index + 1].startsWith("--")
                    || values.putIfAbsent(key, args[index + 1]) != null) {
                throw new IllegalArgumentException("未知、重复或缺值参数: " + args[index]);
            }
        }
        String mode = choice(values, "mode", "normal", Set.of("normal", "compare"));
        String workload = choice(values, "workload", "both", Set.of("both", "model", "agent"));
        List<Integer> concurrency = Arrays.stream(values.getOrDefault("concurrency", "1").split(",", -1))
                .map(value -> positive("concurrency", value, 1)).distinct().toList();
        double timeout = Double.parseDouble(values.getOrDefault("timeout-seconds", "60"));
        if (!Double.isFinite(timeout) || timeout <= 0) throw new IllegalArgumentException("timeout-seconds 必须大于 0");
        double batchTimeout = Double.parseDouble(values.getOrDefault("batch-timeout-seconds", "300"));
        if (!Double.isFinite(batchTimeout) || batchTimeout <= 0) throw new IllegalArgumentException("batch-timeout-seconds 必须大于 0");
        int sessions = positive("sessions", values.getOrDefault("sessions", "2"), 1);
        if (concurrency.stream().anyMatch(c -> c > sessions)) {
            throw new IllegalArgumentException("sessions 必须不小于最大 concurrency，避免虚报实际并发");
        }
        String path = values.getOrDefault("output", "reports/run-" + ZonedDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")));
        if (path.isBlank()) throw new IllegalArgumentException("output 不能为空");
        return new BenchmarkOptions(mode, workload, concurrency,
                positive("rounds", values.getOrDefault("rounds", "3"), 1),
                sessions,
                positive("repeats", values.getOrDefault("repeats", "3"), 1),
                positive("warmup", values.getOrDefault("warmup", "1"), 0),
                positive("payload-chars", values.getOrDefault("payload-chars", "2048"), 0),
                positive("max-tokens", values.getOrDefault("max-tokens", "512"), 1), timeout, batchTimeout,
                choice(values, "cache-isolation", "uncontrolled", Set.of("uncontrolled", "external")),
                Path.of(path).toAbsolutePath().normalize());
    }

    private static String choice(Map<String, String> values, String key, String fallback, Set<String> allowed) {
        String value = values.getOrDefault(key, fallback);
        if (!allowed.contains(value)) throw new IllegalArgumentException(key + " 可选值: " + allowed);
        return value;
    }

    private static int positive(String key, String value, int minimum) {
        int number = Integer.parseInt(value);
        if (number < minimum) throw new IllegalArgumentException(key + " 必须 >= " + minimum);
        return number;
    }
}
