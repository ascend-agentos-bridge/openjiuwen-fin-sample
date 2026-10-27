import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.context.ContextEngine;
import com.openjiuwen.core.context.processor.compressor.MicroCompactProcessorConfig;
import com.openjiuwen.core.context.schema.ContextEngineConfig;
import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessageChunk;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.llm.schema.SystemMessage;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.base.TagMatchStrategy;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.core.singleagent.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.rail.AgentCallbackEvent;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import java.io.BufferedWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.DoubleAdder;

/** Real SDK requests plus benchmark orchestration; no replacement model API. */
public final class AffinityBenchmark {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOOL = "read_credit_file";
    private static final String SYSTEM = "你是银行贷前复核助手。只根据给定档案，简短回答客户编号和信用评分。";
    private static final String AGENT_SYSTEM = "你是银行贷前复核助手。每轮必须先调用一次 read_credit_file 查询档案。"
            + "工具返回后原样回答客户编号和信用评分，不要再次调用工具，不输出完整明细。";

    record Connection(String apiBase, String apiKey, String model) {
        Connection {
            URI uri = URI.create(apiBase);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("API_BASE 必须为不含凭据、query 或 fragment 的 HTTP(S) 地址");
            }
            if (model.isBlank()) throw new IllegalArgumentException("MODEL_NAME 不能为空");
        }

