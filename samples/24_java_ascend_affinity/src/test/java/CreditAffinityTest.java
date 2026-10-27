import static org.junit.jupiter.api.Assertions.*;

import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import org.junit.jupiter.api.Test;

class CreditAffinityTest {
    @Test
    void normalUsesOfficialOpenAiClientWithoutRelease() {
        var config = CreditAffinity.config(false, "http://localhost:1234/v1", "test", "gemma");
        assertEquals("openai", config.getModelClientConfig().getClientProvider());
        assertFalse(config.getContextEngineConfig().isEnableKvCacheRelease());
        assertEquals(0, config.getModelClientConfig().getMaxRetries());
        assertEquals(512, config.getModelConfigObj().getMaxTokens());
    }

    @Test
    void affinityUsesOfficialProviderAndContextSwitchTogether() {
        var config = CreditAffinity.config(true, "http://localhost:8000", "test", "model");
        assertEquals("InferenceAffinity", config.getModelClientConfig().getClientProvider());
        assertTrue(config.getContextEngineConfig().isEnableKvCacheRelease());
        var model = new Model(config.getModelClientConfig(), config.getModelConfigObj());
        assertEquals(Boolean.TRUE, model.buildKvCacheInvokeKwargs(null, true).get("enable_cache_sharing"));
        assertTrue(model.buildKvCacheInvokeKwargs(null, false).isEmpty());
    }

    @Test
    void wrongAffinityBaseFailsBeforeCallingModel() {
        assertThrows(IllegalArgumentException.class,
                () -> CreditAffinity.config(true, "http://localhost:8000/v1/", "test", "model"));
        assertThrows(IllegalArgumentException.class, () -> CreditAffinity.enabled("ture"));
    }
}
