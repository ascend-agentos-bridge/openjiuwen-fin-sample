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
import com.openjiuwen.core.singleagent.rail.AgentCallbackEvent;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import java.util.List;
import java.util.Map;
import java.util.UUID;
/** 样例27：官方 Java SDK 0.1.17，真实模型驱动的信贷任务委派。 */
public final class CreditAgents {
    private static final String MODEL = env("MODEL_NAME", "deepseek-flash");
    private static final ModelClientConfig CLIENT = ModelClientConfig.builder() .clientProvider(env("MODEL_PROVIDER", "openai")) .apiBase(env("API_BASE", "https://api.deepseek.com")).apiKey(env("API_KEY", "")) .timeout(600).maxRetries(1).build();
    private static final ThreadLocal<String> RUN_SUFFIX=new ThreadLocal<>();
    private static final List<String> LOGICAL_IDS=List.of("credit_master","review_master","intake_agent","risk_agent","pricing_agent","execution_master","contract_agent","disbursement_agent","service_agent","postloan_agent");
    static Object run(String query, CreditDomainService domain, java.util.function.BiConsumer<String,String> events) {
        String suffix=UUID.randomUUID().toString().replace("-", "").substring(0,12);
        RUN_SUFFIX.set(suffix);
        ReActAgent master=createAgent("credit_master", "超级智能体", "你是银行信贷任务调度主管。优先按当前业务阶段路由：客户要求准备合同、签约、继续放款或已审批后的办理时，必须先调用service_agent查询申请当前状态；状态批准或合同待签署则直接委派execution_master准备合同，状态已签署并授权放款则直接委派execution_master执行放款。这些续办请求严禁重新调用review_master、intake_agent、risk_agent或pricing_agent，不重新提交计划。只有新申请明确要求审查才进入review_master。你的工作是选择工具并汇总真实工具结果，禁止凭自身知识回答任何产品利率、额度或进度。对于产品咨询（即使没有客户编号）必须立即调用service_agent工具，query传完整问题，不需要追问客户资料；拿到结果后只忠实转述。对于进度查询同样必须调用service_agent。对于申请办理信息不足时才追问客户编号、金额、期限、用途，不可假设。全流程申请先调用submit_plan工具保存步骤列表steps=[受理,审查,审批,签约,放款,贷后]并展示计划，然后调用review_master审查，审查通过后才能调用execution_master，客户未明确授权签约放款时只能说明下一步。贷后问题调用postloan_agent。所有结论依据工具结果，不可编造。演示数据不是真实银行交易。");
        ReActAgent review=createAgent("review_master","贷前审查团队主管", "你负责受理和审查。必须依次调用intake_agent和risk_agent，缺件或拒绝立即停止；通过后调用pricing_agent。汇总各专员依据和阶段计划，不能自己执行放款。");
        ReActAgent intake=createAgent("intake_agent","资料受理专员","调用check_documents检查客户资料。客户编号缺失先询问。");
        ReActAgent risk=createAgent("risk_agent","征信反欺诈专员","调用check_risk核查征信和反欺诈，忠实保留结果。");
        ReActAgent pricing=createAgent("pricing_agent","额度定价专员","调用quote_loan计算额度利率月供，必须有客户编号、整数元金额、整数月期限。");
        ReActAgent execution=createAgent("execution_master","签约放款团队主管","根据客户明确指令和当前状态办理。若已签署并授权放款，直接调用disbursement_agent，不重新准备合同。若仅批准或客户要求准备合同，调用contract_agent；只有客户明确确认签约和放款且业务审批通过才调用disbursement_agent。不能替客户签字或冒充银行审批人员。");
        ReActAgent contract=createAgent("contract_agent","合同专员","调用prepare_contract准备合同，必须引用客户编号。");
        ReActAgent payment=createAgent("disbursement_agent","放款专员","调用disburse执行演示放款，拒绝时如实报告，不重试绕过规则。");
        ReActAgent service=createAgent("service_agent","独立信贷客服：所有贷款产品、利率、申请进度咨询必须委派此Agent，包括不含客户编号的一般产品咨询","产品咨询调用query_product获取规则说明；查询进度必须调用query_application。不要启动审查团队。");
        ReActAgent post=createAgent("postloan_agent","贷后管理专员","调用query_repayment查看放款和还款状态，没有放款不能编造还款计划。");
        List<ReActAgent> agents=List.of(master,review,intake,risk,pricing,execution,contract,payment,service,post);
        for(ReActAgent a:agents){
            String id=a.getCard().getName();
            a.getAgentCallbackManager().registerCallback(AgentCallbackEvent.ON_MODEL_EXCEPTION,c->{
                events.accept(id,"error");return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            ,0);
            a.getAgentCallbackManager().registerCallback(AgentCallbackEvent.BEFORE_TOOL_CALL,c->{
                if(c.getInputs() instanceof ToolCallInputs input)events.accept("tool:"+id,input.getToolName().replace("_"+suffix,""));return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            ,0);
            a.getAgentCallbackManager().registerCallback(AgentCallbackEvent.BEFORE_INVOKE,c->{
                events.accept(id,"working");return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            ,0);
            a.getAgentCallbackManager().registerCallback(AgentCallbackEvent.AFTER_INVOKE,c->{
                events.accept(id,c.getException()==null?"completed":"error");return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            ,0);
        }
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
    private static ReActAgent createAgent(String id, String description, String prompt) {
        String actualId=id+"_"+RUN_SUFFIX.get();
        for(String logicalId:LOGICAL_IDS)prompt=prompt.replace(logicalId,logicalId+"_"+RUN_SUFFIX.get());
        prompt+="\n严格依据工具事实，不得增加工具未报告的缺件、风险、利率或客户事实。";
        AgentCard card = AgentCard.builder().id(actualId).name(id).description(description) .inputParams(schema("query", "需要处理的完整任务，产品咨询无需客户编号；业务办理提供客户编号与已知信息")).build();
        ReActAgent agent = new ReActAgent(card);
        agent.configure(ReActAgentConfig.builder() .modelName(MODEL).modelClientConfig(CLIENT) .modelConfigObj(ModelRequestConfig.builder().modelName(MODEL) .temperature(0.0).topP(1.0).maxTokens(2048).extraFields(Map.of("thinking", Map.of("type", "disabled"))).build()) .promptTemplate(List.of(Map.of("role", "system", "content", prompt))) .maxIterations(12).maxParallelToolCalls(1).build());
        return agent;
    }
    private static Map<String, Object> schema(String field, String description) {
        return Map.of("type", "object", "properties", Map.of(field, Map.of("type", "string", "description", description)), "required", List.of(field));
    }
    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
