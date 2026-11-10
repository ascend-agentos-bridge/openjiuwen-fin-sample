import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

/** Strict non-secret benchmark configuration. API_KEY is never stored here. */
final class BenchmarkOptions {
 static final Set<String> KEYS=Set.of("api-base","model-id","mode","profile","workload","concurrency","sessions","rounds","repeats","warmup-rounds","idle-seconds","payload-chars","max-tokens","request-timeout-seconds","batch-timeout-seconds","min-change-percent","output","group","experiment-id","repeat-index","cache-isolation","server-evidence");
 final Map<String,Object> values;
 final List<Path> inputs;
 private BenchmarkOptions(Map<String,Object> v,List<Path> i){values=Collections.unmodifiableMap(v);inputs=List.copyOf(i);}
 String str(String key){return String.valueOf(values.get(key));}
 int integer(String key){try{return Integer.parseInt(str(key));}catch(Exception e){throw new IllegalArgumentException(key+" 必须为整数");}}
 double number(String key){try{double v=Double.parseDouble(str(key));if(!Double.isFinite(v))throw new Exception();return v;}catch(Exception e){throw new IllegalArgumentException(key+" 必须为有限数值");}}
 List<Integer> concurrency(){try{return Arrays.stream(str("concurrency").split(",",-1)).map(Integer::parseInt).toList();}catch(Exception e){throw new IllegalArgumentException("concurrency 必须为逗号分隔的整数");}}
 Path output(){return Path.of(str("output")).toAbsolutePath().normalize();}
 static BenchmarkOptions parse(String[] args,Map<String,String> env){
  Map<String,Object> cli=new LinkedHashMap<>(),file=new LinkedHashMap<>(); List<Path> inputs=new ArrayList<>();String config=null;
  for(int j=0;j<args.length;j++){
   String a=args[j];if(!a.startsWith("--")||j+1==args.length)throw new IllegalArgumentException("参数需要 --名称 值，使用 --help 查看");
   String key=a.substring(2),v=args[++j];
   if(key.equals("config")){if(config!=null)throw new IllegalArgumentException("config 不可重复");config=v;}
   else if(key.equals("inputs"))inputs.add(Path.of(v));
   else {if(!KEYS.contains(key))throw new IllegalArgumentException("未知参数: "+key);if(cli.putIfAbsent(key,v)!=null)throw new IllegalArgumentException("参数不可重复: "+key);}
  }
  if(config!=null)try{file=new ObjectMapper().readValue(Path.of(config).toFile(),new TypeReference<LinkedHashMap<String,Object>>(){});for(var e:file.entrySet())if(!KEYS.contains(e.getKey())||e.getValue()==null||!(e.getValue() instanceof String||e.getValue() instanceof Number))throw new IllegalArgumentException("未知或无效配置字段: "+e.getKey());}catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("不能读取 JSON 配置文件");}
  Map<String,Object> v=new LinkedHashMap<>();
  v.putAll(Map.of("mode","compare","profile","smoke","workload","both","concurrency","1","warmup-rounds",1,"idle-seconds",5,"payload-chars",8192,"max-tokens",512,"request-timeout-seconds",60,"batch-timeout-seconds",1800));
  v.putAll(Map.of("min-change-percent",5,"output",Path.of(System.getProperty("agent.hint.benchmark.dir", "."),"reports", "run-"+java.time.Instant.now().toString().replace(':','-')+"-"+UUID.randomUUID().toString().substring(0,8)).toString(),"group","both","experiment-id",UUID.randomUUID().toString(),"repeat-index",1,"cache-isolation","uncontrolled","server-evidence",""));
  if(env.containsKey("API_BASE"))v.put("api-base",env.get("API_BASE"));if(env.containsKey("MODEL_NAME"))v.put("model-id",env.get("MODEL_NAME"));
  v.putAll(file);v.putAll(cli);
  boolean perf="performance".equals(v.get("profile"));
  for(var e:Map.of("sessions",perf?8:1,"rounds",perf?4:2,"repeats",perf?5:1).entrySet())v.putIfAbsent(e.getKey(),e.getValue());
  var o=new BenchmarkOptions(v,inputs);
  require(Set.of("baseline","compare","report").contains(o.str("mode")),"mode 无效");
  require(Set.of("smoke","performance").contains(o.str("profile")),"profile 无效");
  require(Set.of("both","continuous","idle-resume").contains(o.str("workload")),"workload 无效");
  require(Set.of("both","off","on").contains(o.str("group")),"group 无效");
  require(Set.of("uncontrolled","external").contains(o.str("cache-isolation")),"cache-isolation 无效");
  for(String k:List.of("sessions","rounds","repeats","payload-chars","max-tokens","request-timeout-seconds","batch-timeout-seconds","repeat-index"))require(o.integer(k)>0,k+" 必须大于零");
  require(o.integer("warmup-rounds")>=0,"warmup-rounds 不可为负");
  require(o.number("idle-seconds")>=0,"idle-seconds 不可为负");
  require(o.number("min-change-percent")>0&&o.number("min-change-percent")<100,"min-change-percent 必须在 0 和 100 之间");
  require(o.concurrency().stream().allMatch(c->c>0&&c<=o.integer("sessions"))&&new HashSet<>(o.concurrency()).size()==o.concurrency().size(),"concurrency 必须为不重复正数且不大于 sessions");
  if(o.str("mode").equals("report")){require(inputs.size()>=2,"report 至少需要两个 inputs");}
  else {
   require(v.containsKey("api-base")&&v.containsKey("model-id")&&!o.str("model-id").isBlank(),"必须提供 api-base 与 model-id");
   try {URI u=URI.create(o.str("api-base"));require(Set.of("http","https").contains(u.getScheme())&&u.getHost()!=null&&u.getUserInfo()==null&&u.getQuery()==null&&u.getFragment()==null,"api-base 必须为不含凭据、查询参数的 HTTP(S) 地址");}catch(Exception e){throw new IllegalArgumentException("api-base 地址无效");}
   require(inputs.isEmpty(),"仅 report 支持 inputs");
   if(!o.str("group").equals("both")){require(o.str("mode").equals("compare"),"group 只用于 compare");require((cli.containsKey("experiment-id")||file.containsKey("experiment-id"))&&(cli.containsKey("repeat-index")||file.containsKey("repeat-index")),"单组需要 experiment-id 和 repeat-index");require(o.integer("repeats")==1,"单组每次只运行一个重复，请设置 repeats=1");}
  }
  if(!o.str("server-evidence").isEmpty())require(Files.isRegularFile(Path.of(o.str("server-evidence"))),"server-evidence 文件不存在");
  return o;
 }
 static void require(boolean b,String message){if(!b)throw new IllegalArgumentException(message);}
 static String help(){return "Linux/Java AgentHint benchmark (SDK 0.1.17)\n--api-base URL --model-id ID (或 API_BASE / MODEL_NAME)，API_KEY 仅环境变量\n--mode baseline|compare|report (compare) --profile smoke|performance (smoke)\n--config FILE --workload both|continuous|idle-resume (both) --concurrency 1,4,8 (1)\n--sessions N (1/8) --rounds N (2/4) --repeats N (1/5) --warmup-rounds N (1)\n--idle-seconds N (5) --payload-chars N (8192) --max-tokens N (512)\n--request-timeout-seconds N (60) --batch-timeout-seconds N (1800)\n--min-change-percent N (5) --output NEW_DIRECTORY\n--group off|on --experiment-id ID --repeat-index N (单组要求 repeats=1)\n--cache-isolation uncontrolled|external --server-evidence FILE (仅附件，未自动验证)\n--mode report --inputs OFF_DIRECTORY --inputs ON_DIRECTORY (可重复)\n退出码: 0执行完成,1运行/校验失败,2配置/环境失败,130/143中断\n例: bash run_benchmark.sh --mode baseline --api-base https://api.deepseek.com --model-id deepseek-flash";}
}
