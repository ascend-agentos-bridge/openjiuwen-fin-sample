import java.util.*;

/** Serializable observations; null means unavailable, not zero. */
final class BenchmarkMetrics {
 record Row(String group,String workload,int concurrency,int repeat,String sessionId,int round,String roundType,
            boolean warmup,boolean success,boolean correct,Double elapsedMs,Double firstContentMs,
            int modelCalls,int toolCalls,Double modelMs,Double toolMs,Long inputTokens,Long outputTokens,
            String usageStatus,String error,String answer) {}
 record Batch(String group,String workload,int concurrency,int repeat,boolean warmup,double wallSeconds,
              double scheduledIdleSeconds,List<Row> rows,List<String> errors) {}
 static final class Observation {
  long modelStart,toolStart;int modelCalls,toolCalls,knownUsage;double modelMs,toolMs;long input,output;
  Double firstContentMs;String finishReason;StringBuilder answer=new StringBuilder();
  final List<Map<String,Object>> calls=new ArrayList<>();
 }
 static double elapsed(long start){return (System.nanoTime()-start)/1_000_000.;}
}
