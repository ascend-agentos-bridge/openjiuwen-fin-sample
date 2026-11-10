import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class BenchmarkMergeTest {
 @TempDir Path root;
 Path single(String g,int repeat,String model) throws Exception {
  Path p=root.resolve(g+repeat+model);Files.createDirectory(p);
  var options=BenchmarkOptions.parse(new String[]{"--mode","compare","--group",g,"--experiment-id","paired-test","--repeat-index",String.valueOf(repeat),"--repeats","1","--api-base","http://host/v1","--model-id",model},Map.of());
  var manifest=AgentHintBenchmark.manifest(options);var row=new BenchmarkMetrics.Row(g,"continuous",1,repeat,"s",1,"first",false,true,true,100.,50.,2,1,100.,1.,10L,5L,"observed","","");
  try(var sink=new AgentHintBenchmark.Sink(p)){sink.line("requests.jsonl",row);}
  BenchmarkReport.write(p,manifest,List.of(new BenchmarkMetrics.Batch(g,"continuous",1,repeat,false,1.,0.,List.of(row),List.of())),new ArrayList<>());return p;
 }
 BenchmarkOptions options(Path off,Path on,String dest){return BenchmarkOptions.parse(new String[]{"--mode","report","--inputs",off.toString(),"--inputs",on.toString(),"--output",root.resolve(dest).toString()},Map.of());}
 @Test void mergesPairedRawDataWithoutModel()throws Exception{Path off=single("off",1,"m"),on=single("on",1,"m");assertEquals(0,AgentHintBenchmark.run(options(off,on,"merged")));assertTrue(Files.readString(root.resolve("merged/conclusion.json")).contains("INSUFFICIENT_DATA"));assertEquals(2,Files.readAllLines(root.resolve("merged/requests.jsonl")).size());}
 @Test void rejectsMismatchedModels()throws Exception{Path off=single("off",1,"m"),on=single("on",1,"other");assertThrows(IllegalArgumentException.class,()->AgentHintBenchmark.run(options(off,on,"mismatch")));}
 @Test void rejectsMismatchedRepeatPairs()throws Exception{Path off=single("off",1,"m"),on=single("on",2,"m");assertThrows(IllegalArgumentException.class,()->AgentHintBenchmark.run(options(off,on,"missing")));}
 @Test void rejectsDuplicateBatches()throws Exception{Path off=single("off",1,"m");assertThrows(IllegalArgumentException.class,()->AgentHintBenchmark.run(options(off,off,"duplicate")));}
}
