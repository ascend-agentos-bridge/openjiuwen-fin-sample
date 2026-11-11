import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.core.JsonParser;
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
 // Daily interface: YAML configuration, automatic comparison and automatic report directory.
 static final Set<String> USER_KEYS=Set.of("api-base","model-id","concurrency","sessions","rounds","repeats","idle-seconds","payload-chars","max-tokens","request-timeout-seconds");
 static BenchmarkOptions parse(String[] args,Map<String,String> env){
  if(args.length>0&&args[0].equals("merge")){
   List<String> internal=new ArrayList<>(List.of("--mode","report"));
   for(int i=1;i<args.length;i++){require(!args[i].startsWith("--"),"merge 后只填写报告目录");internal.add("--inputs");internal.add(args[i]);}
   return parseInternal(internal.toArray(String[]::new),env);
  }
  boolean check=args.length>0&&args[0].equals("check");int offset=check?1:0;
  Path config=Path.of(System.getProperty("agent.hint.benchmark.dir","benchmarks/java_agent_hint"),"benchmark.yaml");
  if(args.length>offset){
   if(args[offset].equals("--config")){require(args.length==offset+2,"用法：run_benchmark.sh [check] [--config FILE.yaml]");config=Path.of(args[offset+1]);}
   else {require(args.length==offset+1&&!args[offset].startsWith("--"),"无需 mode/output 等参数；修改 benchmark.yaml，直接执行脚本即可");config=Path.of(args[offset]);}
  }
  Map<String,Object> settings=readYaml(config.toString(),USER_KEYS);
  if(settings.get("concurrency") instanceof List<?> levels){require(!levels.isEmpty()&&levels.stream().allMatch(x->x instanceof Integer),"concurrency 列表必须为整数");settings.put("concurrency",levels.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")));}
  List<String> internal=new ArrayList<>(List.of("--mode",check?"baseline":"compare","--profile",check?"smoke":"performance"));
  if(!check&&!settings.containsKey("sessions")&&settings.containsKey("concurrency")){
   try{settings.put("sessions",Math.max(8,Arrays.stream(String.valueOf(settings.get("concurrency")).split(",")).mapToInt(Integer::parseInt).max().orElse(1)));}catch(Exception e){throw new IllegalArgumentException("concurrency 必须为正整数列表");}
  }
  for(var e:settings.entrySet()){if(check&&Set.of("concurrency","sessions","rounds","repeats").contains(e.getKey()))continue;internal.add("--"+e.getKey());internal.add(String.valueOf(e.getValue()));}
  if(check){internal.addAll(List.of("--concurrency","1","--sessions","1","--rounds","2","--repeats","1"));}
  var result=parseInternal(internal.toArray(String[]::new),env);
  return result;
 }
 static Map<String,Object> readYaml(String config,Set<String> allowed){
  require(config.endsWith(".yaml")||config.endsWith(".yml"),"config 必须为 .yaml 或 .yml 文件");
  try{
   Map<String,Object> file=new ObjectMapper(new YAMLFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)).readValue(Path.of(config).toFile(),new TypeReference<LinkedHashMap<String,Object>>(){});
   require(file!=null,"YAML 配置不可为空");
   for(var e:file.entrySet())require(allowed.contains(e.getKey())&&e.getValue()!=null&&(e.getValue() instanceof String||e.getValue() instanceof Number||e.getKey().equals("concurrency")&&e.getValue() instanceof List<?>),"不支持的配置字段或值: "+e.getKey());
   return file;
  }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("不能读取 YAML 配置文件，请检查路径及 YAML 格式: "+config);}
 }
 static BenchmarkOptions parseInternal(String[] args,Map<String,String> env){
  Map<String,Object> cli=new LinkedHashMap<>(),file=new LinkedHashMap<>(); List<Path> inputs=new ArrayList<>();String config=null;
  for(int j=0;j<args.length;j++){
   String a=args[j];if(!a.startsWith("--")||j+1==args.length)throw new IllegalArgumentException("参数需要 --名称 值，使用 --help 查看");
   String key=a.substring(2),v=args[++j];
   if(key.equals("config")){if(config!=null)throw new IllegalArgumentException("config 不可重复");config=v;}
   else if(key.equals("inputs"))inputs.add(Path.of(v));
   else {if(!KEYS.contains(key))throw new IllegalArgumentException("未知参数: "+key);if(cli.putIfAbsent(key,v)!=null)throw new IllegalArgumentException("参数不可重复: "+key);}
  }
  if(config!=null)file=readYaml(config,KEYS);
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
 static String help(){return "AgentHint benchmark\n用法: bash run_benchmark.sh [配置.yaml]\n默认读取脚本旁的 benchmark.yaml，自动运行 off/on 对比与两类负载。\n报告保存到 reports/run-时间戳-随机ID，终端打印本次报告绝对路径。\nAPI_KEY 仅从环境变量读取。\n可选: --config FILE.yaml；check [配置.yaml] 进行普通模型小规模试跑；merge DIR DIR... 合并已有配对结果。\n配置中保留模型连接和负载规模；mode/output/profile/group/实验编号由程序管理。\n退出码: 0执行完成，1运行或校验失败，2配置或构建失败。";}
}
