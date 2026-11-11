import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.function.Function;
/**
 * sample27 模型连接配置：本地 JSON 持久化 + 环境变量兜底 + 内存缓存。
 * 只读视图 view() 永远不返回原始 apiKey，最多返回末 4 位掩码。
 */
public final class ModelConfigStore {
    static final String ENV_FILE="SAMPLE27_MODEL_CONFIG";
    static final String ENV_PROVIDER="MODEL_PROVIDER",ENV_API_BASE="API_BASE",ENV_MODEL_NAME="MODEL_NAME",ENV_API_KEY="API_KEY";
    static final String DEFAULT_PROVIDER="openai",DEFAULT_API_BASE="https://api.deepseek.com",DEFAULT_MODEL_NAME="deepseek-flash";
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final int MAX_PROVIDER=32,MAX_MODEL_NAME=128,MAX_API_BASE=512,MAX_API_KEY=512;
    private static final List<String> FIELDS=List.of("provider","apiBase","modelName","apiKey");
    private static final Set<PosixFilePermission> OWNER_ONLY=Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE);
    private static volatile ModelConfigStore shared;

    /** 一次有效配置快照；apiKey 只在进程内使用，不通过 view() 外泄。 */
    public record Settings(String provider,String apiBase,String modelName,String apiKey){}

    private final Path path;
    private final Function<String,String> environment;
    private Settings settings;
    private boolean fileLoaded;
    private boolean envProvided;

    public ModelConfigStore(){this(defaultPath(System::getenv),System::getenv);}
    public ModelConfigStore(Path path){this(path,System::getenv);}
    ModelConfigStore(Path path,Function<String,String> environment){
        this.path=Objects.requireNonNull(path,"path").toAbsolutePath();
        this.environment=environment==null?name->null:environment;
        reload();
    }
    /** 进程级共享实例：/config 保存成功后，下一次 CreditAgents.run 立即读到新配置。 */
    public static ModelConfigStore shared(){
        ModelConfigStore instance=shared;
        if(instance==null){
            synchronized(ModelConfigStore.class){
                instance=shared;
                if(instance==null)shared=instance=new ModelConfigStore();
            }
        }
        return instance;
    }
    public synchronized Settings settings(){return settings;}
    public synchronized Settings save(String provider,String apiBase,String modelName,String apiKey){return save(provider,apiBase,modelName,apiKey,false);}
    /** apiKey 为空 = 保留已保存的 key；clearApiKey=true = 清空；校验失败抛 IllegalArgumentException。 */
    public synchronized Settings save(String provider,String apiBase,String modelName,String apiKey,boolean clearApiKey){
        Settings requested=new Settings(trim(provider),trim(apiBase),trim(modelName),trim(apiKey));
        validate(requested);
        String effectiveApiKey=clearApiKey?"":requested.apiKey().isEmpty()?settings.apiKey():requested.apiKey();
        Settings saved=new Settings(requested.provider(),requested.apiBase(),requested.modelName(),effectiveApiKey);
        write(saved);
        settings=saved;
        fileLoaded=true;
        return saved;
    }
    /** 前端可见的脱敏视图：provider/apiBase/modelName/hasApiKey/apiKeyMasked/source。 */
    public synchronized Map<String,Object> view(){
        Map<String,Object> view=new LinkedHashMap<>();
        view.put("provider",settings.provider());
        view.put("apiBase",settings.apiBase());
        view.put("modelName",settings.modelName());
        view.put("hasApiKey",!settings.apiKey().isEmpty());
        view.put("apiKeyMasked",mask(settings.apiKey()));
        view.put("source",fileLoaded?"file":envProvided?"env":"none");
        return view;
    }
    private void reload(){
        Map<String,String> file=readFile();
        fileLoaded=file!=null;
        String envProvider=trimToNull(environment.apply(ENV_PROVIDER));
        String envApiBase=trimToNull(environment.apply(ENV_API_BASE));
        String envModelName=trimToNull(environment.apply(ENV_MODEL_NAME));
        String envApiKey=trimToNull(environment.apply(ENV_API_KEY));
        envProvided=envProvider!=null||envApiBase!=null||envModelName!=null||envApiKey!=null;
        String fileProvider=file==null?null:trimToNull(file.get("provider"));
        String fileApiBase=file==null?null:trimToNull(file.get("apiBase"));
        String fileModelName=file==null?null:trimToNull(file.get("modelName"));
        String fileApiKey=file==null?null:file.get("apiKey");
        settings=new Settings(
                firstNonNull(fileProvider,envProvider,DEFAULT_PROVIDER),
                firstNonNull(fileApiBase,envApiBase,DEFAULT_API_BASE),
                firstNonNull(fileModelName,envModelName,DEFAULT_MODEL_NAME),
                fileApiKey!=null?trim(fileApiKey):envApiKey==null?"":envApiKey);
    }
    private Map<String,String> readFile(){
        if(!Files.isRegularFile(path))return null;
        try{
            Map<?,?> parsed=JSON.readValue(Files.readString(path,StandardCharsets.UTF_8),Map.class);
            Map<String,String> values=new HashMap<>();
            for(String field:FIELDS){
                Object value=parsed.get(field);
                if(value!=null)values.put(field,String.valueOf(value));
            }
            return values;
        }
        catch(IOException|RuntimeException ignored){
            return null;
        }
    }
    private void write(Settings saved){
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("provider",saved.provider());
        data.put("apiBase",saved.apiBase());
        data.put("modelName",saved.modelName());
        data.put("apiKey",saved.apiKey());
        try{
            Path parent=path.getParent();
            if(parent!=null)Files.createDirectories(parent);
            Path temporary=Files.createTempFile(parent,"sample27-model-config-",".tmp");
            try{
                Files.writeString(temporary,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(data)+"\n",StandardCharsets.UTF_8);
                restrictPermissions(temporary);
                try{
                    Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                }
                catch(AtomicMoveNotSupportedException ignored){
                    Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING);
                }
                restrictPermissions(path);
            }
            finally{
                Files.deleteIfExists(temporary);
            }
        }
        catch(IOException e){
            String reason=e.getMessage()==null||e.getMessage().isBlank()?e.getClass().getSimpleName():e.getMessage();
            throw new IllegalStateException("模型配置保存失败："+reason+"（配置路径："+path+"）",e);
        }
    }
    private static void restrictPermissions(Path file){
        try{
            Files.setPosixFilePermissions(file,OWNER_ONLY);
        }
        catch(UnsupportedOperationException|IOException ignored){
            // 非 POSIX 文件系统：尽力而为，不阻断保存。
        }
    }
    private static void validate(Settings requested){
        if(requested.provider().isEmpty())throw new IllegalArgumentException("Provider 不能为空");
        if(requested.provider().length()>MAX_PROVIDER)throw new IllegalArgumentException("Provider 长度不能超过 "+MAX_PROVIDER+" 个字符");
        if(requested.modelName().isEmpty())throw new IllegalArgumentException("模型名称不能为空");
        if(requested.modelName().length()>MAX_MODEL_NAME)throw new IllegalArgumentException("模型名称长度不能超过 "+MAX_MODEL_NAME+" 个字符");
        String apiBase=requested.apiBase();
        if(!apiBase.startsWith("http://")&&!apiBase.startsWith("https://"))throw new IllegalArgumentException("模型服务地址必须以 http:// 或 https:// 开头");
        if(apiBase.length()>MAX_API_BASE)throw new IllegalArgumentException("模型服务地址长度不能超过 "+MAX_API_BASE+" 个字符");
        if(requested.apiKey().length()>MAX_API_KEY)throw new IllegalArgumentException("API Key 长度不能超过 "+MAX_API_KEY+" 个字符");
    }
    private static String mask(String apiKey){
        if(apiKey==null||apiKey.isEmpty())return "";
        return apiKey.length()<8?"****":"****"+apiKey.substring(apiKey.length()-4);
    }
    private static String trim(String value){return value==null?"":value.trim();}
    private static String trimToNull(String value){String trimmed=trim(value);return trimmed.isEmpty()?null:trimmed;}
    private static String firstNonNull(String first,String second,String fallback){return first!=null?first:second!=null?second:fallback;}
    private static Path defaultPath(Function<String,String> environment){
        String custom=trimToNull(environment.apply(ENV_FILE));
        if(custom!=null)return Path.of(custom);
        // 默认写在「服务启动目录」下（样例即 java/），避免 home 目录在容器/沙箱/只读环境中不可写；
        // 该文件已加入 .gitignore，不会把本地密钥提交到仓库。
        Path local=Path.of(System.getProperty("user.dir", "."), ".sample27-model-config.json").toAbsolutePath();
        Path parent=local.getParent();
        if(parent!=null&&Files.isWritable(parent))return local;
        // 启动目录不可写时退回 home（多数本机开发环境可用）。
        return Path.of(System.getProperty("user.home"),".openjiuwen-fin-sample","sample27-model-config.json");
    }
}