        static Connection fromEnvironment(String mode) {
            String base = env("API_BASE", "compare".equals(mode) ? "" : "http://127.0.0.1:1234/v1");
            if (base.isBlank()) throw new IllegalArgumentException("compare 必须设置 API_BASE 为亲和插件服务根地址");
            if ("compare".equals(mode) && base.replaceAll("/+$", "").endsWith("/v1")) {
                throw new IllegalArgumentException("InferenceAffinity 的 API_BASE 不带 /v1");
            }
            return new Connection(base, env("API_KEY", "lm-studio"), env("MODEL_NAME", "google/gemma-4-e4b"));
        }
    }

    private AffinityBenchmark() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--help".equals(args[0])) {
            System.out.println("--mode normal|compare --workload both|model|agent --concurrency 1,4,8 "
                    + "--sessions 2 --rounds 3 --repeats 3 --warmup 1 --payload-chars 2048 "
                    + "--max-tokens 512 --timeout-seconds 60 --batch-timeout-seconds 300 "
                    + "--cache-isolation uncontrolled|external --output PATH");
            return;
        }
        var options = BenchmarkOptions.parse(args);
        int status = run(options, Connection.fromEnvironment(options.mode()));
        if (status != 0) throw new IllegalStateException("benchmark 失败，已生成报告，状态=" + status);
    }

    static int run(BenchmarkOptions options, Connection connection) throws Exception {
        Path output = options.output();
        if (Files.exists(output)) throw new IllegalArgumentException("output 已存在，请使用新的目录: " + output);
        Files.createDirectories(output);
        var manifest = manifest(options, connection);
        var batches = new ArrayList<BenchmarkReport.Batch>();
        var errors = new ArrayList<String>();
        // A valid manifest survives even if initialization or all requests fail.
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("run-manifest.json").toFile(), manifest);
        try (BufferedWriter raw = Files.newBufferedWriter(output.resolve("requests.jsonl"))) {
            try {
                var workloads = "both".equals(options.workload()) ? List.of("model", "agent") : List.of(options.workload());
                for (String workload : workloads) {
                    for (int concurrency : options.concurrency()) {
                        for (int repeat = 1; repeat <= options.repeats(); repeat++) {
                            var groups = "normal".equals(options.mode()) ? List.of("normal")
                                    : repeat % 2 == 1 ? List.of("off", "on") : List.of("on", "off");
                            for (String group : groups) {
                                if (options.warmup() > 0) {
                                    var warm = batch(options, connection, group, workload, concurrency, repeat, true);
                                    writeRows(raw, warm.batch().samples());
                                    errors.addAll(warm.errors());
                                    if (warm.batch().samples().stream().anyMatch(s -> !s.success() || !s.correct())) {
                                        errors.add("预热失败: " + group + "/" + workload + "/" + concurrency + "/" + repeat);
                                    }
                                    if (!warm.errors().isEmpty()) throw new IllegalStateException("预热 worker 异常，停止后续实验");
                                }
                                var measured = batch(options, connection, group, workload, concurrency, repeat, false);
                                batches.add(measured.batch());
                                writeRows(raw, measured.batch().samples());
                                errors.addAll(measured.errors());
                                long success = measured.batch().samples().stream().filter(BenchmarkSample::success).count();
                                System.out.printf("%s/%s c=%d repeat=%d success=%d/%d wall=%.3fs%n", group, workload,
                                        concurrency, repeat, success, measured.batch().samples().size(), measured.batch().wallSeconds());
                                if (!measured.errors().isEmpty()) throw new IllegalStateException("worker 异常，已保留部分数据并停止后续实验");
                            }
                        }
                    }
                }
            } catch (Exception error) {
                errors.add(error.getClass().getSimpleName() + ": " + sanitized(error, connection));
            } finally {
                try {
                    var stopped = BenchmarkWorkers.run(List.of(new BenchmarkWorkers.Job("runner-stop", job -> {
                        if (!Runner.stop()) job.failure("Runner.stop returned false");
                    })), 1, 5000);
                    if (!stopped.terminated() || stopped.jobs().get(0).error() != null) errors.add("Runner 清理失败或超时");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    errors.add("Runner 清理被中断");
                } finally {
                    manifest.put("finishedAt", Instant.now().toString());
                    BenchmarkReport.write(output, manifest, batches, errors);
                }
            }
        }
        boolean passed = errors.isEmpty() && BenchmarkReport.passed(batches);
        System.out.println("结论: " + (passed ? "真实调用及业务校验通过" : "存在调用、预热或业务校验失败")
                + "；昇腾亲和效果未验证。报告: " + output.resolve("report.md"));
        return passed ? 0 : 2;
    }

    private static void writeRows(BufferedWriter writer, List<BenchmarkSample> rows) throws Exception {
        for (var row : rows) {
            writer.write(JSON.writeValueAsString(row));
            writer.newLine();
        }
        writer.flush();
    }

    private record BatchExecution(BenchmarkReport.Batch batch, List<String> errors) { }

    private static BatchExecution batch(BenchmarkOptions o, Connection c, String group, String workload,
                                               int concurrency, int repeat, boolean warmup) throws Exception {
        int sessions = warmup ? Math.min(concurrency, o.sessions()) : o.sessions();
        int rounds = warmup ? o.warmup() : o.rounds();
        long start = System.nanoTime();
        var rows = new ArrayList<BenchmarkSample>();
        var jobs = new ArrayList<BenchmarkWorkers.Job>();
        for (int index = 0; index < sessions; index++) {
            jobs.add(new BenchmarkWorkers.Job("bench-" + UUID.randomUUID(),
                    job -> session(o, c, group, workload, concurrency, repeat, rounds, warmup, job)));
        }
        long deadline = Math.max(1, (long) (o.batchTimeoutSeconds() * 1000));
        var workers = BenchmarkWorkers.run(jobs, concurrency, deadline);
        var errors = new ArrayList<String>();
        for (var completed : workers.jobs()) {
            rows.addAll(completed.samples());
            if (completed.error() != null) errors.add(sanitized(new IllegalStateException(completed.error()), c));
            for (int missing = completed.samples().size() + 1; missing <= rounds; missing++) {
                rows.add(new BenchmarkSample(group, workload, concurrency, repeat, completed.id(), missing, warmup,
                        false, false, null, null, null, null, null,
                        null, null, null, "NOT_COMPLETED: " + completed.error(), ""));
            }
        }
        if (!workers.terminated()) errors.add("worker 未退出，已异步关闭流；剩余请求标失败，后续实验中止");
        return new BatchExecution(new BenchmarkReport.Batch(group, workload, concurrency, repeat,
                Math.max(1e-9, (System.nanoTime() - start) / 1e9), List.copyOf(rows)), errors);
    }

    private static void session(BenchmarkOptions o, Connection c, String group, String workload,
                                int concurrency, int repeat, int rounds, boolean warmup, BenchmarkWorkers.Job job) {
        String session = job.id();
        String agentId = session + "-agent";
        var current = new AtomicReference<Observation>();
        ReActAgent agent = null;
        Model model = null;
        Exception initialization = null;
        try {
            try {
                if ("agent".equals(workload)) agent = agent(o, c, group, agentId, current);
                else model = new Model(client(o, c), request(o, c));
            } catch (Exception error) {
                initialization = error;
            }
            for (int round = 1; round <= rounds; round++) {
                if (job.cancelled() || Thread.currentThread().isInterrupted()) break;
                var obs = new Observation();
                current.set(obs);
                long start = System.nanoTime();
                try {
                    if (initialization != null) throw initialization;
                    if ("agent".equals(workload)) agentRound(agent, session, round, obs, start, job);
                    else modelRound(model, o, c, group, session, obs, start, job);
                    obs.success = true;
                } catch (Exception error) {
                    obs.error = sanitized(error, c);
                }
                double elapsed = ms(start);
                boolean correct = obs.success && obs.answer.contains("C1001") && obs.answer.contains("735")
                        && (!"agent".equals(workload) || obs.toolCalls.get() == 1);
                if (obs.success && !correct) obs.error = "业务校验未通过：应包含 C1001/735，Agent 每轮应恰好调用一次工具";
                job.add(new BenchmarkSample(group, workload, concurrency, repeat, session, round, warmup,
                        obs.success, correct, elapsed, obs.firstContentMs, obs.modelCalls.get(), obs.toolCalls.get(),
                        obs.modelMs.sum(), obs.usage.input(), obs.usage.output(), obs.usage.cache(), obs.error, obs.answer));
            }
        } finally {
            if (agent != null) {
                try {
                    Runner.resourceMgr().removeTool(TOOL, agentId, TagMatchStrategy.ALL, true);
                    Runner.release(session);
                } catch (Exception error) {
                    job.failure("会话清理失败: " + sanitized(error, c));
                }
            }
        }
    }

    private static void modelRound(Model model, BenchmarkOptions o, Connection c, String group, String session,
                                   Observation obs, long start, BenchmarkWorkers.Job job) throws Exception {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("stream_options", Map.of("include_usage", true));
        if ("compare".equals(o.mode())) {
            extra.put("session_id", session);
            extra.put("enable_cache_sharing", "on".equals(group));
        }
        Iterator<AssistantMessageChunk> stream = null;
        var answer = new StringBuilder();
        String finish = null;
        obs.modelCalls.incrementAndGet();
        try {
            stream = model.stream(List.of(new SystemMessage(SYSTEM), new UserMessage(
                    "演示档案：客户 C1001，信用评分 735。明细：" + payload(o.payloadChars())
                            + "\n请原样输出客户编号和评分。")), null, 0f, null, c.model(), o.maxTokens(),
                    null, null, (float) o.timeoutSeconds(), extra);
            job.stream(stream);
            while (stream.hasNext()) {
                var chunk = stream.next();
                if (chunk.getContent() instanceof String text && !text.isEmpty()) {
                    if (obs.firstContentMs == null) obs.firstContentMs = ms(start);
                    answer.append(text);
                }
                obs.usage.observe(chunk.getUsageMetadata());
                if (chunk.getFinishReason() != null && !"null".equals(chunk.getFinishReason())) finish = chunk.getFinishReason();
            }
            obs.answer = answer.toString();
            obs.usage.completeCall();
            if (!completeAnswer(finish, obs.answer)) {
                throw new IllegalStateException("模型流未正常结束或回答为空，finish_reason=" + finish);
            }
        } finally {
            obs.modelMs.add(ms(start));
            if (stream instanceof AutoCloseable closeable) closeable.close();
            job.stream(null);
        }
    }

    private static void agentRound(ReActAgent agent, String session, int round, Observation obs, long start, BenchmarkWorkers.Job job)
            throws Exception {
        Iterator<Object> stream = null;
        boolean answered = false;
        try {
            stream = Runner.runAgentStreaming(agent, Map.of("query", "第" + round + "轮：请重新调用 " + TOOL
                            + " 查询 C1001，仅回答客户编号和信用评分，不得复用历史查询。", "conversation_id", session),
                    null, null, List.of(StreamMode.OUTPUT));
            job.stream(stream);
            while (stream.hasNext()) {
                Object frame = stream.next();
                if (!(frame instanceof OutputSchema output) || !(output.getPayload() instanceof Map<?, ?> data)) continue;
                if ("error".equals(output.getType())) throw new IllegalStateException("Agent error: " + data);
                if ("llm_output".equals(output.getType()) && data.get("content") instanceof String text && !text.isEmpty()
                        && obs.firstContentMs == null) obs.firstContentMs = ms(start);
                if ("answer".equals(output.getType())) {
                    if (!"answer".equals(data.get("result_type"))) {
                        throw new IllegalStateException("Agent 未正常结束: " + data.get("result_type"));
                    }
                    obs.answer = data.get("output") == null ? "" : String.valueOf(data.get("output"));
                    answered = !obs.answer.isBlank();
                    if (answered && obs.firstContentMs == null) obs.firstContentMs = ms(start);
                }
            }
            if (!answered || !completeAnswer(obs.finalFinishReason, obs.answer)) {
                throw new IllegalStateException("Agent 流未返回完整 answer，finish_reason=" + obs.finalFinishReason);
            }
        } finally {
            if (stream instanceof AutoCloseable closeable) closeable.close();
            job.stream(null);
        }
    }

    private static ReActAgent agent(BenchmarkOptions o, Connection c, String group, String id,
                                    AtomicReference<Observation> current) {
        var context = ContextEngineConfig.builder().maxContextMessageNum(Math.max(200, o.rounds() * 8 + 20))
                .defaultWindowRoundNum(o.rounds() + 2).enableReload(false).enableKvCacheRelease("on".equals(group)).build();
        var compression = MicroCompactProcessorConfig.builder().triggerThreshold(1).keepRecentPerTool(1)
                .compactableToolNames(List.of(TOOL)).build();
        var config = ReActAgentConfig.builder().modelName(c.model()).modelClientConfig(client(o, c))
                .modelConfigObj(request(o, c)).promptTemplate(List.of(Map.of("role", "system", "content", AGENT_SYSTEM)))
                .maxIterations(3).maxParallelToolCalls(1).streamMaxRetries(0).build()
                .configureContextProcessors(List.of(new ContextEngine.ProcessorSpec("MicroCompactProcessor", compression)));
        config.setContextEngineConfig(context);
        var agent = new ReActAgent(AgentCard.builder().id(id).name(id).description("信贷亲和 benchmark").build());
        agent.configure(config);
        var tool = new LocalFunction(ToolCard.builder().id(TOOL).name(TOOL)
                .description("重新读取客户 C1001 的演示征信档案")
                .inputParams(Map.of("type", "object", "properties", Map.of(
                        "customer_id", Map.of("type", "string")), "required", List.of("customer_id"))).build(), inputs -> {
            if (!"C1001".equals(inputs.get("customer_id"))) throw new IllegalArgumentException("仅有 C1001 演示数据");
            return Map.of("customer_id", "C1001", "credit_score", 735,
                    "details", payload(o.payloadChars()), "source", "固定演示数据");
        });
        Runner.resourceMgr().addTool(tool, id);
        agent.getAbilityManager().add(tool.getCard());
        agent.registerCallback(AgentCallbackEvent.BEFORE_MODEL_CALL, ctx -> {
            current.get().modelCalls.incrementAndGet();
            current.get().modelStart.set(System.nanoTime());
        }, 10);
        agent.registerCallback(AgentCallbackEvent.AFTER_MODEL_CALL, ctx -> {
            var obs = current.get();
            obs.modelMs.add(ms(obs.modelStart.get()));
            if (ctx.getInputs() instanceof ModelCallInputs inputs && inputs.getResponse() instanceof AssistantMessage message) {
                obs.usage.observe(message.getUsageMetadata());
                if (message.getToolCalls() == null || message.getToolCalls().isEmpty()) {
                    obs.finalFinishReason = message.getFinishReason();
                }
            }
            obs.usage.completeCall();
        }, 10);
        agent.registerCallback(AgentCallbackEvent.AFTER_TOOL_CALL, ctx -> current.get().toolCalls.incrementAndGet(), 10);
        return agent;
    }

    private static ModelClientConfig client(BenchmarkOptions o, Connection c) {
        return ModelClientConfig.builder().clientProvider("compare".equals(o.mode()) ? "InferenceAffinity" : "openai")
                .apiBase(c.apiBase()).apiKey(c.apiKey()).timeout(o.timeoutSeconds()).maxRetries(0).build();
    }

    private static ModelRequestConfig request(BenchmarkOptions o, Connection c) {
        var request = ModelRequestConfig.builder().modelName(c.model()).temperature(0.0).topP(1.0).maxTokens(o.maxTokens()).build();
        request.setExtraField("stream_options", Map.of("include_usage", true));
        return request;
    }

    private static Map<String, Object> manifest(BenchmarkOptions o, Connection c) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", UUID.randomUUID().toString());
        data.put("startedAt", Instant.now().toString());
        data.put("sdk", "com.openjiuwen:agent-core-java:0.1.16");
        data.put("mode", o.mode());
        data.put("provider", "compare".equals(o.mode()) ? "InferenceAffinity" : "openai");
        data.put("apiBase", c.apiBase());
        data.put("model", c.model());
        data.put("javaVersion", System.getProperty("java.version"));
        data.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        data.put("workload", o.workload());
        data.put("concurrency", o.concurrency());
        data.put("sessionsPerBatch", o.sessions());
        data.put("roundsPerSession", o.rounds());
        data.put("repeats", o.repeats());
        data.put("warmupRoundsPerSession", o.warmup());
        data.put("payloadChars", o.payloadChars());
        data.put("payloadSha256", sha256(payload(o.payloadChars())));
        data.put("systemPrompt", SYSTEM);
        data.put("systemPromptSha256", sha256(SYSTEM));
        data.put("agentSystemPrompt", AGENT_SYSTEM);
        data.put("agentSystemPromptSha256", sha256(AGENT_SYSTEM));
        data.put("temperature", 0);
        data.put("maxTokens", o.maxTokens());
        data.put("timeoutSeconds", o.timeoutSeconds());
        data.put("batchTimeoutSeconds", o.batchTimeoutSeconds());
        data.put("maxRetries", 0);
        data.put("streamMaxRetries", 0);
        data.put("cacheIsolation", o.cacheIsolation());
        data.put("cacheState", "uncontrolled; warmup is not a cold-cache experiment");
        data.put("serverMetricsCollected", false);
        data.put("affinityValidated", false);
        data.put("serverEngineVersion", "unknown");
        data.put("affinityPluginVersion", "unknown");
        data.put("ascendDevice", "unknown");
        return data;
    }

    private static String payload(int chars) {
        String unit = "演示银行流水：每月收入稳定，按期还款，无逾期。";
        return unit.repeat(chars / unit.length() + 1).substring(0, chars);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static String sanitized(Exception error, Connection c) {
        String text = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        if (!c.apiKey().isEmpty()) text = text.replace(c.apiKey(), "[REDACTED]");
        return text.length() <= 1000 ? text : text.substring(0, 1000);
    }

    private static double ms(long start) { return (System.nanoTime() - start) / 1e6; }
    static boolean completeAnswer(String finishReason, String answer) {
        return "stop".equals(finishReason) && answer != null && !answer.isBlank();
    }
    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static final class Observation {
        final BenchmarkUsage usage = new BenchmarkUsage();
        final AtomicInteger modelCalls = new AtomicInteger();
        final AtomicInteger toolCalls = new AtomicInteger();
        final AtomicLong modelStart = new AtomicLong();
        final DoubleAdder modelMs = new DoubleAdder();
        Double firstContentMs;
        boolean success;
        String error;
        String answer = "";
        volatile String finalFinishReason;
    }
}
