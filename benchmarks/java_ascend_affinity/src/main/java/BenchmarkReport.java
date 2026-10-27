import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Pure statistics and report output. No inference client or framework wrapper. */
final class BenchmarkReport {
    private static final ObjectMapper JSON = new ObjectMapper();

    record Batch(String group, String workload, int concurrency, int repeat, double wallSeconds,
                 List<BenchmarkSample> samples) { }

    record Summary(long requests, long succeeded, long correct, double wallSeconds, Double meanMs,
                   Double p50Ms, Double p95Ms, Double p99Ms, Double firstContentP50Ms,
                   double requestsPerSecond, Long modelCalls, Long toolCalls, Double modelMeanMs,
                   long usageKnown, Long inputTokens, Long outputTokens, Double outputTokensPerSecond,
                   long cacheKnown, Double cachedTokenRatio) { }

    record Key(String group, String workload, int concurrency) { }

    private BenchmarkReport() { }

    static Summary summarize(List<BenchmarkSample> samples, double wallSeconds) {
        if (!Double.isFinite(wallSeconds) || wallSeconds <= 0) {
            throw new IllegalArgumentException("测量窗口必须大于 0");
        }
        var measured = samples.stream().filter(s -> !s.warmup()).toList();
        var good = measured.stream().filter(BenchmarkSample::success).toList();
        var latencies = good.stream().map(BenchmarkSample::elapsedMs).sorted().toList();
        var first = good.stream().map(BenchmarkSample::firstContentMs).filter(v -> v != null).sorted().toList();
        long usageKnown = good.stream().filter(s -> s.inputTokens() != null && s.outputTokens() != null).count();
        boolean fullUsage = !good.isEmpty() && usageKnown == good.size();
        Long input = fullUsage ? good.stream().mapToLong(BenchmarkSample::inputTokens).sum() : null;
        Long output = fullUsage ? good.stream().mapToLong(BenchmarkSample::outputTokens).sum() : null;
        long cacheKnown = good.stream().filter(s -> s.cacheTokens() != null).count();
        Double cacheRatio = fullUsage && input > 0 && cacheKnown == good.size()
                ? good.stream().mapToLong(BenchmarkSample::cacheTokens).sum() / (double) input : null;
        return new Summary(measured.size(), good.size(), measured.stream().filter(BenchmarkSample::correct).count(),
                wallSeconds, latencies.isEmpty() ? null : latencies.stream().mapToDouble(Double::doubleValue).average().orElseThrow(),
                percentile(latencies, .50), latencies.size() < 20 ? null : percentile(latencies, .95),
                latencies.size() < 100 ? null : percentile(latencies, .99), percentile(first, .50),
                good.size() / wallSeconds, measured.stream().allMatch(s -> s.modelCalls() != null)
                        ? measured.stream().mapToLong(BenchmarkSample::modelCalls).sum() : null,
                measured.stream().allMatch(s -> s.toolCalls() != null)
                        ? measured.stream().mapToLong(BenchmarkSample::toolCalls).sum() : null,
                good.isEmpty() ? null : good.stream().mapToDouble(BenchmarkSample::modelMs).average().orElseThrow(),
                usageKnown, input, output, fullUsage ? output / wallSeconds : null, cacheKnown, cacheRatio);
    }

    private static Double percentile(List<Double> sorted, double fraction) {
        return sorted.isEmpty() ? null : sorted.get(Math.max(0, (int) Math.ceil(fraction * sorted.size()) - 1));
    }

    static Double improvement(Double baseline, Double enabled) {
        return baseline == null || enabled == null || baseline <= 0 ? null : (baseline - enabled) / baseline * 100;
    }

    static boolean passed(List<Batch> batches) {
        var samples = batches.stream().flatMap(b -> b.samples().stream()).filter(s -> !s.warmup()).toList();
        return !samples.isEmpty() && samples.stream().allMatch(s -> s.success() && s.correct());
    }

