import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Bounded benchmark workers; retains progress independently of task completion. */
final class BenchmarkWorkers {
    @FunctionalInterface interface Action { void run(Job job) throws Exception; }
    record Result(List<Completed> jobs, boolean terminated) { }
    record Completed(String id, List<BenchmarkSample> samples, String error) { }

    static final class Job {
        private final String id;
        private final Action action;
        private final List<BenchmarkSample> samples = new ArrayList<>();
        private AutoCloseable stream;
        private boolean sealed;
        private String error;

        Job(String id, Action action) { this.id = id; this.action = action; }
        String id() { return id; }
        synchronized void add(BenchmarkSample sample) { if (!sealed) samples.add(sample); }
        synchronized List<BenchmarkSample> snapshot() { return List.copyOf(samples); }
        synchronized boolean cancelled() { return sealed; }
        synchronized void failure(String reason) { if (!sealed) error = reason; }

        synchronized void stream(Object candidate) {
            stream = candidate instanceof AutoCloseable closeable ? closeable : null;
            if (sealed) closeAsynchronously(stream);
        }

        synchronized Completed seal(boolean timeout) {
            sealed = true;
            if (timeout) {
                error = "SESSION_TIMEOUT: batch deadline exceeded";
                closeAsynchronously(stream);
            }
            return new Completed(id, List.copyOf(samples), error);
        }
    }

    private BenchmarkWorkers() { }

    static Result run(List<Job> jobs, int concurrency, long deadlineMillis) throws InterruptedException {
        var executor = Executors.newFixedThreadPool(concurrency, task -> {
            var thread = new Thread(task, "benchmark-session");
            thread.setDaemon(true);
            return thread;
        });
        try {
            var tasks = new ArrayList<Callable<Void>>();
            for (var job : jobs) tasks.add(() -> {
                try { job.action.run(job); }
                catch (Exception error) { job.failure(error.getClass().getSimpleName() + ": " + error.getMessage()); }
                return null;
            });
            var futures = executor.invokeAll(tasks, deadlineMillis, TimeUnit.MILLISECONDS);
            var completed = new ArrayList<Completed>();
            for (int index = 0; index < futures.size(); index++) {
                var future = futures.get(index);
                if (!future.isCancelled()) {
                    try { future.get(); }
                    catch (java.util.concurrent.ExecutionException error) {
                        jobs.get(index).failure("WORKER_ERROR: " + error.getCause().getClass().getSimpleName());
                    }
                }
                completed.add(jobs.get(index).seal(future.isCancelled()));
            }
            executor.shutdownNow();
            return new Result(List.copyOf(completed), executor.awaitTermination(500, TimeUnit.MILLISECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private static void closeAsynchronously(AutoCloseable stream) {
        if (stream == null) return;
        // A stalled BufferedReader.close may itself block; it must not block reporting.
        var closer = new Thread(() -> {
            try { stream.close(); }
            catch (Exception ignored) { /* Timeout/failure is already recorded. */ }
        }, "benchmark-stream-close");
        closer.setDaemon(true);
        closer.start();
    }
}
