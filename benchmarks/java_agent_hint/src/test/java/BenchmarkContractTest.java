import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class BenchmarkContractTest {
 @TempDir Path temp;
 BenchmarkMetrics.Row row(String group,int repeat,double elapsed,boolean ok){return new BenchmarkMetrics.Row(group,"continuous",1,repeat,"s"+repeat,1,"first",false,ok,ok,elapsed,elapsed/2,2,1,elapsed,1.,10L,5L,"observed","","");}
 List<BenchmarkMetrics.Batch> batches(){var b=new ArrayList<BenchmarkMetrics.Batch>();for(int r=1;r<=5;r++)for(String g:List.of("off","on")){var rows=new ArrayList<BenchmarkMetrics.Row>();for(int i=0;i<8;i++)rows.add(row(g,r,g.equals("off")?100.:80.,true));b.add(new BenchmarkMetrics.Batch(g,"continuous",1,r,false,g.equals("off")?10:8,0,rows,List.of()));}return b;}
 Map<String,Object> manifest(String mode){var m=new LinkedHashMap<String,Object>();m.put("mode",mode);m.put("profile","performance");m.put("minChangePercent",5.);m.put("cacheIsolation","uncontrolled");return m;}
 String state(String mode,List<BenchmarkMetrics.Batch> b,List<String> errors){return String.valueOf(BenchmarkReport.conclusion(manifest(mode),b,errors,BenchmarkReport.summary(b)).get("performance_status"));}
 @Test void baselineAndIncompleteNeverClaimHint(){assertEquals("BASELINE_ONLY",state("baseline",batches(),List.of()));assertEquals("INCOMPLETE_COMPARISON",state("compare",batches().stream().filter(x->x.group().equals("off")).toList(),List.of()));}
 @Test void deterministicComparisonAndValidityGate(){assertEquals("OBSERVED_IMPROVEMENT",state("compare",batches(),List.of()));assertEquals("INVALID",state("compare",batches(),List.of("timeout")));assertEquals("INSUFFICIENT_DATA",state("compare",batches().subList(0,2),List.of()));}
 @Test void throughputUsesWholeWindowAndTailThreshold(){var sum=BenchmarkReport.summary(batches());var s=sum.stream().filter(x->x.get("group").equals("off")&&x.get("roundType").equals("all")).findFirst().orElseThrow();assertEquals(.8,(Double)s.get("throughput"),1e-9);assertNull(s.get("p99Ms"));assertEquals(100.,s.get("p95Ms"));}
 @Test void missingUsagePreventsInventedTotal(){var r=new BenchmarkMetrics.Row("off","continuous",1,1,"s",1,"first",false,true,true,1.,null,1,1,1.,1.,null,null,"unknown","","");var s=BenchmarkReport.summary(List.of(new BenchmarkMetrics.Batch("off","continuous",1,1,false,1.,0.,List.of(r),List.of()))).get(0);assertNull(s.get("inputTokens"));assertNull(s.get("cacheTokens"));assertEquals(0.,s.get("usageCoverage"));}
 @Test void failureStillWritesReadableReport()throws Exception{var m=manifest("compare");var c=BenchmarkReport.write(temp,m,List.of(),List.of("failed"));assertEquals("INVALID",c.get("performance_status"));assertTrue(Files.isRegularFile(temp.resolve("report.md")));assertTrue(Files.readString(temp.resolve("report.md")).contains("INVALID"));}
 @Test void rowsSerializeAndDeserializeForReproducibleMerge()throws Exception{var list=batches();Path p=temp.resolve("batches.json");AgentHintBenchmark.writeJson(p,list);var again=AgentHintBenchmark.JSON.readValue(p.toFile(),new com.fasterxml.jackson.core.type.TypeReference<List<BenchmarkMetrics.Batch>>(){});assertEquals(list,again);assertEquals(state("compare",list,List.of()),state("compare",again,List.of()));}
 @Test void payloadRespectsCharacterBudget(){assertEquals(2048,BenchmarkWorkload.payload(2048).length());assertEquals(1,BenchmarkWorkload.payload(1).length());}
 @Test void zeroOverdueValidationDoesNotAcceptNonzero(){assertTrue(BenchmarkWorkload.validAnswer("C1001，评分735，逾期笔数0"));assertFalse(BenchmarkWorkload.validAnswer("C1001，评分735，逾期笔数3"));assertFalse(BenchmarkWorkload.validAnswer("C1001，评分735，逾期笔数10"));assertFalse(BenchmarkWorkload.validAnswer("C1001，评分1735，逾期笔数0"));}
}
