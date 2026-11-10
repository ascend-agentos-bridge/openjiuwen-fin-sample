import org.junit.jupiter.api.Test;
import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.schema.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class BenchmarkAffinityConfigTest {
 @Test void officialCapabilityDependsOnExtensionNotProviderName(){
  for(String mode:List.of("none","affinity")){
   var config=ModelClientConfig.builder().clientProvider("AscendAffinity").apiBase("http://localhost:8000/v1").apiKey("not-required").extraFields(Map.of("extensions",Map.of("kv_cache",Map.of("mode",mode)))).build();
   var model=new Model(config,ModelRequestConfig.builder().modelName("configured-model").build());
   assertEquals(mode.equals("affinity"),model.supportsKvCacheAffinity());
  }
 }
}
