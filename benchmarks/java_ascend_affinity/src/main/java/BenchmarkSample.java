/** One measured round; null metrics mean unavailable, never a fabricated zero. */
record BenchmarkSample(String group, String workload, int concurrency, int repeat, String sessionId, int round,
                       boolean warmup, boolean success, boolean correct, Double elapsedMs, Double firstContentMs,
                       Integer modelCalls, Integer toolCalls, Double modelMs, Long inputTokens, Long outputTokens,
                       Long cacheTokens, String error, String answer) { }
