import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class BenchmarkReportTest {
 @Test void percentileUsesNearestRank(){ assertEquals(3.0,BenchmarkReport.percentile(List.of(1.,2.,3.),.95)); }
 @Test void missingAndZeroBaselineNeverProduceInfinity(){assertNull(BenchmarkReport.change(0.,1.,true));assertNull(BenchmarkReport.change(null,1.,true));}
 @Test void requiresConsistentRepeats(){assertEquals("IMPROVED",BenchmarkReport.metric(10.,List.of(8.,9.,10.,11.,-1.),5));assertEquals("UNSTABLE",BenchmarkReport.metric(10.,List.of(8.,-9.,10.,-11.,-1.),5));}
 @Test void thresholdBoundaryAndTradeoff(){assertEquals("IMPROVED",BenchmarkReport.metric(5.,Collections.nCopies(5,5.),5)); assertEquals("TRADEOFF",BenchmarkReport.combine(List.of("IMPROVED","REGRESSED")));assertEquals("INCONCLUSIVE",BenchmarkReport.combine(List.of("IMPROVED","UNKNOWN")));}
 @Test void smallChangesAreNotSignificantClaims(){assertEquals("UNCHANGED",BenchmarkReport.metric(1.,Collections.nCopies(5,1.),5));}
}
