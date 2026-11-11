import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class BenchmarkOptionsTest {
 @TempDir Path tmp;
 @Test void configurationPriorityAndProfile() throws Exception {
  Path file=tmp.resolve("config.yaml");Files.writeString(file,"# YAML parameters\napi-base: http://file:8000/v1\nmodel-id: file-model\nsessions: 3\n");
  var o=BenchmarkOptions.parseInternal(new String[]{"--config",file.toString(),"--model-id","cli-model","--profile","performance"},Map.of("API_BASE","http://env:8000","MODEL_NAME","env-model"));
  assertEquals("cli-model",o.str("model-id"));assertEquals("http://file:8000/v1",o.str("api-base"));assertEquals(3,o.integer("sessions"));assertEquals(5,o.integer("repeats"));
 }
 @Test void acceptsYmlAndRejectsDuplicateUnknownAndInvalidYaml() throws Exception {
  Path file=tmp.resolve("config.yml");
  Files.writeString(file,"api-base: http://host/v1\nmodel-id: model\n");
  assertEquals("model",BenchmarkOptions.parseInternal(new String[]{"--config",file.toString()},Map.of()).str("model-id"));
  for(String bad:List.of("api-base: http://host\nmodel-id: first\nmodel-id: second\n","api-key: secret\n","sessions: [1, 2]\n","[broken", "")){
   Files.writeString(file,bad);assertThrows(IllegalArgumentException.class,()->BenchmarkOptions.parseInternal(new String[]{"--config",file.toString()},Map.of()));
  }
 }
 @Test void rejectsJsonConfigExtension() throws Exception {
  Path file=tmp.resolve("config.json");Files.writeString(file,"{\"api-base\":\"http://host\",\"model-id\":\"m\"}");
  assertThrows(IllegalArgumentException.class,()->BenchmarkOptions.parseInternal(new String[]{"--config",file.toString()},Map.of()));
 }
 @Test void dailyEntryDefaultsToComparisonAndCreatesUniqueAbsolutePaths() throws Exception {
  Path file=tmp.resolve("daily.yaml");Files.writeString(file,"api-base: http://host/v1\nmodel-id: model\n");
  var a=BenchmarkOptions.parse(new String[]{"--config",file.toString()},Map.of());
  var b=BenchmarkOptions.parse(new String[]{file.toString()},Map.of());
  assertEquals("compare",a.str("mode"));assertEquals("both",a.str("workload"));assertEquals(5,a.integer("repeats"));assertTrue(a.output().isAbsolute());assertNotEquals(a.output(),b.output());
 }
 @Test void noArgumentsReadDefaultYamlAndInferSessionCount() throws Exception {
  Files.writeString(tmp.resolve("benchmark.yaml"),"api-base: http://host/v1\nmodel-id: model\nconcurrency: [1, 16]\n");
  String previous=System.getProperty("agent.hint.benchmark.dir");
  try{System.setProperty("agent.hint.benchmark.dir",tmp.toString());var o=BenchmarkOptions.parse(new String[0],Map.of());assertEquals(List.of(1,16),o.concurrency());assertEquals(16,o.integer("sessions"));assertEquals("compare",o.str("mode"));assertTrue(o.output().startsWith(tmp));}
  finally{if(previous==null)System.clearProperty("agent.hint.benchmark.dir");else System.setProperty("agent.hint.benchmark.dir",previous);}
 }
 @Test void dailyEntryRejectsInternalKnobs() throws Exception {
  assertThrows(IllegalArgumentException.class,()->BenchmarkOptions.parse(new String[]{"--mode","baseline"},Map.of()));
  assertThrows(IllegalArgumentException.class,()->BenchmarkOptions.parse(new String[]{"--output","anything"},Map.of()));
  Path file=tmp.resolve("internal.yaml");Files.writeString(file,"mode: baseline\n");
  assertThrows(IllegalArgumentException.class,()->BenchmarkOptions.parse(new String[]{file.toString()},Map.of()));
 }
 @Test void checkIsExplicitAndDoesNotSilentlyReplaceComparison() throws Exception {
  Path file=tmp.resolve("check.yaml");Files.writeString(file,"api-base: http://host/v1\nmodel-id: model\nsessions: 8\nrounds: 4\nrepeats: 5\n");
  var o=BenchmarkOptions.parse(new String[]{"check",file.toString()},Map.of());assertEquals("baseline",o.str("mode"));assertEquals(1,o.integer("sessions"));assertEquals(2,o.integer("rounds"));assertEquals(1,o.integer("repeats"));
 }
 @Test void rejectsUnsafeOrUnknownConfig(){assertThrows(IllegalArgumentException.class,()->BenchmarkOptions.parseInternal(new String[]{"--bogus","x"},Map.of()));assertThrows(IllegalArgumentException.class,()->BenchmarkOptions.parseInternal(new String[]{"--api-base","https://user:key@host","--model-id","m"},Map.of()));}
 @Test void reportNeedsNoModel(){var o=BenchmarkOptions.parseInternal(new String[]{"--mode","report","--inputs","off","--inputs","on"},Map.of());assertEquals(2,o.inputs.size());}
 @Test void rejectsBadConcurrencyAndNonFinite(){assertThrows(IllegalArgumentException.class,()->BenchmarkOptions.parseInternal(new String[]{"--api-base","http://host","--model-id","m","--concurrency","2"},Map.of()));assertThrows(IllegalArgumentException.class,()->BenchmarkOptions.parseInternal(new String[]{"--api-base","http://host","--model-id","m","--min-change-percent","NaN"},Map.of()));}
}
