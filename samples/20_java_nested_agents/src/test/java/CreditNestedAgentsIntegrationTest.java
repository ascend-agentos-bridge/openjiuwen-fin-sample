import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.multiagent.teams.hierarchical_tools.HierarchicalToolsTeam;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.base.TagMatchStrategy;
import com.openjiuwen.core.session.AgentGroupSessionApi;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.rail.AgentCallbackEvent;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Acceptance checks against the real configured model, enabled with -Preal-model. */
@Tag("integration")
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class CreditNestedAgentsIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AgentGroupSessionApi session = new AgentGroupSessionApi("credit-review-test-" + UUID.randomUUID());
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Integer> modelCalls = new ConcurrentHashMap<>();
    private final Map<String, Object> toolArguments = new ConcurrentHashMap<>();
    private final Map<String, Object> toolResults = new ConcurrentHashMap<>();
    private LocalFunction queryCredit;

    @AfterEach
    void tearDown() {
        if (queryCredit != null) {
            Runner.resourceMgr().removeTool(queryCredit.getCard().getId(), "credit_checker", TagMatchStrategy.ALL, true);
        }
        Runner.release(session.getSessionId());
        Runner.stop();
    }

    @Test
    void delegatesThroughAllThreeAgentsAndReturnsTheToolsReportReference() throws Exception {
        ReActAgent master = CreditNestedAgents.createCreditMaster();
        ReActAgent officer = CreditNestedAgents.createRiskOfficer();
        ReActAgent checker = CreditNestedAgents.createCreditChecker();
        List<ReActAgent> agents = List.of(master, officer, checker);
        agents.forEach(this::observe);
        queryCredit = CreditNestedAgents.createCreditQueryTool();
        HierarchicalToolsTeam team = CreditNestedAgents.createTeam(master, officer, checker, queryCredit, session);

        Object result = team.invoke(Map.of("query",
                "请对客户 C1001 做贷前征信核查，给出简短审查建议并注明报告编号。"), session);

        Map<String, Integer> orderedCounts = new LinkedHashMap<>();
        agents.forEach(agent -> orderedCounts.put(agent.getCard().getId(),
                modelCalls.getOrDefault(agent.getCard().getId(), 0)));
        System.out.println("NESTING: " + events);
        System.out.println("CALLS: " + calls);
        System.out.println("MODEL_CALLS: " + orderedCounts);
        System.out.println("TOOL_ARGUMENTS: " + toolArguments);
        System.out.println("RESULT: " + result);

        assertEquals(List.of("enter:credit_master", "enter:risk_officer", "enter:credit_checker",
                "exit:credit_checker", "exit:risk_officer", "exit:credit_master"), events);
        assertEquals(List.of("credit_master -> risk_officer", "risk_officer -> credit_checker",
                "credit_checker -> query_credit_record"), calls);
        orderedCounts.forEach((id, count) -> assertTrue(count >= 2,
                id + " must call the real model before and after its tool"));
        assertDelegatedCustomer("credit_master -> risk_officer");
        assertDelegatedCustomer("risk_officer -> credit_checker");
        assertEquals(Map.of("customer_id", "C1001"),
                argumentsFor("credit_checker -> query_credit_record"));

        Map<?, ?> record = assertInstanceOf(Map.class, toolResults.get("credit_checker -> query_credit_record"));
        assertEquals("C1001", record.get("customer_id"));
        String reportId = assertInstanceOf(String.class, record.get("report_id"));
        assertTrue(reportId.matches("CR-[0-9a-f]{8}"));
        for (String edge : List.of("risk_officer -> credit_checker", "credit_master -> risk_officer")) {
            Map<?, ?> childAnswer = assertInstanceOf(Map.class, toolResults.get(edge));
            assertEquals("answer", childAnswer.get("result_type"));
            assertTrue(String.valueOf(childAnswer.get("output")).contains(reportId), edge + " lost the report id");
        }
        Map<?, ?> answer = assertInstanceOf(Map.class, result);
        assertEquals("answer", answer.get("result_type"));
        assertTrue(String.valueOf(answer.get("output")).contains(reportId), "The master lost the generated report id");
    }

    private void observe(ReActAgent agent) {
        String id = agent.getCard().getId();
        agent.getAgentCallbackManager().registerCallback(AgentCallbackEvent.BEFORE_INVOKE,
                ctx -> events.add("enter:" + id));
        agent.getAgentCallbackManager().registerCallback(AgentCallbackEvent.AFTER_INVOKE,
                ctx -> events.add("exit:" + id));
        agent.getAgentCallbackManager().registerCallback(AgentCallbackEvent.AFTER_MODEL_CALL,
                ctx -> modelCalls.merge(id, 1, Integer::sum));
        agent.getAgentCallbackManager().registerCallback(AgentCallbackEvent.BEFORE_TOOL_CALL, ctx -> {
            if (ctx.getInputs() instanceof ToolCallInputs input) {
                String edge = id + " -> " + input.getToolName();
                calls.add(edge);
                toolArguments.put(edge, input.getToolArgs());
            }
        });
        agent.getAgentCallbackManager().registerCallback(AgentCallbackEvent.AFTER_TOOL_CALL, ctx -> {
            if (ctx.getInputs() instanceof ToolCallInputs input && input.getToolResult() != null) {
                toolResults.put(id + " -> " + input.getToolName(), input.getToolResult());
            }
        });
    }

    private void assertDelegatedCustomer(String edge) throws Exception {
        Map<?, ?> arguments = argumentsFor(edge);
        String query = assertInstanceOf(String.class, arguments.get("query"));
        assertTrue(query.contains("C1001"), edge + " lost the customer id");
    }

    private Map<?, ?> argumentsFor(String edge) throws Exception {
        String json = assertInstanceOf(String.class, toolArguments.get(edge));
        return MAPPER.readValue(json, Map.class);
    }
}
