import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.multiagent.schema.TeamCard;
import com.openjiuwen.core.multiagent.teams.hierarchical_tools.HierarchicalToolsTeam;
import com.openjiuwen.core.multiagent.teams.hierarchical_tools.HierarchicalToolsTeamConfig;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.session.AgentGroupSessionApi;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.rail.AgentCallbackEvent;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** 样例 20：官方 Java SDK 0.1.16，真实模型驱动的 Master → Sub → Sub。 */
public final class CreditNestedAgents {
    private static final String API_BASE = env("API_BASE", "http://127.0.0.1:1234/v1");
    private static final String MODEL = env("MODEL_NAME", "google/gemma-4-e4b");
    private static final ModelClientConfig CLIENT = ModelClientConfig.builder()
            .clientProvider(env("MODEL_PROVIDER", "openai"))
            .apiBase(API_BASE).apiKey(env("API_KEY", "lm-studio"))
            .timeout(600).maxRetries(1).build();

    public static void main(String[] args) {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        Map<String, Integer> modelCalls = new ConcurrentHashMap<>();
        AtomicReference<String> reportId = new AtomicReference<>();

        ReActAgent master = agent("credit_master", "审批主管，汇总风控专员的贷前审查意见", """
                你是银行审批主管。本例只演示贷前征信核查。
                收到客户请求后，必须先且仅调用一次 risk_officer 工具，把客户编号和完整请求放入 query。
                收到风控专员结果后直接用中文简短汇总，必须原样保留 report_id 报告编号。
                不得自己代替风控专员核查，不得在工具调用前作答。
                """);
        ReActAgent officer = agent("risk_officer", "风控专员；接收客户编号并委派征信核验员", """
                你是银行风控专员。必须先且仅调用一次 credit_checker 工具，query 中带客户编号和核查要求。
                收到征信核验员结果后，给出简短风控建议，必须原样保留 report_id 报告编号与征信事实。
                不得自己编造征信记录，不得在工具调用前作答。
                """);
        ReActAgent checker = agent("credit_checker", "征信核验员；查询客户征信演示记录并解释结果", """
                你是银行征信核验员。必须先且仅调用一次 query_credit_record 工具，customer_id 使用请求中的客户编号。
                收到查询结果后，用中文概括征信事实，必须原样保留 report_id 报告编号。
                这是虚构客户演示数据，不要编造其他记录，不要再次委派。
                """);

        // 只记录官方生命周期事件，不替换模型或 Agent 的执行行为。
        for (ReActAgent agent : List.of(master, officer, checker)) {
            String id = agent.getCard().getId();
            agent.getAgentCallbackManager().registerCallback(AgentCallbackEvent.BEFORE_INVOKE, ctx -> {
                events.add("enter:" + id);
                System.out.println("ENTER " + id);
            });
            agent.getAgentCallbackManager().registerCallback(AgentCallbackEvent.AFTER_INVOKE, ctx -> {
                events.add("exit:" + id);
                System.out.println("EXIT  " + id);
            });
            agent.getAgentCallbackManager().registerCallback(AgentCallbackEvent.AFTER_MODEL_CALL,
                    ctx -> modelCalls.merge(id, 1, Integer::sum));
            agent.getAgentCallbackManager().registerCallback(AgentCallbackEvent.BEFORE_TOOL_CALL, ctx -> {
                if (ctx.getInputs() instanceof ToolCallInputs input) {
                    String call = id + " -> " + input.getToolName();
                    calls.add(call);
                    System.out.println("CALL  " + call);
                }
            });
        }

        // 业务数据是显式的虚构演示记录；随机报告编号只通过工具结果交给模型。
        LocalFunction queryCredit = new LocalFunction(ToolCard.builder()
                .id("sample20.query_credit_record").name("query_credit_record")
                .description("查询虚构演示客户的征信记录，返回报告编号和征信事实")
                .inputParams(schema("customer_id", "客户编号，例如 C1001")).build(), inputs -> {
                    if (!"C1001".equals(inputs.get("customer_id"))) {
                        throw new IllegalArgumentException("演示数据仅包含客户 C1001");
                    }
                    String reference = "CR-" + UUID.randomUUID().toString().substring(0, 8);
                    reportId.set(reference);
                    System.out.println("DATA  customer=C1001 report_id=" + reference);
                    return Map.of("customer_id", "C1001", "report_id", reference,
                            "credit_score", 735, "overdue_count_24m", 0,
                            "data_source", "虚构演示记录，不是真实征信");
                });

        AgentGroupSessionApi session = new AgentGroupSessionApi("credit-review-" + UUID.randomUUID());
        try {
            Runner.resourceMgr().addTool(queryCredit, checker.getCard().getId());
            checker.getAbilityManager().add(queryCredit.getCard());

            TeamCard card = new TeamCard();
            card.setId("sample20_credit_team");
            card.setName("银行贷前三层审查团队");
            session.setTeamId(card.getId());
            HierarchicalToolsTeamConfig config = new HierarchicalToolsTeamConfig(master.getCard());
            config.setMessageTimeout(600);
            HierarchicalToolsTeam team = new HierarchicalToolsTeam(card, config);
            team.addAgent(master.getCard(), () -> master);
            team.addAgent(officer.getCard(), () -> officer, master.getCard().getId());
            team.addAgent(checker.getCard(), () -> checker, officer.getCard().getId());

            System.out.println("SDK: com.openjiuwen:agent-core-java:0.1.16");
            System.out.println("MODEL: " + MODEL);
            Object result = team.invoke(Map.of("query",
                    "请对客户 C1001 做贷前征信核查，给出简短审查建议并注明报告编号。"), session);
            System.out.println("RESULT: " + result);

            verifyNesting(events);
            if (!calls.equals(List.of("credit_master -> risk_officer", "risk_officer -> credit_checker",
                    "credit_checker -> query_credit_record"))) {
                throw new IllegalStateException("实际委派/工具调用链不符: " + calls);
            }
            Map<String, Integer> orderedCounts = new LinkedHashMap<>();
            for (ReActAgent agent : List.of(master, officer, checker)) {
                String id = agent.getCard().getId();
                int count = modelCalls.getOrDefault(id, 0);
                if (count < 1) {
                    throw new IllegalStateException("未观察到该 Agent 成功调用真实模型: " + id);
                }
                orderedCounts.put(id, count);
            }
            if (!(result instanceof Map<?, ?> answer) || !"answer".equals(answer.get("result_type"))
                    || reportId.get() == null || !String.valueOf(answer.get("output")).contains(reportId.get())) {
                throw new IllegalStateException("主管最终答案未收到征信工具本次生成的报告编号: " + result);
            }
            System.out.println("NESTING: " + events);
            System.out.println("MODEL_CALLS: " + orderedCounts);
            System.out.println("SUCCESS: Java 三层 Agent 委派、真实模型调用和报告编号逐级回传验收通过");
        } finally {
            Runner.release(session.getSessionId());
            Runner.stop();
        }
    }

    private static ReActAgent agent(String id, String description, String prompt) {
        AgentCard card = AgentCard.builder().id(id).name(id).description(description)
                .inputParams(schema("query", "需要处理的完整任务，必须包含客户编号")).build();
        ReActAgent agent = new ReActAgent(card);
        agent.configure(ReActAgentConfig.builder()
                .modelName(MODEL).modelClientConfig(CLIENT)
                .modelConfigObj(ModelRequestConfig.builder().modelName(MODEL)
                        .temperature(0.0).topP(1.0).maxTokens(512).build())
                .promptTemplate(List.of(Map.of("role", "system", "content", prompt)))
                .maxIterations(4).maxParallelToolCalls(1).build());
        return agent;
    }

    private static Map<String, Object> schema(String field, String description) {
        return Map.of("type", "object", "properties",
                Map.of(field, Map.of("type", "string", "description", description)), "required", List.of(field));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void verifyNesting(List<String> events) {
        List<String> expected = Arrays.asList(
                "enter:credit_master", "enter:risk_officer", "enter:credit_checker",
                "exit:credit_checker", "exit:risk_officer", "exit:credit_master");
        if (!events.equals(expected)) {
            throw new IllegalStateException("三层嵌套轨迹不符: " + events);
        }
    }
}
