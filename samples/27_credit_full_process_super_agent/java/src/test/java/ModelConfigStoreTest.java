import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ModelConfigStoreTest {
    @TempDir Path tempDir;

    private ModelConfigStore store(String name){
        return new ModelConfigStore(tempDir.resolve(name),field->null);
    }

    @Test void defaultsAndEnvironmentFallback() {
        Path missing=tempDir.resolve("not-yet-created.json");
        ModelConfigStore empty=new ModelConfigStore(missing,field->null);
        ModelConfigStore.Settings defaults=empty.settings();
        assertEquals("openai",defaults.provider());
        assertEquals("https://api.deepseek.com",defaults.apiBase());
        assertEquals("deepseek-flash",defaults.modelName());
        assertEquals("",defaults.apiKey());
        assertEquals("none",empty.view().get("source"));
        assertEquals(Boolean.FALSE,empty.view().get("hasApiKey"));

        Map<String,String> env=Map.of("MODEL_PROVIDER","custom-provider","API_BASE","https://env.example.com/v1","MODEL_NAME","env-model","API_KEY","test-key-1234567890");
        ModelConfigStore fromEnv=new ModelConfigStore(missing,env::get);
        ModelConfigStore.Settings settings=fromEnv.settings();
        assertEquals("custom-provider",settings.provider());
        assertEquals("https://env.example.com/v1",settings.apiBase());
        assertEquals("env-model",settings.modelName());
        assertEquals("test-key-1234567890",settings.apiKey());
        assertEquals("env",fromEnv.view().get("source"));
        assertEquals(Boolean.TRUE,fromEnv.view().get("hasApiKey"));
        assertEquals("****7890",fromEnv.view().get("apiKeyMasked"));
    }

    @Test void saveThenReloadRoundtripKeepsAllFields() {
        Path path=tempDir.resolve("roundtrip.json");
        new ModelConfigStore(path,field->null).save("azure-openai","https://example.com/v1","gpt-4o-mini","test-roundtrip-123456");
        ModelConfigStore reloadedStore=new ModelConfigStore(path,field->null);
        ModelConfigStore.Settings reloaded=reloadedStore.settings();
        assertEquals("azure-openai",reloaded.provider());
        assertEquals("https://example.com/v1",reloaded.apiBase());
        assertEquals("gpt-4o-mini",reloaded.modelName());
        assertEquals("test-roundtrip-123456",reloaded.apiKey());
        assertEquals("file",reloadedStore.view().get("source"));
    }

    @Test void viewNeverExposesRawKeyAndMasksTail() {
        ModelConfigStore store=store("mask.json");
        store.save("openai","https://api.deepseek.com","deepseek-flash","test-secret-abcdef");
        Map<String,Object> view=store.view();
        assertEquals(Boolean.TRUE,view.get("hasApiKey"));
        assertEquals("****cdef",view.get("apiKeyMasked"));
        assertFalse(view.toString().contains("test-secret-abcdef"),view.toString());
        store.save("openai","https://api.deepseek.com","deepseek-flash","short");
        assertEquals("****",store.view().get("apiKeyMasked"));
        assertEquals("****",new ModelConfigStore(storePath("mask.json"),field->null).view().get("apiKeyMasked"));
        ModelConfigStore blank=store("blank-mask.json");
        assertEquals("",blank.view().get("apiKeyMasked"));
        assertEquals(Boolean.FALSE,blank.view().get("hasApiKey"));
    }

    @Test void blankApiKeyKeepsSavedKeyAndClearApiKeyRemovesIt() {
        Path path=tempDir.resolve("merge.json");
        ModelConfigStore store=new ModelConfigStore(path,field->null);
        store.save("openai","https://api.deepseek.com","deepseek-flash","test-keep-12345678");
        store.save("openai","https://api.deepseek.com","deepseek-flash","");
        assertEquals("test-keep-12345678",store.settings().apiKey());
        assertEquals(Boolean.TRUE,store.view().get("hasApiKey"));
        store.save("openai","https://api.deepseek.com","deepseek-flash","",true);
        assertEquals("",store.settings().apiKey());
        assertEquals(Boolean.FALSE,store.view().get("hasApiKey"));
        assertEquals("",new ModelConfigStore(path,field->null).settings().apiKey());
    }

    @Test void invalidFieldsAreRejected() {
        Path path=tempDir.resolve("invalid.json");
        ModelConfigStore store=new ModelConfigStore(path,field->null);
        assertThrows(IllegalArgumentException.class,()->store.save("openai","ftp://example.com","deepseek-flash","",false));
        assertThrows(IllegalArgumentException.class,()->store.save("openai","https://api.deepseek.com","   ","",false));
        assertThrows(IllegalArgumentException.class,()->store.save(" ","https://api.deepseek.com","deepseek-flash","",false));
        assertThrows(IllegalArgumentException.class,()->store.save("openai","https://api.deepseek.com","deepseek-flash","k".repeat(513),false));
        assertFalse(Files.exists(path),"校验失败不应写文件");
    }

    @Test void savedFileIsOwnerOnlyBestEffort() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path path=tempDir.resolve("perm.json");
        new ModelConfigStore(path,field->null).save("openai","https://api.deepseek.com","deepseek-flash","test-perm-123456789");
        assertEquals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE),Files.getPosixFilePermissions(path));
    }

    private Path storePath(String name){
        return tempDir.resolve(name);
    }
}
