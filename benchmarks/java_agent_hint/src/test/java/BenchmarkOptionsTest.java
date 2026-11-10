import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class BenchmarkOptionsTest {
 @TempDir Path tmp;
 @Test void configurationPriorityAndProfile() throws Exception {
  Path file=tmp.resolve("config.json");Files.writeString(file,"{\"api-base\":\"http://file:8000/v1\",\"model-id\":\"file-model\",\"sessions\":3}");
  var o=BenchmarkOptions.parse(new String[]{"--config",file.toString(),"--model-id","cli-model","--profile","performance"},Map.of("API_BASE","http://env:8000","MODEL_NAME","env-model"));
  assertEquals("cli-model",o.str("model-id"));assertEquals("http://file:8000/v1",o.str("api-base"));assertEquals(3,o.integer("sessions"));assertEquals(5,o.integer("repeats"));
 }
 @Test void rejectsUnsafeOrUnknownConfig(){assertThrows(IllegalArgumentException.class,()->BenchmarkOptions.parse(new String[]{"--bogus","x"},Map.of()));assertThrows(IllegalArgumentException.class,()->BenchmarkOptions.parse(new String[]{"--api-base","https://user:key@host","--model-id","m"},Map.of()));}
 @Test void reportNeedsNoModel(){var o=BenchmarkOptions.parse(new String[]{"--mode","report","--inputs","off","--inputs","on"},Map.of());assertEquals(2,o.inputs.size());}
 @Test void rejectsBadConcurrencyAndNonFinite(){assertThrows(IllegalArgumentException.class,()->BenchmarkOptions.parse(new String[]{"--api-base","http://host","--model-id","m","--concurrency","2"},Map.of()));assertThrows(IllegalArgumentException.class,()->BenchmarkOptions.parse(new String[]{"--api-base","http://host","--model-id","m","--min-change-percent","NaN"},Map.of()));}
}
