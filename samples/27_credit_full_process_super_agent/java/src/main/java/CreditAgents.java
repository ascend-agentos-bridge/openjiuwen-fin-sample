import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.multiagent.schema.TeamCard;
import com.openjiuwen.core.multiagent.teams.hierarchical_tools.HierarchicalToolsTeam;
import com.openjiuwen.core.multiagent.teams.hierarchical_tools.HierarchicalToolsTeamConfig;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.base.TagMatchStrategy;
import com.openjiuwen.core.session.AgentGroupSession;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.rail.AgentCallback;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentCallbackEvent;
import com.openjiuwen.core.singleagent.rail.InvokeInputs;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
/** 样例27：官方 Java SDK 0.1.17，真实模型驱动的信贷任务委派。 */
public final class CreditAgents {
    private static final ThreadLocal<String> RUN_SUFFIX=new ThreadLocal<>();
    private static final List<String> LOGICAL_IDS=List.of("credit_master","review_master","intake_agent","risk_agent","pricing_agent","execution_master","contract_agent","disbursement_agent","service_agent","postloan_agent");
    /** 静态父子关系表，与下方 team.addAgent 的委派关系一致；null 表示团队根节点。 */
    private static final Map<String,String> PARENTS=new HashMap<>();
    static{
        PARENTS.put("credit_master",null);
        for(String logicalId:List.of("review_master","execution_master","service_agent","postloan_agent"))PARENTS.put(logicalId,"credit_master");
        for(String logicalId:List.of("intake_agent","risk_agent","pricing_agent"))PARENTS.put(logicalId,"review_master");
        for(String logicalId:List.of("contract_agent","disbursement_agent"))PARENTS.put(logicalId,"execution_master");
    }
    private static final int TASK_LIMIT=240,OUTPUT_LIMIT=500,RESULT_LIMIT=240;
    static Object run(String query, CreditDomainService domain, Consumer<Map<String,Object>> events) {
        ModelConfigStore.Settings settings=ModelConfigStore.shared().settings();
        if(settings.apiKey().isBlank())throw new IllegalStateException("尚未配置模型 API Key，请点击右上角「模型设置」填写后重试");
        String suffix=UUID.randomUUID().toString().replace("-", "").substring(0,12);
        RUN_SUFFIX.set(suffix);
        ReActAgent master=createAgent("credit_master", "超级智能体", "你是银行信贷任务调度主管。优先按当前业务阶段路由：客户要求准备合同、签约、继续放款或已审批后的办理时，必须先调用service_agent查询申请当前状态；状态批准或合同待签署则直接委派execution_master准备合同，状态已签署并授权放款则直接委派execution_master执行放款。这些续办请求严禁重新调用review_master、intake_agent、risk_agent或pricing_agent，不重新提交计划。只有新申请明确要求审查才进入review_master。你的工作是选择工具并汇总真实工具结果，禁止凭自身知识回答任何产品利率、额度或进度。对于产品咨询（即使没有客户编号）必须立即调用service_agent工具，query传完整问题，不需要追问客户资料；拿到结果后只忠实转述。对于进度查询同样必须调用service_agent。对于申请办理信息不足时才追问客户编号、金额、期限、用途，不可假设。全流程申请先调用submit_plan工具保存步骤列表steps=[受理,审查,审批,签约,放款,贷后]并展示计划，然后调用review_master审查，审查通过后才能调用execution_master，客户未明确授权签约放款时只能说明下一步。贷后问题调用postloan_agent。所有结论依据工具结果，不可编造。演示数据不是真实银行交易。",settings);
        ReActAgent review=createAgent("review_master","贷前审查团队主管", "你负责受理和审查。必须依次调用intake_agent和risk_agent，缺件或拒绝立即停止；通过后调用pricing_agent。汇总各专员依据和阶段计划，不能自己执行放款。",settings);
        ReActAgent intake=createAgent("intake_agent","资料受理专员","调用check_documents检查客户资料。客户编号缺失先询问。",settings);
        ReActAgent risk=createAgent("risk_agent","征信反欺诈专员","调用check_risk核查征信和反欺诈，忠实保留结果。",settings);
        ReActAgent pricing=createAgent("pricing_agent","额度定价专员","调用quote_loan计算额度利率月供，必须有客户编号、整数元金额、整数月期限。",settings);
        ReActAgent execution=createAgent("execution_master","签约放款团队主管","根据客户明确指令和当前状态办理。若已签署并授权放款，直接调用disbursement_agent，不重新准备合同。若仅批准或客户要求准备合同，调用contract_agent；只有客户明确确认签约和放款且业务审批通过才调用disbursement_agent。不能替客户签字或冒充银行审批人员。",settings);
        ReActAgent contract=createAgent("contract_agent","合同专员","调用prepare_contract准备合同，必须引用客户编号。",settings);
        ReActAgent payment=createAgent("disbursement_agent","放款专员","调用disburse执行演示放款，拒绝时如实报告，不重试绕过规则。",settings);
        ReActAgent service=createAgent("service_agent","独立信贷客服：所有贷款产品、利率、申请进度咨询必须委派此Agent，包括不含客户编号的一般产品咨询","产品咨询调用query_product获取规则说明；查询进度必须调用query_application。不要启动审查团队。",settings);
        ReActAgent post=createAgent("postloan_agent","贷后管理专员","调用query_repayment查看放款和还款状态，没有放款不能编造还款计划。",settings);
        List<ReActAgent> agents=List.of(master,review,intake,risk,pricing,execution,contract,payment,service,post);
        for(ReActAgent a:agents)registerCallbacks(a,events,suffix);
        java.util.List<String> toolIds=new java.util.ArrayList<>();
        for(var entry:Map.of("submit_plan",master,"check_documents",intake,"check_risk",risk,"quote_loan",pricing,"prepare_contract",contract,"disburse",payment,"query_product",service,"query_application",service,"query_repayment",post).entrySet()){
            String name=entry.getKey();
            ReActAgent a=entry.getValue();
            LocalFunction tool=new LocalFunction(ToolCard.builder().id("sample27."+name+suffix).name(name).description("虚构信贷业务服务："+name+"，customer_id如C1001；amount为整数元，months为整数月").inputParams(Map.of("type","object","properties",Map.of("customer_id",Map.of("type","string"),"amount",Map.of("type","integer"),"months",Map.of("type","integer"),"steps",Map.of("type","array","items",Map.of("type","string"))),"required",name.equals("query_product")?List.of():List.of("customer_id"))).build(),in->domain.execute(name,in));
            toolIds.add(tool.getCard().getId());
            Runner.resourceMgr().addTool(tool,a.getCard().getId());
            a.getAbilityManager().add(tool.getCard());
        }
        TeamCard card=new TeamCard();
        card.setId("credit_team_"+suffix);
        card.setName("信贷全流程");
        var config=new HierarchicalToolsTeamConfig(master.getCard());
        config.setMessageTimeout(600);
        var team=new HierarchicalToolsTeam(card,config);
        team.addAgent(master.getCard(),ignored->master);
        for(ReActAgent a:List.of(review,execution,service,post))team.addAgent(a.getCard(),ignored->a,master.getCard().getId());
        for(ReActAgent a:List.of(intake,risk,pricing))team.addAgent(a.getCard(),ignored->a,review.getCard().getId());
        for(ReActAgent a:List.of(contract,payment))team.addAgent(a.getCard(),ignored->a,execution.getCard().getId());
        AgentGroupSession session=new AgentGroupSession("credit-"+suffix);
        session.setTeamId(card.getId());
        try{
            Object result=team.invoke(Map.of("query",query),session).toCompletableFuture().join();
            while(result instanceof java.util.concurrent.CompletionStage<?> stage)result=stage.toCompletableFuture().join();
            return result;
        }
        finally{
            Runner.release(session.getSessionId());
            for(String toolId:toolIds)Runner.resourceMgr().removeTool(toolId);
            for(ReActAgent agent:agents){
                Runner.resourceMgr().removeTool(agent.getCard().getId());
                Runner.resourceMgr().removeAgent(agent.getCard().getId());
                agent.close();
            }
            RUN_SUFFIX.remove();
        }
    }
    /** 注册官方 0.1.17 生命周期回调；只发出展示事件，不修改业务、模型行为。 */
    private static void registerCallbacks(ReActAgent agent,Consumer<Map<String,Object>> events,String suffix){
        String id=agent.getCard().getName();
        String parent=PARENTS.get(id);
        on(agent,AgentCallbackEvent.BEFORE_INVOKE,c->{
            events.accept(agentStateEvent(id,"working"));
            events.accept(agentStartEvent(id,parent,taskText(invokeInputs(c))));
            return done();
        });
        on(agent,AgentCallbackEvent.AFTER_INVOKE,c->{
            Map<String,Object> result=invokeResult(c);
            boolean ok=c.getException()==null&&!isErrorResult(result);
            events.accept(agentStateEvent(id,ok?"completed":"error"));
            events.accept(agentEndEvent(id,parent,ok,outputText(result)));
            return done();
        });
        on(agent,AgentCallbackEvent.BEFORE_TOOL_CALL,c->{
            ToolCallInputs input=toolCallInputs(c);
            if(input!=null&&!isDelegationTool(input,suffix))events.accept(toolStartEvent(id,toolName(input,suffix),input.getToolArgs()));
            return done();
        });
        on(agent,AgentCallbackEvent.AFTER_TOOL_CALL,c->{
            ToolCallInputs input=toolCallInputs(c);
            if(input!=null&&!isDelegationTool(input,suffix))events.accept(toolEndEvent(id,toolName(input,suffix),input.getToolResult()));
            return done();
        });
        on(agent,AgentCallbackEvent.ON_MODEL_EXCEPTION,c->{
            events.accept(collabErrorEvent(id,"模型调用异常："+exceptionText(c.getException())));
            return done();
        });
        on(agent,AgentCallbackEvent.ON_TOOL_EXCEPTION,c->{
            ToolCallInputs input=toolCallInputs(c);
            String prefix=input==null?"工具调用异常：":"工具 "+toolName(input,suffix)+" 调用异常：";
            events.accept(collabErrorEvent(id,prefix+exceptionText(c.getException())));
            return done();
        });
    }
    private static void on(ReActAgent agent,AgentCallbackEvent event,AgentCallback callback){
        agent.getAgentCallbackManager().registerCallback(event,callback,0);
    }
    private static CompletableFuture<Void> done(){return CompletableFuture.completedFuture(null);}
    private static InvokeInputs invokeInputs(AgentCallbackContext context){return context.getInputs() instanceof InvokeInputs inputs?inputs:null;}
    private static Map<String,Object> invokeResult(AgentCallbackContext context){InvokeInputs inputs=invokeInputs(context);return inputs==null?null:inputs.getResult();}
    private static ToolCallInputs toolCallInputs(AgentCallbackContext context){return context.getInputs() instanceof ToolCallInputs inputs?inputs:null;}
    private static String toolName(ToolCallInputs inputs,String suffix){return inputs.getToolName().replace("_"+suffix,"");}
    /** 层级团队把「调用子智能体」实现成工具；这类委派工具不是业务工具，前端已有 handoff 气泡，不再重复展示。 */
    private static boolean isDelegationTool(ToolCallInputs inputs,String suffix){return LOGICAL_IDS.contains(toolName(inputs,suffix));}
    private static String taskText(InvokeInputs inputs){
        String text=inputs==null||inputs.getQuery()==null?null:String.valueOf(inputs.getQuery());
        return text==null||text.isBlank()?"（无任务文本）":text;
    }
    private static String outputText(Map<String,Object> result){
        Object output=result==null?null:result.get("output");
        String text=output!=null?String.valueOf(output):(result==null?null:String.valueOf(result));
        return text==null||text.isBlank()?"（无文本输出）":text;
    }
    private static String resultText(Object result){
        String text=result==null?null:String.valueOf(result);
        return truncate(text==null||text.isBlank()?"（无结果）":text,RESULT_LIMIT);
    }
    private static boolean isErrorResult(Object result){return result instanceof Map<?,?> map&&"error".equals(map.get("result_type"));}
    private static String exceptionText(Exception exception){
        if(exception==null)return "未知错误";
        String message=exception.getMessage();
        return message==null||message.isBlank()?exception.getClass().getSimpleName():message;
    }
    private static Map<String,Object> event(String type){Map<String,Object> event=new LinkedHashMap<>();event.put("type",type);return event;}
    private static Map<String,Object> collab(String ev){Map<String,Object> event=event("collab");event.put("ev",ev);return event;}
    static Map<String,Object> agentStateEvent(String agent,String state){Map<String,Object> event=event("agent");event.put("agent",agent);event.put("state",state);return event;}
    static Map<String,Object> agentStartEvent(String agent,String parent,String task){Map<String,Object> event=collab("agent_start");event.put("agent",agent);event.put("parent",parent);event.put("task",truncate(task,TASK_LIMIT));return event;}
    static Map<String,Object> agentEndEvent(String agent,String parent,boolean ok,String output){Map<String,Object> event=collab("agent_end");event.put("agent",agent);event.put("parent",parent);event.put("ok",ok);event.put("output",truncate(output,OUTPUT_LIMIT));return event;}
    static Map<String,Object> toolStartEvent(String agent,String tool,Object args){Map<String,Object> event=collab("tool_start");event.put("agent",agent);event.put("tool",tool);if(args!=null)event.put("args",args);return event;}
    static Map<String,Object> toolEndEvent(String agent,String tool,Object result){
        Map<String,Object> event=collab("tool_end");event.put("agent",agent);event.put("tool",tool);event.put("ok",result!=null&&!isErrorResult(result));event.put("result",resultText(result));
        if("submit_plan".equals(tool)&&result instanceof Map<?,?> map&&map.get("steps") instanceof List<?> steps)event.put("steps",steps);
        return event;
    }
    private static Map<String,Object> collabErrorEvent(String agent,String message){Map<String,Object> event=collab("error");event.put("agent",agent);event.put("message",message);return event;}
    static String truncate(String text,int limit){
        if(text==null)return "";
        return text.length()<=limit?text:text.substring(0,Math.max(0,limit-1))+"…";
    }
    private static ReActAgent createAgent(String id, String description, String prompt, ModelConfigStore.Settings settings) {
        String actualId=id+"_"+RUN_SUFFIX.get();
        for(String logicalId:LOGICAL_IDS)prompt=prompt.replace(logicalId,logicalId+"_"+RUN_SUFFIX.get());
        prompt+="\n严格依据工具事实，不得增加工具未报告的缺件、风险、利率或客户事实。";
        AgentCard card = AgentCard.builder().id(actualId).name(id).description(description) .inputParams(schema("query", "需要处理的完整任务，产品咨询无需客户编号；业务办理提供客户编号与已知信息")).build();
        ReActAgent agent = new ReActAgent(card);
        agent.configure(ReActAgentConfig.builder() .modelName(settings.modelName()).modelClientConfig(clientConfig(settings)) .modelConfigObj(ModelRequestConfig.builder().modelName(settings.modelName()) .temperature(0.0).topP(1.0).maxTokens(2048).extraFields(Map.of("thinking", Map.of("type", "disabled"))).build()) .promptTemplate(List.of(Map.of("role", "system", "content", prompt))) .maxIterations(12).maxParallelToolCalls(1).build());
        return agent;
    }
    private static Map<String, Object> schema(String field, String description) {
        return Map.of("type", "object", "properties", Map.of(field, Map.of("type", "string", "description", description)), "required", List.of(field));
    }
    /** 每次 run 按当前保存的配置构造模型客户端：保存后下一次问答立即生效。 */
    private static ModelClientConfig clientConfig(ModelConfigStore.Settings settings) {
        return ModelClientConfig.builder() .clientProvider(settings.provider()) .apiBase(settings.apiBase()).apiKey(settings.apiKey()) .timeout(600).maxRetries(1).build();
    }
}
