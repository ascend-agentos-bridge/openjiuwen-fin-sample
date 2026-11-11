import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
/** 本地演示服务。会话历史及业务状态保存在服务端，重启清空。 */
public final class CreditWebApp {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Conversation> conversations = new ConcurrentHashMap<>();
    static final class Conversation {
        final CreditDomainService domain = new CreditDomainService();
        final List<String> history = new ArrayList<>();
    }
    /** NDJSON 写出器：所有事件在写行时统一追加 seq（每请求从 1 递增）与毫秒 ts，并同步 flush。 */
    private static final class EventWriter implements Consumer<Map<String,Object>> {
        private final HttpExchange exchange;
        private int seq;
        EventWriter(HttpExchange exchange) {
            this.exchange = exchange;
        }
        @Override public void accept(Map<String,Object> event) {
            synchronized (exchange) {
                try {
                    Map<String,Object> line = new LinkedHashMap<>();
                    line.put("type", event.get("type"));
                    line.put("seq", ++seq);
                    line.put("ts", System.currentTimeMillis());
                    for (Map.Entry<String,Object> entry : event.entrySet()) {
                        if (!line.containsKey(entry.getKey())) line.put(entry.getKey(), entry.getValue());
                    }
                    exchange.getResponseBody().write((JSON.writeValueAsString(line) + "\n").getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
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
            Conversation conversation=conversations.computeIfAbsent(id,k->new Conversation()); x.getResponseHeaders().set("Content-Type","application/x-ndjson; charset=utf-8"); x.getResponseHeaders().set("Cache-Control","no-cache"); x.sendResponseHeaders(200,0);
            EventWriter emit=new EventWriter(x);
            String action=x.getRequestHeaders().getFirst("X-Decision");
            synchronized(CreditAgents.class) {
                try {
                    emit.accept(runStart(action==null?query.trim():"运行者确认："+decisionLabel(action)+"（"+query.trim()+"）"));
                    if(action!=null){
                        Map<String,Object> decision=conversation.domain.decision(query.trim(),action); conversation.history.add("运行者确认："+JSON.writeValueAsString(decision));
                        emit.accept(operator(decision,action));
                        emit.accept(agentCompleted("credit_master"));
                        emit.accept(runEnd("completed"));
                        emit.accept(reply(conversation.domain));
                        emit.accept(answer(operatorAnswer(decision)));
                        emit.accept(a2ui(conversation.domain));
                    }
                    else{
                        if(conversation.history.size()>30)throw new IllegalStateException("会话已达到演示上限，请新建会话"); conversation.history.add("客户："+query);
                        Object result=CreditAgents.run(String.join("\n",conversation.history),conversation.domain,emit); if(result instanceof Map<?,?> map&&"error".equals(map.get("result_type")))throw new IllegalStateException(String.valueOf(map.get("output"))); String text=result instanceof Map<?,?> map?String.valueOf(map.get("output")):String.valueOf(result); conversation.history.add("助手："+text);
                        emit.accept(runEnd("completed"));
                        emit.accept(reply(conversation.domain));
                        emit.accept(answer(text));
                        emit.accept(a2ui(conversation.domain));
                    }
                }
                catch(Exception e){
                    String reason=(e instanceof IllegalStateException || e instanceof IllegalArgumentException)?e.getMessage():"请检查服务端模型配置及业务前置条件"; String message="处理失败："+reason;
                    emit.accept(collabError(message));
                    emit.accept(runEnd("error"));
                    emit.accept(error(message));
                }
            }
            x.close();
        }
        );
        server.createContext("/config", CreditWebApp::handleConfig);
        Runtime.getRuntime().addShutdownHook(new Thread(()->{
            server.stop(0);pool.shutdownNow();com.openjiuwen.core.runner.Runner.stop();
        }
        ));
        server.start();
        System.out.println("信贷对话页面：http://127.0.0.1:8080（虚构业务数据）");
    }
    /** GET/POST /config：只暴露脱敏视图；POST 校验失败 400 {"error":...}，成功返回与 GET 相同结构。 */
    static void handleConfig(HttpExchange x) throws IOException{
        if(!originAllowed(x)){x.sendResponseHeaders(403,-1);x.close();return;}
        String method=x.getRequestMethod();
        if("GET".equals(method)){writeJson(x,200,ModelConfigStore.shared().view());x.close();return;}
        if(!"POST".equals(method)){x.sendResponseHeaders(405,-1);x.close();return;}
        byte[] bytes=x.getRequestBody().readNBytes(8193); if(bytes.length>8192){x.sendResponseHeaders(413,-1);x.close();return;}
        Map<?,?> body;
        try{
            body=JSON.readValue(new String(bytes,StandardCharsets.UTF_8),Map.class);
        }
        catch(Exception e){
            writeJson(x,400,Map.of("error","请求体不是合法 JSON"));x.close();return;
        }
        try{
            Object clear=body.get("clearApiKey");
            boolean clearApiKey=clear instanceof Boolean flag?flag:Boolean.parseBoolean(String.valueOf(clear));
            ModelConfigStore.shared().save(configText(body.get("provider")),configText(body.get("apiBase")),configText(body.get("modelName")),configText(body.get("apiKey")),clearApiKey);
            writeJson(x,200,ModelConfigStore.shared().view());
        }
        catch(IllegalArgumentException e){
            writeJson(x,400,Map.of("error",configMessage(e.getMessage())));
        }
        catch(RuntimeException e){
            String message=e.getMessage();
            if(message==null||message.isBlank())message="模型配置保存失败：服务端写入配置时发生未知错误";
            writeJson(x,500,Map.of("error",message));
        }
        x.close();
    }
    private static Map<String,Object> event(String type){Map<String,Object> event=new LinkedHashMap<>();event.put("type",type);return event;}
    private static Map<String,Object> collab(String ev){Map<String,Object> event=event("collab");event.put("ev",ev);return event;}
    private static Map<String,Object> runStart(String question){Map<String,Object> event=collab("run_start");event.put("question",question);return event;}
    private static Map<String,Object> operator(Map<String,Object> decision,String action){
        Map<String,Object> event=collab("operator");event.put("agent","credit_master");event.put("text","运行者确认"+decisionLabel(action)+" → 状态："+decision.get("status"));return event;
    }
    private static Map<String,Object> runEnd(String status){Map<String,Object> event=collab("run_end");event.put("status",status);return event;}
    /** 运行者确认分支不经过模型，但宿主仍把协调者标记为已完成，避免右侧架构图与协作直播状态不一致。 */
    private static Map<String,Object> agentCompleted(String agent){Map<String,Object> event=event("agent");event.put("agent",agent);event.put("state","completed");return event;}
    private static Map<String,Object> collabError(String message){Map<String,Object> event=collab("error");event.put("agent","credit_master");event.put("message",message);return event;}
    private static Map<String,Object> reply(CreditDomainService domain){Map<String,Object> event=event("reply");event.put("data",CreditSurfaces.reply(domain));return event;}
    private static Map<String,Object> answer(String text){Map<String,Object> event=event("answer");event.put("text",text);return event;}
    private static Map<String,Object> a2ui(CreditDomainService domain){Map<String,Object> event=event("a2ui");event.put("messages",CreditSurfaces.event(domain).get("messages"));return event;}
    private static Map<String,Object> error(String message){Map<String,Object> event=event("error");event.put("message",message);return event;}
    private static boolean originAllowed(HttpExchange x){
        String origin=x.getRequestHeaders().getFirst("Origin");
        return origin==null||origin.equals("http://127.0.0.1:8080")||origin.equals("http://localhost:8080");
    }
    private static void writeJson(HttpExchange x,int status,Object body) throws IOException{
        byte[] bytes=JSON.writeValueAsBytes(body);
        x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        x.getResponseHeaders().set("Cache-Control","no-store");
        x.sendResponseHeaders(status,bytes.length);
        x.getResponseBody().write(bytes);x.getResponseBody().flush();
    }
    private static String configText(Object value){return value==null?"":String.valueOf(value).trim();}
    private static String configMessage(String value){return value==null||value.isBlank()?"参数校验失败":value;}
    private static String operatorAnswer(Map<String,Object> decision){return "**"+decision.get("customer_id")+" · "+decision.get("status")+"**\n\n操作已记录，请查看更新后的业务卡片，或继续输入下一步要求。";}
    private static String decisionLabel(String action){
        return switch(action){case "approve"->"审批批准";case "reject"->"审批拒绝";case "sign"->"签署及放款授权";case "supplement"->"材料补齐";default->action;};
    }
}
