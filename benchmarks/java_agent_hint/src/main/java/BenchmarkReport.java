import com.fasterxml.jackson.core.type.TypeReference;
import java.nio.file.*;
import java.util.*;
import static java.util.stream.Collectors.*;

/** Pure arithmetic and templates: never asks a model to interpret measurements. */
final class BenchmarkReport {
 static final String RULE_VERSION="1.0";
 static Double percentile(List<Double> samples,double p){if(samples.isEmpty())return null;var s=new ArrayList<>(samples);Collections.sort(s);return s.get(Math.max(0,(int)Math.ceil(p*s.size())-1));}
 static Double change(Double off,Double on,boolean latency){if(off==null||on==null||off<=0||!Double.isFinite(off)||!Double.isFinite(on))return null;return (latency?off-on:on-off)/off*100;}
 static String metric(Double delta,List<Double> repeats,double threshold){
  if(delta==null||repeats.size()<5||repeats.stream().anyMatch(Objects::isNull))return "UNKNOWN";
  long pos=repeats.stream().filter(x->x>0).count(),neg=repeats.stream().filter(x->x<0).count(),flat=repeats.stream().filter(x->Math.abs(x)<threshold).count();
  if(delta>=threshold&&pos>=Math.ceil(.8*repeats.size()))return "IMPROVED";
  if(delta<=-threshold&&neg>=Math.ceil(.8*repeats.size()))return "REGRESSED";
  if(Math.abs(delta)<threshold&&flat>=Math.ceil(.8*repeats.size()))return "UNCHANGED";
  return "UNSTABLE";
 }
 static String combine(List<String> metrics){if(metrics.contains("IMPROVED")&&metrics.contains("REGRESSED"))return "TRADEOFF";if(metrics.contains("UNKNOWN")||metrics.contains("UNSTABLE"))return "INCONCLUSIVE";if(metrics.contains("REGRESSED"))return "OBSERVED_REGRESSION";if(metrics.contains("IMPROVED"))return "OBSERVED_IMPROVEMENT";return "NO_MATERIAL_CHANGE";}
 static String label(String s){return switch(s){case "INVALID"->"实验无效：存在运行或业务校验失败";case "BASELINE_ONLY"->"仅建立真实模型基线，未进行 AgentHint 对比";case "INCOMPLETE_COMPARISON"->"单组完成，待配对合并";case "INSUFFICIENT_DATA"->"样本或重复不足，不作稳定性能判断";case "TRADEOFF"->"不同指标存在改善与退化的取舍";case "INCONCLUSIVE"->"差异波动或指标缺失，无法稳定判断";case "OBSERVED_IMPROVEMENT"->"客户端观察到改善";case "OBSERVED_REGRESSION"->"客户端观察到退化";case "NO_MATERIAL_CHANGE"->"未观察到达到阈值的稳定变化";default->s;};}
 static List<Map<String,Object>> summary(List<BenchmarkMetrics.Batch> batches){
  var groups=batches.stream().filter(b->!b.warmup()).collect(groupingBy(b->b.group()+"/"+b.workload()+"/"+b.concurrency(),TreeMap::new,toList()));
  var out=new ArrayList<Map<String,Object>>();
  for(var entry:groups.entrySet()){
   var bs=entry.getValue();var rows=bs.stream().flatMap(b->b.rows().stream()).toList();
   for(String type:List.of("all","first","subsequent","resumed")){
    var selected=rows.stream().filter(r->type.equals("all")||r.roundType().equals(type)).toList();if(selected.isEmpty())continue;
    var ok=selected.stream().filter(r->r.success()&&r.correct()).toList();var times=ok.stream().map(BenchmarkMetrics.Row::elapsedMs).filter(Objects::nonNull).toList();
    var m=new LinkedHashMap<String,Object>();var b=bs.get(0);m.put("group",b.group());m.put("workload",b.workload());m.put("concurrency",b.concurrency());m.put("roundType",type);m.put("planned",selected.size());m.put("success",selected.stream().filter(BenchmarkMetrics.Row::success).count());m.put("correct",ok.size());m.put("correctRate",ok.size()/(double)selected.size());m.put("repeats",bs.stream().map(BenchmarkMetrics.Batch::repeat).distinct().count());
    m.put("meanMs",times.stream().mapToDouble(Double::doubleValue).average().isPresent()?times.stream().mapToDouble(Double::doubleValue).average().getAsDouble():null);m.put("p50Ms",percentile(times,.5));m.put("p95Ms",times.size()>=20?percentile(times,.95):null);m.put("p99Ms",times.size()>=100?percentile(times,.99):null);
    var first=ok.stream().map(BenchmarkMetrics.Row::firstContentMs).filter(Objects::nonNull).toList();m.put("firstContentP50Ms",percentile(first,.5));m.put("firstContentCoverage",first.size());
    double wall=bs.stream().mapToDouble(BenchmarkMetrics.Batch::wallSeconds).sum();m.put("wallSeconds",wall);m.put("throughput",type.equals("all")&&wall>0?ok.size()/wall:null);m.put("scheduledIdleSeconds",bs.stream().mapToDouble(BenchmarkMetrics.Batch::scheduledIdleSeconds).sum());
    m.put("modelCalls",selected.stream().mapToInt(BenchmarkMetrics.Row::modelCalls).sum());m.put("toolCalls",selected.stream().mapToInt(BenchmarkMetrics.Row::toolCalls).sum());
    long known=ok.stream().filter(r->r.inputTokens()!=null&&r.outputTokens()!=null).count();m.put("usageKnownRounds",known);m.put("usageCoverage",ok.isEmpty()?0.:known/(double)ok.size());m.put("inputTokens",!ok.isEmpty()&&known==ok.size()?ok.stream().mapToLong(r->r.inputTokens()).sum():null);m.put("outputTokens",!ok.isEmpty()&&known==ok.size()?ok.stream().mapToLong(r->r.outputTokens()).sum():null);m.put("cacheTokens",null);out.add(m);
   }
  }
  return out;
 }
 static Map<String,Object> conclusion(Map<String,Object> manifest,List<BenchmarkMetrics.Batch> batches,List<String> errors,List<Map<String,Object>> summary){
  var c=new LinkedHashMap<String,Object>();c.put("rule_version",RULE_VERSION);double threshold=((Number)manifest.get("minChangePercent")).doubleValue();c.put("thresholdPercent",threshold);c.put("requiredRepeatConsistency",.8);c.put("evidence_status","NOT_VERIFIED");c.put("cacheIsolation",manifest.get("cacheIsolation"));
  c.put("evidence_note","不能据此确认 AgentHint 实际执行或归因于 AgentHint；外部附件不代表程序已验证真实性。");
  List<Map<String,Object>> comparisons=new ArrayList<>();String state;
  boolean invalid=!errors.isEmpty()||batches.isEmpty()||batches.stream().anyMatch(b->!b.errors().isEmpty()||b.rows().isEmpty()||b.rows().stream().anyMatch(r->!r.success()||!r.correct()));
  if(invalid)state="INVALID";
  else if("baseline".equals(manifest.get("mode")))state="BASELINE_ONLY";
  else {
   Set<String> groups=batches.stream().filter(b->!b.warmup()).map(BenchmarkMetrics.Batch::group).collect(toSet());
   if(!groups.equals(Set.of("off","on")))state="INCOMPLETE_COMPARISON";
   else {
    for(var off:summary){if(!"off".equals(off.get("group")))continue;
     var on=summary.stream().filter(x->"on".equals(x.get("group"))&&Objects.equals(x.get("workload"),off.get("workload"))&&Objects.equals(x.get("concurrency"),off.get("concurrency"))&&Objects.equals(x.get("roundType"),off.get("roundType"))).findFirst().orElse(null);
     var item=new LinkedHashMap<String,Object>();item.put("workload",off.get("workload"));item.put("concurrency",off.get("concurrency"));item.put("roundType",off.get("roundType"));
     boolean enough=on!=null&&!"smoke".equals(manifest.get("profile"))&&((Number)off.get("correct")).intValue()>=20&&((Number)on.get("correct")).intValue()>=20&&((Number)off.get("repeats")).intValue()>=5&&((Number)on.get("repeats")).intValue()>=5;
     if(!enough){item.put("status","INSUFFICIENT_DATA");item.put("reason_codes",List.of("MIN_20_ROUNDS_AND_5_PAIRED_REPEATS"));}
     else {
      List<String> states=new ArrayList<>();
      for(String key:List.of("p50Ms","p95Ms","throughput")){
       if(key.equals("throughput")&&!"all".equals(off.get("roundType")))continue;
       Double d=change((Double)off.get(key),(Double)on.get(key),!key.equals("throughput"));List<Double> paired=new ArrayList<>();
       var formal=batches.stream().filter(b->!b.warmup()&&b.workload().equals(off.get("workload"))&&b.concurrency()==((Number)off.get("concurrency")).intValue()).toList();
       Set<Integer> repeats=formal.stream().map(BenchmarkMetrics.Batch::repeat).collect(toCollection(TreeSet::new));
       for(int rep:repeats){var per=summary(formal.stream().filter(b->b.repeat()==rep).toList());var a=per.stream().filter(x->x.get("group").equals("off")&&x.get("roundType").equals(off.get("roundType"))).findFirst().orElse(null);var b=per.stream().filter(x->x.get("group").equals("on")&&x.get("roundType").equals(off.get("roundType"))).findFirst().orElse(null);paired.add(a==null||b==null?null:change((Double)a.get(key),(Double)b.get(key),!key.equals("throughput")));}
       // P95 remains a warning when too few samples within a repeat to assess consistency.
       String status=metric(d,paired,threshold);item.put(key+"ChangePercent",d);item.put(key+"RepeatChanges",paired);item.put(key+"Status",status);if(!key.equals("p95Ms")||!status.equals("UNKNOWN"))states.add(status);else if(d!=null&&d<=-threshold)states.add("UNSTABLE");
      }
      item.put("status",combine(states));item.put("reason_codes",List.of("HEURISTIC_NOT_SIGNIFICANCE_TEST"));
     }
     comparisons.add(item);
    }
    var states=comparisons.stream().map(x->String.valueOf(x.get("status"))).toList();
    if(states.isEmpty()||states.contains("INSUFFICIENT_DATA"))state="INSUFFICIENT_DATA";
    else if(states.contains("TRADEOFF")||states.contains("OBSERVED_IMPROVEMENT")&&states.contains("OBSERVED_REGRESSION"))state="TRADEOFF";
    else if(states.contains("INCONCLUSIVE"))state="INCONCLUSIVE";
    else if(states.contains("OBSERVED_REGRESSION"))state="OBSERVED_REGRESSION";
    else if(states.contains("OBSERVED_IMPROVEMENT"))state="OBSERVED_IMPROVEMENT";else state="NO_MATERIAL_CHANGE";
   }
  }
  c.put("test_scope","baseline".equals(manifest.get("mode"))?"仅基线":state.equals("INCOMPLETE_COMPARISON")?"单组待合并":"off-on对比");c.put("performance_status",state);c.put("conclusion",label(state));c.put("reason_codes",errors.isEmpty()?List.of(state):List.copyOf(errors));c.put("comparisons",comparisons);return c;
 }
 static Map<String,Object> write(Path output,Map<String,Object> manifest,List<BenchmarkMetrics.Batch> batches,List<String> errors) throws Exception {
  manifest.put("errors",errors);var sums=summary(batches);var c=conclusion(manifest,batches,errors,sums);
  AgentHintBenchmark.writeJson(output.resolve("run-manifest.json"),manifest);AgentHintBenchmark.writeJson(output.resolve("batches.json"),batches);AgentHintBenchmark.writeJson(output.resolve("summary.json"),sums);AgentHintBenchmark.writeJson(output.resolve("conclusion.json"),c);
  StringBuilder csv=new StringBuilder("group,workload,concurrency,roundType,planned,success,correct,p50Ms,p95Ms,p99Ms,throughput,usageCoverage\n");for(var s:sums){for(String key:List.of("group","workload","concurrency","roundType","planned","success","correct","p50Ms","p95Ms","p99Ms","throughput","usageCoverage")){if(!key.equals("group"))csv.append(',');csv.append(s.get(key)==null?"":s.get(key));}csv.append('\n');}Files.writeString(output.resolve("summary.csv"),csv);
  StringBuilder md=new StringBuilder("# AgentHint benchmark 报告\n\n结论：**"+c.get("performance_status")+" — "+c.get("conclusion")+"**\n\n"+c.get("evidence_note")+"\n\n规则版本："+RULE_VERSION+"；变化阈值："+manifest.get("minChangePercent")+"%；重复同向比例至少80%，不是统计显著性检验。\n\n");
  md.append("|组别|负载|并发|轮次类型|计划/调用成功/业务通过|P50 ms|P95 ms|P99 ms|有效轮/s|\n|---|---|---:|---|---|---:|---:|---:|---:|\n");for(var s:sums)md.append("|").append(s.get("group")).append("|").append(s.get("workload")).append("|").append(s.get("concurrency")).append("|").append(s.get("roundType")).append("|").append(s.get("planned")).append("/").append(s.get("success")).append("/").append(s.get("correct")).append("|").append(fmt(s.get("p50Ms"))).append("|").append(fmt(s.get("p95Ms"))).append("|").append(fmt(s.get("p99Ms"))).append("|").append(fmt(s.get("throughput"))).append("|\n");
  md.append("\n## 分组结论与重复波动\n\n```json\n").append(AgentHintBenchmark.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(c.get("comparisons"))).append("\n```\n\n");
  md.append("## 阅读方法\n\n先核对 success/correct 与 errors，再看配置和缓存隔离，最后比较同负载/并发/轮次类型的延迟和波动。恢复首轮须单独解读，不能混入普通轮次。延迟降低率=(off-on)/off，吞吐增加率=(on-off)/off；正数改善。失败样本不计延迟分位数，但保留在成功率分母。\n\n业务轮耗时包含客户端、网络、模型与工具，不含设定等待；首正文不是引擎 TTFT。有效吞吐=业务通过轮数/完整批次窗口，包含等待与清理；闭环 worker 并发不是固定到达率。P95少于20样本、P99少于100样本均为N/A，达到门槛仍不保证估计稳定。\n\n## 可观测边界\n\nrequests.jsonl: 逐轮耗时、首正文、真实回答、调用成功和事实校验。model-calls.jsonl: 官方callback的模型调用耗时、最终累计usage（不逐chunk累加）。actions.jsonl: 显式suspend/release的返回和耗时。sdk.log: SDK的Hint构造和警告，构造不等于HTTP完成或服务端执行。自动prefetch精确执行量为unknown。\n\nusageCoverage见summary.json；只有全部业务成功轮的usage都可信时才提供整组token总数。SDK缺失数值可归零，流式cached_tokens未解析，因此缓存token/比例为N/A；不能用字符数代替token。\n\nKV实际迁移量、缓存命中、排队/prefill/decode、NPU/HBM需兼容服务日志、引擎/metrics和设备exporter取证。外部证据只标附件未验证；本报告不会自动证明AgentHint执行。\n\n## 环境与错误\n\n完整参数、业务提示摘要、SDK/JDK/模型及缓存声明见run-manifest.json；逐重复墙钟分母见batches.json。\n\n```json\n").append(AgentHintBenchmark.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(errors)).append("\n```\n");Files.writeString(output.resolve("report.md"),md);
  return c;
 }
 static String fmt(Object n){return n instanceof Number x?String.format(Locale.ROOT,"%.3f",x.doubleValue()):"N/A";}
}
