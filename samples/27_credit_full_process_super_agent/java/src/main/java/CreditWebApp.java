import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
/** 本地演示服务。会话历史及业务状态保存在服务端，重启清空。 */
public final class CreditWebApp {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Conversation> conversations = new ConcurrentHashMap<>();
    static final class Conversation {
        final CreditDomainService domain = new CreditDomainService();
        final List<String> history = new ArrayList<>();
    }
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",8080),0);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        server.setExecutor(pool);
        server.createContext("/", x -> {
            String path=x.getRequestURI().getPath();
            if(!"GET".equals(x.getRequestMethod())){x.sendResponseHeaders(405,-1);x.close();return;}
            String resource=path.equals("/")?"/ui/index.html":path.startsWith("/assets/")&&!path.contains("..")?"/ui"+path:null;
            java.io.InputStream stream=resource==null?null:CreditWebApp.class.getResourceAsStream(resource);
            if(stream==null){x.sendResponseHeaders(404,-1);x.close();return;}
            byte[] page;try(stream){page=stream.readAllBytes();}
            String type=path.endsWith(".js")?"text/javascript":path.endsWith(".css")?"text/css":path.endsWith(".svg")?"image/svg+xml":"text/html";
            x.getResponseHeaders().set("Content-Type",type+"; charset=utf-8");x.getResponseHeaders().set("X-Content-Type-Options","nosniff");x.sendResponseHeaders(200,page.length);x.getResponseBody().write(page);x.close();
        }
        );
        server.createContext("/chat", x -> {
            if (!"POST".equals(x.getRequestMethod())) {
                x.sendResponseHeaders(405,-1); x.close(); return;
            }
            String origin=x.getRequestHeaders().getFirst("Origin"); if(origin!=null&&!origin.equals("http://127.0.0.1:8080")&&!origin.equals("http://localhost:8080")){
                x.sendResponseHeaders(403,-1);x.close();return;
            }
            byte[] bytes=x.getRequestBody().readNBytes(16385); if(bytes.length>16384){
                x.sendResponseHeaders(413,-1);x.close();return;
            }
            String query=new String(bytes,StandardCharsets.UTF_8); String id=x.getRequestHeaders().getFirst("X-Conversation"); if(id==null||!id.matches("[a-zA-Z0-9-]{1,64}")||query.isBlank()){
                x.sendResponseHeaders(400,-1);x.close();return;
            }
            if(!conversations.containsKey(id)&&conversations.size()>=100){
                x.sendResponseHeaders(429,-1);x.close();return;
            }
            Conversation conversation=conversations.computeIfAbsent(id,k->new Conversation()); x.getResponseHeaders().set("Content-Type","application/x-ndjson; charset=utf-8"); x.getResponseHeaders().set("Cache-Control","no-cache"); x.sendResponseHeaders(200,0); java.util.function.BiConsumer<String,String> emit=(agent,state)->{
                synchronized(x){
                    try{
                        x.getResponseBody().write((JSON.writeValueAsString(Map.of("id",agent,"state",state))+"\n").getBytes(StandardCharsets.UTF_8));x.getResponseBody().flush();
                    }
                    catch(Exception e){
                        throw new RuntimeException(e);
                    }
                }
            }
            ; synchronized(CreditAgents.class) {
                try {
                    String action=x.getRequestHeaders().getFirst("X-Decision"); if(action!=null){
                        Map<String,Object> decision=conversation.domain.decision(query.trim(),action); conversation.history.add("运行者确认："+JSON.writeValueAsString(decision)); emit.accept("answer","**"+decision.get("customer_id")+" · "+decision.get("status")+"**\n\n操作已记录，请查看更新后的业务卡片，或继续输入下一步要求。");
                    }
                    else{
                        if(conversation.history.size()>30)throw new IllegalStateException("会话已达到演示上限，请新建会话"); conversation.history.add("客户："+query); Object result=CreditAgents.run(String.join("\n",conversation.history),conversation.domain,emit); if(result instanceof Map<?,?> map&&"error".equals(map.get("result_type")))throw new IllegalStateException(String.valueOf(map.get("output"))); String answer=result instanceof Map<?,?> map?String.valueOf(map.get("output")):String.valueOf(result); conversation.history.add("助手："+answer);emit.accept("answer",answer);
                    }
                    x.getResponseBody().write((JSON.writeValueAsString(CreditSurfaces.event(conversation.domain))+"\n").getBytes(StandardCharsets.UTF_8));x.getResponseBody().flush();
                }
                catch(Exception e){
                    String reason=(x.getRequestHeaders().getFirst("X-Decision")!=null && (e instanceof IllegalStateException || e instanceof IllegalArgumentException))?e.getMessage():"请检查服务端模型配置及业务前置条件";emit.accept("error","处理失败："+reason);
                }
            }
            x.close();
        }
        );
        Runtime.getRuntime().addShutdownHook(new Thread(()->{
            server.stop(0);pool.shutdownNow();com.openjiuwen.core.runner.Runner.stop();
        }
        ));
        server.start();
        System.out.println("信贷对话页面：http://127.0.0.1:8080（虚构业务数据）");
    }
}
