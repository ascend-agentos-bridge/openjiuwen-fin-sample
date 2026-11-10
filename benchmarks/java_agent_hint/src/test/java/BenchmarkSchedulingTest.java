import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class BenchmarkSchedulingTest {
 @Test void stageHonorsHardDeadline() throws Exception {
  var pool=Executors.newSingleThreadExecutor();long start=System.nanoTime();
  try{assertThrows(TimeoutException.class,()->BenchmarkWorkload.runStage(pool,List.of(()->{Thread.sleep(60000);return null;}),System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(30)));assertTrue(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(3));}finally{pool.shutdownNow();assertTrue(pool.awaitTermination(1,TimeUnit.SECONDS));}
 }
 @Test void mapsResumedRoundSeparately(){assertEquals("first",BenchmarkWorkload.roundType("idle-resume",1));assertEquals("resumed",BenchmarkWorkload.roundType("idle-resume",2));assertEquals("subsequent",BenchmarkWorkload.roundType("idle-resume",3));}
}
