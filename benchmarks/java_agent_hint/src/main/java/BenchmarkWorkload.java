import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.schema.*;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.kvcache.*;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.session.AgentSession;
import com.openjiuwen.core.session.stream.*;
import com.openjiuwen.core.singleagent.agents.*;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.core.singleagent.rail.*;
import java.util.*;
import java.util.concurrent.*;

/** Benchmark load only. Uses the official SDK directly and bounded, wave-based sessions. */
final class BenchmarkWorkload {
 static final String PROMPT="你是银行信贷复核助手。每轮必须先调用且只调用一次credit_record查档，不得复用历史结果。工具返回后简短回答客户编号、信用评分和逾期笔数，保留原始数字，不输出明细。工具数据为虚构演示，不构成授信建议。";
 static final String DATA_VERSION="credit-record-v1-C1001-735-0";
 static final class Session implements AutoCloseable {
  final String id="hint-"+UUID.randomUUID();final ReActAgent agent;volatile AgentSession session;final KVCacheRuntime runtime;final String toolId=id+"-tool";final String group,workload;final int concurrency,repeat;final boolean warmup;final BenchmarkOptions o;final AgentHintBenchmark.Sink sink;
  volatile BenchmarkMetrics.Observation current;volatile Object stream;volatile boolean sealed;boolean closed;final List<BenchmarkMetrics.Row> rows=new ArrayList<>();final List<String> errors=new ArrayList<>();
  Session(BenchmarkOptions o,String group,String workload,int concurrency,int repeat,boolean warmup,AgentHintBenchmark.Sink sink){
   this.o=o;this.group=group;this.workload=workload;this.concurrency=concurrency;this.repeat=repeat;this.warmup=warmup;this.sink=sink;
   boolean enabled=group.equals("on");
   var client=ModelClientConfig.builder().clientProvider(o.str("mode").equals("baseline")?"OpenAI":"AscendAffinity").apiBase(o.str("api-base")).apiKey(System.getenv().getOrDefault("API_KEY","not-required")).timeout(o.integer("request-timeout-seconds")).maxRetries(0).extraFields(Map.of("extensions",Map.of("kv_cache",Map.of("mode",enabled?"affinity":"none")))).build();
   var request=ModelRequestConfig.builder().modelName(o.str("model-id")).temperature(0.).topP(1.).maxTokens(o.integer("max-tokens")).extraFields(Map.of("thinking",Map.of("type","disabled"),"stream_options",Map.of("include_usage",true))).build();
   Model model=new Model(client,request);
   runtime=enabled?new KVCacheRuntime(new KVCacheConfig(5.,5.,5.),()->model):null;
   var card=new AgentCard(id,id,"虚构信贷复核 benchmark");agent=new ReActAgent(card);
   var config=ReActAgentConfig.builder().modelConfigObj(request).modelName(o.str("model-id")).modelClientConfig(client).promptTemplate(List.of(Map.of("role","system","content",PROMPT))).maxIterations(3).maxParallelToolCalls(1).streamMaxRetries(0).build();config.setEnableKvCacheAffinity(enabled);agent.configure(config);agent.setLlm(model);
   LocalFunction tool=new LocalFunction(ToolCard.builder().id(toolId).name("credit_record").description("读取C1001的虚构征信档案")
    .inputParams(Map.of("type","object","properties",Map.of("customer_id",Map.of("type","string")),"required",List.of("customer_id"))).build(),in->{if(!"C1001".equals(in.get("customer_id")))throw new IllegalArgumentException("未知演示客户");return Map.of("customer_id","C1001","credit_score",735,"overdue_count",0,"details",payload(o.integer("payload-chars")));});
   Runner.resourceMgr().addTool(tool,id);agent.getAbilityManager().add(tool.getCard());
   session=new AgentSession(id,new LinkedHashMap<>(),card,null,false,new LinkedHashMap<>(),runtime);
   for(AgentCallbackEvent event:List.of(AgentCallbackEvent.BEFORE_MODEL_CALL,AgentCallbackEvent.AFTER_MODEL_CALL,AgentCallbackEvent.ON_MODEL_EXCEPTION,AgentCallbackEvent.BEFORE_TOOL_CALL,AgentCallbackEvent.AFTER_TOOL_CALL))agent.getAgentCallbackManager().registerCallback(event,ctx->{observe(event,ctx);return CompletableFuture.completedFuture(null);},0);
  }
  void observe(AgentCallbackEvent event,AgentCallbackContext ctx){var obs=current;if(obs==null||sealed)return;
   synchronized(obs){
    if(event==AgentCallbackEvent.BEFORE_MODEL_CALL){obs.modelCalls++;obs.modelStart=System.nanoTime();}
    if(event==AgentCallbackEvent.BEFORE_TOOL_CALL){obs.toolCalls++;obs.toolStart=System.nanoTime();}
    if(event==AgentCallbackEvent.AFTER_TOOL_CALL&&obs.toolStart!=0)obs.toolMs+=BenchmarkMetrics.elapsed(obs.toolStart);
    if(event==AgentCallbackEvent.AFTER_MODEL_CALL||event==AgentCallbackEvent.ON_MODEL_EXCEPTION){
     double elapsed=obs.modelStart==0?0:BenchmarkMetrics.elapsed(obs.modelStart);obs.modelMs+=elapsed;
     Map<String,Object> call=new LinkedHashMap<>();call.put("group",group);call.put("workload",workload);call.put("sessionId",id);call.put("repeat",repeat);call.put("warmup",warmup);call.put("modelCallIndex",obs.modelCalls);call.put("elapsedMs",elapsed);call.put("success",event==AgentCallbackEvent.AFTER_MODEL_CALL);call.put("cacheTokens",null);
     UsageMetadata usage=null;
     if(ctx.getInputs() instanceof ModelCallInputs in&&in.getResponse() instanceof AssistantMessage m){usage=m.getUsageMetadata();if(m.getToolCalls()==null||m.getToolCalls().isEmpty())obs.finishReason=m.getFinishReason();}
     boolean known=usage!=null&&usage.getInputTokens()>0&&usage.getOutputTokens()>0;
     if(known){obs.knownUsage++;obs.input+=usage.getInputTokens();obs.output+=usage.getOutputTokens();}
     call.put("inputTokens",known?usage.getInputTokens():null);call.put("outputTokens",known?usage.getOutputTokens():null);call.put("usageStatus",known?"observed_positive_counts":"unavailable_or_ambiguous_zero");call.put("firstContentMs",null);obs.calls.add(call);
    }
   }
  }
  void round(int n,String type){if(sealed)return;long start=System.nanoTime();var obs=new BenchmarkMetrics.Observation();current=obs;boolean success=false;String error="";
   try{
    // Runner also creates a fresh facade per invocation. Keep cache identity/runtime,
    // while avoiding reuse of a terminal streaming queue from the previous turn.
    session=new AgentSession(id,new LinkedHashMap<>(),agent.getCard(),null,false,new LinkedHashMap<>(),runtime);
    Iterator<Object> iterator=Runner.runAgentStreaming(agent,Map.of("query","第"+n+"轮：请重新查询客户C1001，回答客户编号、信用评分、逾期笔数。"),session,null,List.of(StreamMode.OUTPUT));stream=iterator;boolean answered=false;
    while(!sealed&&iterator.hasNext()){
     Object frame=iterator.next();if(!(frame instanceof OutputSchema out)||!(out.getPayload() instanceof Map<?,?> p))continue;
     if("error".equals(out.getType()))throw new IllegalStateException("Agent stream error");
     if("llm_output".equals(out.getType())&&p.get("content") instanceof String text&&!text.isBlank()&&obs.firstContentMs==null)obs.firstContentMs=BenchmarkMetrics.elapsed(start);
     if("answer".equals(out.getType())){if(!"answer".equals(p.get("result_type")))throw new IllegalStateException("Agent未正常返回answer: "+AgentHintBenchmark.safe(String.valueOf(p)));obs.answer.setLength(0);obs.answer.append(Objects.toString(p.get("output"),""));answered=!obs.answer.toString().isBlank();}
    }
    if(!answered||!"stop".equals(obs.finishReason))throw new IllegalStateException("回答不完整或为空，finish_reason="+obs.finishReason);
    success=true;
   }catch(Exception e){error=AgentHintBenchmark.safe(e);}finally{Object s=stream;stream=null;if(s instanceof AutoCloseable c)try{c.close();}catch(Exception ignored){}}
   String answer=obs.answer.toString();boolean correct=success&&validAnswer(answer)&&obs.toolCalls==1;
   if(success&&!correct)error="业务校验失败：客户/735/逾期0 或每轮一次工具调用不符";
   synchronized(this){if(!sealed){boolean usageKnown=obs.modelCalls>0&&obs.modelCalls==obs.knownUsage;var row=new BenchmarkMetrics.Row(group,workload,concurrency,repeat,id,n,type,warmup,success,correct,BenchmarkMetrics.elapsed(start),obs.firstContentMs,obs.modelCalls,obs.toolCalls,obs.modelMs,obs.toolMs,usageKnown?obs.input:null,usageKnown?obs.output:null,usageKnown?"observed_positive_counts":"unavailable_or_ambiguous_zero",error,answer);rows.add(row);sink.line("requests.jsonl",row);for(var call:obs.calls){call.put("round",n);sink.line("model-calls.jsonl",call);}}}
  }
  void action(String action){long start=System.nanoTime();boolean ok=false;String error="";try{ok=(action.equals("suspend")?session.suspendKvc():session.releaseKvc()).get(o.integer("request-timeout-seconds"),TimeUnit.SECONDS);if(!ok)error="缓存动作返回false";}catch(Exception e){error=AgentHintBenchmark.safe(e);}synchronized(this){if(!sealed){var m=new LinkedHashMap<String,Object>();m.put("group",group);m.put("workload",workload);m.put("sessionId",id);m.put("repeat",repeat);m.put("warmup",warmup);m.put("action",action);m.put("elapsedMs",BenchmarkMetrics.elapsed(start));m.put("clientSuccess",ok);m.put("serverExecuted",null);m.put("error",error);sink.line("actions.jsonl",m);if(!ok)errors.add(action+": "+error);}}}
  synchronized void sealMissing(int rounds){sealed=true;for(int n=1;n<=rounds;n++){final int round=n;if(rows.stream().noneMatch(r->r.round()==round)){var row=new BenchmarkMetrics.Row(group,workload,concurrency,repeat,id,n,roundType(workload,n),warmup,false,false,null,null,0,0,null,null,null,null,"unavailable","NOT_COMPLETED"," ");rows.add(row);sink.line("requests.jsonl",row);}}Object active=stream;if(active instanceof AutoCloseable c){Thread t=new Thread(()->{try{c.close();}catch(Exception ignored){}},"close-stream");t.setDaemon(true);t.start();}}
  public void close(){synchronized(this){if(closed)return;closed=true;}try{if(runtime!=null){action("release");runtime.close().get(6,TimeUnit.SECONDS);}}catch(Exception e){synchronized(this){errors.add("runtime清理: "+AgentHintBenchmark.safe(e));}}finally{Runner.resourceMgr().removeTool(toolId);agent.close();Runner.release(id);}}
 }
 static String payload(int chars){String unit="正常还款；";return unit.repeat((chars+unit.length()-1)/unit.length()).substring(0,chars);}
 static boolean validAnswer(String a){return a.matches("(?s).*(?<![A-Za-z0-9])C1001(?![A-Za-z0-9]).*")&&a.matches("(?s).*(?<![0-9])735(?![0-9]).*")&&a.matches("(?s).*逾期[^。\\n0-9]{0,16}(?:0(?![0-9])|零|无).*");}
 static String roundType(String workload,int n){return n==1?"first":workload.equals("idle-resume")&&n==2?"resumed":"subsequent";}
 static BenchmarkMetrics.Batch batch(BenchmarkOptions o,String group,String workload,int concurrency,int repeat,boolean warmup,AgentHintBenchmark.Sink sink) throws Exception{
  long start=System.nanoTime(),deadline=start+TimeUnit.SECONDS.toNanos(o.integer("batch-timeout-seconds"));
  int total=warmup?Math.min(concurrency,o.integer("sessions")):o.integer("sessions"),rounds=warmup?o.integer("warmup-rounds"):o.integer("rounds");
  List<Session> all=new ArrayList<>();List<String> errors=new ArrayList<>();double idle=0;
  ExecutorService pool=Executors.newFixedThreadPool(concurrency,r->{Thread t=new Thread(r,"benchmark-session");t.setDaemon(true);return t;});
  try{
   for(int offset=0;offset<total;offset+=concurrency){List<Session> wave=new ArrayList<>();for(int j=offset;j<Math.min(total,offset+concurrency);j++){Session s=new Session(o,group,workload,concurrency,repeat,warmup,sink);wave.add(s);all.add(s);}
    for(int n=1;n<=rounds;n++){
     final int round=n;runStage(pool,wave.stream().<Callable<Void>>map(s->()->{s.round(round,roundType(workload,round));return null;}).toList(),deadline);
     if(n==1&&rounds>1&&workload.equals("idle-resume")){
      if(group.equals("on"))runStage(pool,wave.stream().<Callable<Void>>map(s->()->{s.action("suspend");return null;}).toList(),deadline);
      long wait=(long)(o.number("idle-seconds")*1_000_000_000L);if(System.nanoTime()+wait>=deadline)throw new TimeoutException("等待窗口超过批次截止时间");TimeUnit.NANOSECONDS.sleep(wait);idle+=wait/1e9;
     }
    }
    runStage(pool,wave.stream().<Callable<Void>>map(s->()->{s.close();return null;}).toList(),deadline);
   }
  }catch(Exception e){errors.add(AgentHintBenchmark.safe(e));for(Session s:all)s.sealMissing(rounds);}
  finally{pool.shutdownNow();if(!pool.awaitTermination(2,TimeUnit.SECONDS))errors.add("worker未退出，已封存结果，停止后续实验");
   ExecutorService cleanup=Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"aborted-session-cleanup");t.setDaemon(true);return t;});
   try{var pending=cleanup.invokeAll(all.stream().<Callable<Void>>map(s->()->{s.close();return null;}).toList(),7,TimeUnit.SECONDS);for(var f:pending)if(f.isCancelled())errors.add("会话清理超时");else try{f.get();}catch(Exception e){errors.add("会话清理失败: "+AgentHintBenchmark.safe(e));}}finally{cleanup.shutdownNow();}
  }
  List<BenchmarkMetrics.Row> rows=new ArrayList<>();for(Session s:all){s.sealMissing(rounds);rows.addAll(s.rows);errors.addAll(s.errors);}
  // Initialization errors can leave sessions uncreated; retain the planned denominator.
  for(int missing=all.size();missing<total;missing++)for(int n=1;n<=rounds;n++){var row=new BenchmarkMetrics.Row(group,workload,concurrency,repeat,"not-started-"+missing,n,roundType(workload,n),warmup,false,false,null,null,0,0,null,null,null,null,"unavailable","NOT_COMPLETED: initialization or batch failure","");rows.add(row);sink.line("requests.jsonl",row);}
  return new BenchmarkMetrics.Batch(group,workload,concurrency,repeat,warmup,BenchmarkMetrics.elapsed(start)/1000.,idle,rows,List.copyOf(errors));
 }
 static void runStage(ExecutorService pool,List<Callable<Void>> tasks,long deadline)throws Exception{
  long remaining=deadline-System.nanoTime();if(remaining<=0)throw new TimeoutException("批次超时");var futures=pool.invokeAll(tasks,remaining,TimeUnit.NANOSECONDS);for(var f:futures){if(f.isCancelled())throw new TimeoutException("批次超时");f.get();}
 }
}
