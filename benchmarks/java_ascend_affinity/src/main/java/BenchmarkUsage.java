import com.openjiuwen.core.foundation.llm.schema.UsageMetadata;

/** Records the latest cumulative usage once per model call, conservatively. */
final class BenchmarkUsage {
    private UsageMetadata latest;
    private int completed;
    private int known;
    private int cacheKnown;
    private long input;
    private long output;
    private long cache;

    synchronized void observe(UsageMetadata snapshot) {
        if (snapshot != null && snapshot.getInputTokens() > 0 && snapshot.getOutputTokens() > 0) {
            latest = snapshot;
        }
    }

    synchronized void completeCall() {
        completed++;
        if (latest != null) {
            known++;
            input += latest.getInputTokens();
            output += latest.getOutputTokens();
            if (latest.getCacheTokens() > 0 && latest.getCacheTokens() <= latest.getInputTokens()) {
                cacheKnown++;
                cache += latest.getCacheTokens();
            }
        }
        latest = null;
    }

    synchronized Long input() { return completed > 0 && completed == known ? input : null; }
    synchronized Long output() { return completed > 0 && completed == known ? output : null; }
    synchronized Long cache() { return completed > 0 && completed == cacheKnown ? cache : null; }
}
