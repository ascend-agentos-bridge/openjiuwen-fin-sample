import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.openjiuwen.core.runner.Runner;
import java.nio.file.*;
import java.io.*;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** CLI, raw observations and deterministic report orchestration. */
public final class AgentHintBenchmark {
 static final ObjectMapper JSON=new ObjectMapper();
 static volatile boolean interrupted;
 static String safe(Throwable e){Throwable c=e;while(c.getCause()!=null)c=c.getCause();return safe(c.getClass().getSimpleName()+": "+Objects.toString(c.getMessage(),""));}
 static String safe(String s){String key=System.getenv().getOrDefault("API_KEY","");return key.isEmpty()?s:s.replace(key,"[REDACTED]");}
 static void writeJson(Path p,Object value)throws IOException{Files.writeString(p,safe(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value)));}
 static final class Sink implements AutoCloseable {
  final Path dir;boolean closed;
  Sink(Path dir)throws IOException{this.dir=dir;for(String name:List.of("requests.jsonl","model-calls.jsonl","actions.jsonl","sdk.log"))Files.createFile(dir.resolve(name));}
  synchronized void line(String file,Object value){if(closed)return;try{Files.writeString(dir.resolve(file),safe(JSON.writeValueAsString(value))+"\n",StandardOpenOption.APPEND);}catch(IOException e){throw new UncheckedIOException(e);}}
  public synchronized void close(){closed=true;}
 }
 public static void main(String[] args){int code;
  try{if(args.length==1&&args[0].equals("--help")){System.out.println(BenchmarkOptions.help());return;}var o=BenchmarkOptions.parse(args,System.getenv());code=run(o);}
  catch(IllegalArgumentException e){System.err.println("配置错误: "+safe(e)+"；报告未生成。");code=2;}
  catch(Exception e){System.err.println("执行失败: "+safe(e)+"；请检查输出目录中是否有已保存的记录。");code=1;}
  if(code!=0)System.exit(code);
 }
 static Map<String,Object> manifest(BenchmarkOptions o)throws Exception{
  var m=new LinkedHashMap<String,Object>();m.put("runId",UUID.randomUUID().toString());m.put("startedAt",Instant.now().toString());m.put("sdk","com.openjiuwen:agent-core-java:0.1.17");m.put("javaVersion",System.getProperty("java.version"));m.put("os",System.getProperty("os.name")+"/"+System.getProperty("os.arch"));m.put("mode",o.str("mode"));m.put("profile",o.str("profile"));m.put("experimentId",o.str("experiment-id"));m.put("minChangePercent",o.number("min-change-percent"));m.put("cacheIsolation",o.str("cache-isolation"));m.put("serverEvidence",o.str("server-evidence").isEmpty()?"not_collected":"attached_not_verified");m.put("parameters",o.values);m.put("dataVersion",BenchmarkWorkload.DATA_VERSION);m.put("promptSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(BenchmarkWorkload.PROMPT.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
  var comparable=new TreeMap<String,Object>(o.values);for(String k:List.of("output","mode","group","experiment-id","repeat-index","repeats","server-evidence","cache-isolation"))comparable.remove(k);m.put("comparableParameters",comparable);m.put("serverComponents","unknown");m.put("cacheControlNote","声明不是隔离证明；自动compare不重启引擎、不清空缓存");return m;
 }
 static int run(BenchmarkOptions o)throws Exception{
  Path output=o.output();if(Files.exists(output))throw new IllegalArgumentException("output 已存在，请选择新目录");Files.createDirectories(output);
  if(o.str("mode").equals("report"))return merge(o,output);
  var manifest=manifest(o);List<BenchmarkMetrics.Batch>batches=new ArrayList<>();List<String> errors=new ArrayList<>();writeJson(output.resolve("run-manifest.json"),manifest);
  if(!o.str("server-evidence").isEmpty())Files.copy(Path.of(o.str("server-evidence")),output.resolve("server-evidence.txt"));
  Thread owner=Thread.currentThread();CountDownLatch finished=new CountDownLatch(1);
  Thread hook=new Thread(()->{interrupted=true;owner.interrupt();try{finished.await(8,TimeUnit.SECONDS);}catch(InterruptedException ignored){}},"benchmark-shutdown");Runtime.getRuntime().addShutdownHook(hook);
  Map<String,Object> conclusion;
  try(Sink sink=new Sink(output);var logs=new BenchmarkSdkLog(sink)){
   try{
    long calls=(long)o.integer("sessions")*o.integer("rounds")*o.integer("repeats")*o.concurrency().size()*(o.str("workload").equals("both")?2:1)*(o.str("mode").equals("compare")&&o.str("group").equals("both")?2:1)*3;
    System.out.println("模型: "+o.str("model-id")+"；计划正式模型调用上限（每轮3次）: "+calls+"；预热另计。代码规则总结，不调用AI解释报告。");
    var workloads=o.str("workload").equals("both")?List.of("continuous","idle-resume"):List.of(o.str("workload"));
    outer:for(String workload:workloads)for(int concurrency:o.concurrency())for(int r=0;r<o.integer("repeats");r++){
     int repeat=o.str("group").equals("both")?r+1:o.integer("repeat-index");
     var groups=o.str("mode").equals("baseline")?List.of("baseline"):!o.str("group").equals("both")?List.of(o.str("group")):repeat%2==1?List.of("off","on"):List.of("on","off");
     for(String group:groups){
      if(o.integer("warmup-rounds")>0){var warm=BenchmarkWorkload.batch(o,group,workload,concurrency,repeat,true,sink);batches.add(warm);if(failed(warm)){errors.add("预热失败，停止正式实验");break outer;}}
      var batch=BenchmarkWorkload.batch(o,group,workload,concurrency,repeat,false,sink);batches.add(batch);System.out.printf(Locale.ROOT,"%s/%s c=%d repeat=%d 通过=%d/%d wall=%.3fs%n",group,workload,concurrency,repeat,batch.rows().stream().filter(x->x.success()&&x.correct()).count(),batch.rows().size(),batch.wallSeconds());
      // Persist every completed batch so even hard termination retains observations.
      writeJson(output.resolve("batches.json"),batches);
      if(failed(batch)){errors.add("批次失败，停止后续实验");break outer;}
     }
    }
   }catch(Exception e){errors.add(safe(e));}finally{
    boolean wasInterrupted=Thread.interrupted();
    try{ExecutorService stop=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"runner-cleanup");t.setDaemon(true);return t;});try{if(!stop.submit(()->Runner.stop().toCompletableFuture().get(5,TimeUnit.SECONDS)).get(6,TimeUnit.SECONDS))errors.add("Runner.stop返回false");}finally{stop.shutdownNow();}}catch(Exception e){errors.add("Runner清理失败: "+safe(e));}
    if(wasInterrupted||interrupted)errors.add("INTERRUPTED");manifest.put("finishedAt",Instant.now().toString());
   }
   conclusion=BenchmarkReport.write(output,manifest,batches,errors);
   print(output,conclusion);
  }finally{finished.countDown();try{Runtime.getRuntime().removeShutdownHook(hook);}catch(IllegalStateException ignored){}}
  return "INVALID".equals(conclusion.get("performance_status"))?1:0;
 }
 static boolean failed(BenchmarkMetrics.Batch b){return !b.errors().isEmpty()||b.rows().stream().anyMatch(x->!x.success()||!x.correct());}
 static int merge(BenchmarkOptions o,Path output)throws Exception{
  List<BenchmarkMetrics.Batch>batches=new ArrayList<>();List<String>errors=new ArrayList<>();List<Map<String,Object>> sources=new ArrayList<>();Set<String> keys=new HashSet<>();Map<String,Object> first=null;
  for(Path input:o.inputs){Path dir=input.toAbsolutePath().normalize();var m=JSON.readValue(dir.resolve("run-manifest.json").toFile(),new TypeReference<LinkedHashMap<String,Object>>(){});
   if(!"compare".equals(m.get("mode")))throw new IllegalArgumentException("合并只接受compare原始单组报告");
   if(first==null)first=m;else for(String key:List.of("experimentId","sdk","dataVersion","promptSha256","comparableParameters","cacheIsolation"))if(!Objects.equals(first.get(key),m.get(key)))throw new IllegalArgumentException("不能合并：manifest不一致 "+key);
   if(m.get("errors") instanceof List<?> e)for(Object x:e)errors.add(String.valueOf(x));
   var bs=JSON.readValue(dir.resolve("batches.json").toFile(),new TypeReference<List<BenchmarkMetrics.Batch>>(){});
   for(var b:bs){String key=b.group()+"/"+b.workload()+"/"+b.concurrency()+"/"+b.repeat()+"/"+b.warmup();if(!keys.add(key))throw new IllegalArgumentException("重复组/负载/并发/重复编号，拒绝重复计数");batches.add(b);}sources.add(Map.of("directory",dir.toString(),"runId",m.get("runId")));
  }
  Set<String> off=new TreeSet<>(),on=new TreeSet<>();for(var b:batches){String key=b.workload()+"/"+b.concurrency()+"/"+b.repeat()+"/"+b.warmup();if(b.group().equals("off"))off.add(key);else if(b.group().equals("on"))on.add(key);else throw new IllegalArgumentException("单组必须为off或on");}if(!off.equals(on)||off.isEmpty())throw new IllegalArgumentException("off/on配对不完整");
  var m=new LinkedHashMap<>(first);m.put("runId",UUID.randomUUID().toString());m.put("sources",sources);m.put("finishedAt",Instant.now().toString());m.put("serverEvidence","not_verified");
  // Re-export raw data; inputs remain immutable.
  try(Sink sink=new Sink(output)){for(Path input:o.inputs)for(String file:List.of("requests.jsonl","model-calls.jsonl","actions.jsonl","sdk.log")){Path source=input.resolve(file);if(Files.exists(source))Files.writeString(output.resolve(file),Files.readString(source),StandardOpenOption.APPEND);}}
  var c=BenchmarkReport.write(output,m,batches,errors);print(output,c);return "INVALID".equals(c.get("performance_status"))?1:0;
 }
 static void print(Path output,Map<String,Object> c){
  System.out.println("执行状态: "+("INVALID".equals(c.get("performance_status"))?"失败":"完成"));System.out.println("测试范围: "+c.get("test_scope"));System.out.println("测试结论: "+c.get("performance_status")+" — "+c.get("conclusion"));
  System.out.println("分组结果: "+safe(String.valueOf(c.get("comparisons"))));System.out.println("证据说明: "+c.get("evidence_note"));System.out.println("测试报告: "+output.resolve("report.md"));System.out.println("结构化结论: "+output.resolve("conclusion.json"));System.out.println("原始记录目录: "+output);
 }
}
