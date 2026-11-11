import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@Tag("integration")
class BenchmarkRealModelTest {
 @TempDir Path tmp;
 @Test void realConcurrentTwoTurnWorkloads()throws Exception{
  assertNotNull(System.getenv("API_BASE"));assertNotNull(System.getenv("MODEL_NAME"));
  var o=BenchmarkOptions.parseInternal(new String[]{"--mode","baseline","--workload","both","--concurrency","2","--sessions","2","--rounds","2","--warmup-rounds","0","--idle-seconds","0","--payload-chars","512","--output",tmp.resolve("run").toString()},System.getenv());
  assertEquals(0,AgentHintBenchmark.run(o));
  var rows=Files.readAllLines(o.output().resolve("requests.jsonl"));assertEquals(8,rows.size());
  for(String row:rows){var r=AgentHintBenchmark.JSON.readValue(row,BenchmarkMetrics.Row.class);assertTrue(r.success());assertTrue(r.correct());assertEquals(1,r.toolCalls());assertEquals(2,r.modelCalls());}
  assertTrue(Files.readString(o.output().resolve("conclusion.json")).contains("BASELINE_ONLY"));
 }
}
