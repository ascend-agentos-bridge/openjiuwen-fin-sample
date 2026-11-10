import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.schema.*;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.kvcache.KVCacheRuntime;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.session.AgentSession;
import com.openjiuwen.core.singleagent.agents.*;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import java.util.*;

/** 信贷复核：直接使用官方 Agent、模型和缓存生命周期 API。 */
public final class CreditReviewAgentHint {
    static boolean parseSwitch(String value) {
        if (!"true".equals(value) && !"false".equals(value)) throw new IllegalArgumentException("AGENT_HINT_ENABLED 只能为 true/false");
        return Boolean.parseBoolean(value);
    }
    static String creditRecord(String customer) {
        if (!customer.equals("C1001")) throw new IllegalArgumentException("演示客户仅支持 C1001");
        return "客户 C1001，信用评分735，逾期笔数0。虚构演示档案，不构成授信建议。";
    }
    static String env(String name,String fallback){return System.getenv().getOrDefault(name,fallback);}
    public static void main(String[] args) throws Exception {
        boolean enabled=parseSwitch(env("AGENT_HINT_ENABLED","false"));
        String suffix=UUID.randomUUID().toString(), id="credit-review-"+suffix;
        ModelClientConfig client=ModelClientConfig.builder().clientProvider(enabled?"AscendAffinity":"OpenAI")
            .apiBase(env("API_BASE","https://api.deepseek.com"))
            .apiKey(env("API_KEY","" )).maxRetries(0).timeout(120)
            .extraFields(Map.of("extensions",Map.of("kv_cache",Map.of("mode",enabled?"affinity":"none")))).build();
        Model model=new Model(client,ModelRequestConfig.builder().modelName(env("MODEL_NAME","deepseek-flash"))
            .temperature(0.0).topP(1.0).maxTokens(1024).extraFields(Map.of("thinking",Map.of("type","disabled"))).build());
        KVCacheRuntime runtime=enabled?new KVCacheRuntime(()->model):null;
        AgentCard card=new AgentCard(id,id,"信贷复核专员");
        ReActAgent agent=new ReActAgent(card);
        ReActAgentConfig config=ReActAgentConfig.builder().maxIterations(4).streamMaxRetries(0).promptTemplate(List.of(Map.of("role","system","content","你是信贷复核专员。有客户编号时每轮调用一次credit_record读取客户档案，忠实报告客户编号、信用评分和逾期笔数。缺少编号先追问，不得编造。只支持演示客户C1001。"))).build();
        config.setEnableKvCacheAffinity(enabled);
        agent.configure(config);agent.setLlm(model);
        LocalFunction tool=new LocalFunction(ToolCard.builder().id("credit-record-"+suffix).name("credit_record")
            .description("读取虚构客户征信档案")
            .inputParams(Map.of("type","object","properties",Map.of("customer_id",Map.of("type","string")),"required",List.of("customer_id"))).build(), input->creditRecord(String.valueOf(input.get("customer_id"))));
        Runner.resourceMgr().addTool(tool,id);agent.getAbilityManager().add(tool.getCard());
        AgentSession session=new AgentSession(id,new LinkedHashMap<>(),card,null,false,new LinkedHashMap<>(),runtime);
        try(Scanner scanner=new Scanner(System.in)){
            boolean paused=false;
            System.out.println("AgentHint="+enabled+"；输入客户问题；/pause 暂停，/resume 继续，/exit 结束。");
            while(true){System.out.print("你> ");if(!scanner.hasNextLine())break;String query=scanner.nextLine().trim();
                if(query.equals("/exit"))break;
                if(query.equals("/pause")){if(enabled)System.out.println("缓存暂停结果："+session.suspendKvc().join());paused=true;System.out.println("会话已暂停。");continue;}
                if(query.equals("/resume")){paused=false;System.out.println("可以继续提问。");continue;}
                if(paused){System.out.println("请先输入 /resume。");continue;}
                if(!query.isEmpty())System.out.println("Agent> "+Runner.runAgent(agent,Map.of("query",query),session,null));
            }
        }finally{if(enabled){System.out.println("缓存释放结果："+session.releaseKvc().join());runtime.close().join();}Runner.resourceMgr().removeTool(tool.getCard().getId());agent.close();Runner.stop().toCompletableFuture().join();}
    }
}