    static void write(Path output, Map<String, Object> manifest, List<Batch> batches, List<String> errors)
            throws IOException {
        Files.createDirectories(output);
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("run-manifest.json").toFile(), manifest);
        var grouped = batches.stream().collect(Collectors.groupingBy(
                b -> new Key(b.group(), b.workload(), b.concurrency()), LinkedHashMap::new, Collectors.toList()));
        Map<Key, Summary> summaries = new LinkedHashMap<>();
        grouped.forEach((key, group) -> summaries.put(key, summarize(group.stream()
                .flatMap(b -> b.samples().stream()).toList(), group.stream().mapToDouble(Batch::wallSeconds).sum())));
        List<Map<String, Object>> summaryRows = new ArrayList<>();
        summaries.forEach((key, value) -> summaryRows.add(Map.of("group", key.group(), "workload", key.workload(),
                "concurrency", key.concurrency(), "summary", value)));
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("summary.json").toFile(), summaryRows);
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("batches.json").toFile(), batches);

        StringBuilder csv = new StringBuilder("group,workload,concurrency,requests,succeeded,correct,wall_seconds,"
                + "mean_ms,p50_ms,p95_ms,p99_ms,first_content_p50_ms,successful_requests_per_second,model_calls,tool_calls,"
                + "model_ms_per_round,usage_known,input_tokens,output_tokens,output_tokens_per_second,cache_known,cached_token_ratio\n");
        summaries.forEach((key, s) -> csv.append(String.join(",", key.group(), key.workload(), "" + key.concurrency(),
                "" + s.requests(), "" + s.succeeded(), "" + s.correct(), number(s.wallSeconds()), number(s.meanMs()),
                number(s.p50Ms()), number(s.p95Ms()), number(s.p99Ms()), number(s.firstContentP50Ms()),
                number(s.requestsPerSecond()), nullable(s.modelCalls()), nullable(s.toolCalls()), number(s.modelMeanMs()),
                "" + s.usageKnown(), nullable(s.inputTokens()), nullable(s.outputTokens()), number(s.outputTokensPerSecond()),
                "" + s.cacheKnown(), number(s.cachedTokenRatio()))).append('\n'));
        Files.writeString(output.resolve("summary.csv"), csv);

        boolean normal = "normal".equals(manifest.get("mode"));
        boolean passed = errors.isEmpty() && passed(batches);
        long requests = summaries.values().stream().mapToLong(Summary::requests).sum();
        long succeeded = summaries.values().stream().mapToLong(Summary::succeeded).sum();
        long correct = summaries.values().stream().mapToLong(Summary::correct).sum();
        var md = new StringBuilder("# Java Agent Core benchmark 测试报告\n\n## 结论\n\n");
        md.append(passed ? "**调用及业务校验通过。**" : "**测试失败。**")
                .append(" 已执行批次的正式计划轮数 ").append(requests).append(" 次，成功 ").append(succeeded)
                .append(" 次，业务规则通过 ").append(correct).append(" 次。\n\n");
        md.append(normal ? "本次运行 normal 普通模型模式，发起真实模型请求。没有执行亲和开关 A/B，"
                + "昇腾算力亲和及加速效果**未验证**。性能结论：本次仅建立普通调用基线，无法判断亲和提升。\n\n"
                : "本次运行 compare，使用相同 InferenceAffinity 客户端采集开关 A/B。以下差异仅为客户端观察；"
                + "未接入服务端机制证据，昇腾算力亲和生效及因果收益**未验证**。\n\n");
        if (!normal) comparison(md, summaries, grouped);
        if (!errors.isEmpty()) {
            md.append("运行级错误：\n\n");
            errors.forEach(error -> md.append("- ").append(safe(error)).append('\n'));
            md.append('\n');
        }
        md.append("## 实验配置与解释范围\n\n");
        md.append("配置及版本见 [run-manifest.json](run-manifest.json)，逐请求见 [requests.jsonl](requests.jsonl)，"
                + "汇总见 [summary.csv](summary.csv)，每轮实验原始数据见 [batches.json](batches.json)。\n\n");
        md.append("- 固定 temperature=0、相同上下文/工具数据和 max_tokens；输出长度、实际模型及工具次数同时记录。\n")
                .append("- 闭环并发：每个会话顺序执行多轮；sessions 是每个组/负载/并发/重复的总会话数。\n")
                .append("- 预热不计入正式统计；预热使用独立会话，并非冷缓存实验。本实现不清空服务端缓存。\n")
                .append("- A/B 交替顺序；cache-isolation=").append(safe(manifest.get("cacheIsolation")))
                .append("。external 仅为运行方声明，benchmark 未自动验证隔离；未隔离时不能归因。\n")
                .append("- wall_seconds 为各重复的批次墙钟窗口之和，包含执行及会话清理；吞吐不使用各请求耗时之和。\n")
                .append("- 延迟仅统计成功请求；失败、超时和内容错误均保留。批次超时保留已完成记录，未完成/未开始轮次用 NOT_COMPLETED 标记并填 null，不伪造耗时或调用次数。中止后的未执行批次不计入统计。\n")
                .append("- P95 至少 20 个成功样本，P99 至少 100 个；N/A 为样本不足或指标不可用。\n")
                .append("- 分位数使用 nearest-rank；重复范围用于观察波动，不作统计显著性或生产容量结论。\n")
                .append("- model 负载每轮发送固定上下文，不将生成结果加入下一轮；agent 负载保留真实多轮历史并压缩旧工具内容。\n")
                .append("- 正确率是演示事实校验（C1001/735、Agent 每轮恰好一次工具调用），不是信贷决策质量评估。\n\n");

        md.append("## 客户端可采集统计\n\n")
                .append("| 组 | 负载 | 并发 | 请求/成功/正确 | 均值 ms | P50 ms | P95 ms | P99 ms | 首内容 P50 ms | 成功请求/s |\n")
                .append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        summaries.forEach((key, s) -> md.append("| ").append(key.group()).append(" | ").append(key.workload())
                .append(" | ").append(key.concurrency()).append(" | ").append(s.requests()).append('/')
                .append(s.succeeded()).append('/').append(s.correct()).append(" | ").append(number(s.meanMs()))
                .append(" | ").append(number(s.p50Ms())).append(" | ").append(number(s.p95Ms()))
                .append(" | ").append(number(s.p99Ms())).append(" | ").append(number(s.firstContentP50Ms()))
                .append(" | ").append(number(s.requestsPerSecond())).append(" |\n"));
        md.append("\n| 组/负载/并发 | 模型/工具调用 | 模型总耗时/成功轮 ms | 有效 usage/成功轮 | 输入/输出 token | 输出 token/s | 有效缓存样本/成功轮 | 缓存 token 比例 |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|\n");
        summaries.forEach((key, s) -> md.append("| ").append(key.group()).append('/').append(key.workload())
                .append('/').append(key.concurrency()).append(" | ").append(nullable(s.modelCalls())).append('/')
                .append(nullable(s.toolCalls())).append(" | ").append(number(s.modelMeanMs())).append(" | ")
                .append(s.usageKnown()).append('/').append(s.succeeded()).append(" | ")
                .append(nullable(s.inputTokens())).append('/').append(nullable(s.outputTokens())).append(" | ")
                .append(number(s.outputTokensPerSecond())).append(" | ").append(s.cacheKnown()).append('/')
                .append(s.succeeded()).append(" | ").append(number(s.cachedTokenRatio())).append(" |\n"));
        md.append("\n- model 的首内容是客户端首个非空 content 到达延迟；agent 的首内容可能在工具执行后才到达，不能当作引擎 TTFT。\n")
                .append("- SDK 0.1.16 将缺失 usage/cache 字段归零。仅接受正数的输入/输出计数；缓存零值标 ambiguous，不能认定零命中。\n")
                .append("- 流式 usage 只在每次模型调用结束取一次有效累计值；一个 Agent 轮次可包含多次模型调用。\n")
                .append("- 部分成功请求缺少有效 usage 时，不报告总 token 或 token/s；逐请求仍保留已有值和覆盖率。\n")
                .append("- 输出 token 按服务返回的 completion_tokens 口径，可能包含思考 token，不等于可见正文长度。\n")
                .append("- 缓存 token 比例仅在全部成功样本有可信缓存计数时计算；它不是设备 KV Cache 命中率。\n")
                .append("- 未从片段数量推算 token、ITL/TPOT，也未使用未填充的 SDK totalLatency/firstTokenTime。\n\n");

        md.append("## 服务端指标：本次未采集\n\n")
                .append("| 待验证指标 | 获取位置 | 用途 |\n|---|---|---|\n")
                .append("| Prefix/KV Cache 查询量、命中量 | 推理引擎 /metrics → Prometheus/Grafana | 证明缓存复用；按同一窗口 counter 增量计算 |\n")
                .append("| KV Cache 占用、峰值、空闲块 | 引擎缓存监控 | 判断缓存压力和并发容量 |\n")
                .append("| 释放请求/成功数、实际释放块/字节 | affinity 插件指标或日志 | 2xx 只能证明受理，释放量需服务端确认 |\n")
                .append("| 排队、Prefill、Decode、服务端 TTFT/TPOT | 引擎时延直方图/trace | 定位收益阶段，不能与客户端首内容混用 |\n")
                .append("| 运行/等待请求、抢占、重计算 | 引擎调度监控 | 判断高负载稳定性 |\n")
                .append("| NPU 利用率、HBM、功耗 | 昇腾设备 exporter/监控平台、npu-smi | 证明实际昇腾设备和资源效率 |\n")
                .append("| 预取、卸载、迁移 | 支持该能力的版本及调度器/插件监控 | 无能力或无指标时标不可用 |\n\n")
                .append("服务端指标名按实际引擎/插件版本核对；MindIE Motor 按其版本从 Coordinator Observability /metrics 获取。")
                .append("独占测试窗口或过滤测试流量，counter 重置需处理；非独占且不能过滤时，不作本测试归因。\n\n")
                .append("参考：[官方 KV 示例](https://github.com/openJiuwen-ai/agent-core-java/tree/v0.1.16/examples/kv-cache-demo)、")
                .append("[vLLM 指标](https://docs.vllm.ai/en/latest/usage/metrics/)。\n");
        Files.writeString(output.resolve("report.md"), md);
    }

    private static void comparison(StringBuilder md, Map<Key, Summary> summaries, Map<Key, List<Batch>> grouped) {
        md.append("### 客户端 A/B 观察\n\n")
                .append("| 负载/并发 | P50 延迟降低 % | 吞吐增加 % | 每重复 P50 降低 % 的范围 |\n")
                .append("|---|---:|---:|---:|\n");
        summaries.forEach((key, off) -> {
            if (!"off".equals(key.group())) return;
            var onKey = new Key("on", key.workload(), key.concurrency());
            var on = summaries.get(onKey);
            if (on == null) return;
            var changes = new ArrayList<Double>();
            for (var a : grouped.get(key)) {
                grouped.get(onKey).stream().filter(b -> b.repeat() == a.repeat()).findFirst().ifPresent(b -> {
                    var before = summarize(a.samples(), a.wallSeconds());
                    var after = summarize(b.samples(), b.wallSeconds());
                    Double value = improvement(before.p50Ms(), after.p50Ms());
                    if (value != null) changes.add(value);
                });
            }
            Double latency = improvement(off.p50Ms(), on.p50Ms());
            Double throughput = off.requestsPerSecond() <= 0 ? null
                    : (on.requestsPerSecond() - off.requestsPerSecond()) / off.requestsPerSecond() * 100;
            changes.sort(Comparator.naturalOrder());
            String range = changes.isEmpty() ? "N/A" : number(changes.get(0)) + " ~ " + number(changes.get(changes.size() - 1));
            md.append("| ").append(key.workload()).append('/').append(key.concurrency()).append(" | ")
                    .append(number(latency)).append(" | ").append(number(throughput)).append(" | ").append(range).append(" |\n");
        });
        md.append("\n正数表示改善，负数表示退化；输出 token、调用次数、正确率或成功率变化时，差异可能来自工作量变化。")
                .append("只有服务端证据、缓存隔离及重复实验共同满足，才可判断亲和提升。\n\n");
    }

    private static String nullable(Object value) { return value == null ? "N/A" : String.valueOf(value); }
    private static String safe(Object value) { return String.valueOf(value).replace('\n', ' ').replace('|', '/'); }
    private static String number(Double value) { return value == null ? "N/A" : String.format(Locale.ROOT, "%.3f", value); }
}
