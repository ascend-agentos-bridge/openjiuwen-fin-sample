import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 事件构造纯函数单测：不调用模型、不进入任何业务流程。 */
class CreditAgentsEventTest {
    private static final List<String> STEPS=List.of("受理","审查","审批","签约","放款","贷后");

    @Test void submitPlanToolEndCarriesSixSteps() {
        Map<String,Object> event=CreditAgents.toolEndEvent("credit_master","submit_plan",Map.of("status","计划校验通过","steps",STEPS));
        assertEquals("collab",event.get("type"));
        assertEquals("tool_end",event.get("ev"));
        assertEquals("credit_master",event.get("agent"));
        assertEquals("submit_plan",event.get("tool"));
        assertEquals(Boolean.TRUE,event.get("ok"));
        assertEquals(STEPS,event.get("steps"));
    }

    @Test void otherToolEndHasNoSteps() {
        Map<String,Object> event=CreditAgents.toolEndEvent("intake_agent","check_documents",Map.of("status","资料齐全"));
        assertFalse(event.containsKey("steps"));
        assertEquals(Boolean.TRUE,event.get("ok"));
        assertEquals("{status=资料齐全}",event.get("result"));
    }

    @Test void failedToolResultIsNotOk() {
        Map<String,Object> event=CreditAgents.toolEndEvent("risk_agent","check_risk",Map.of("result_type","error","output","反欺诈服务不可用"));
        assertEquals(Boolean.FALSE,event.get("ok"));
    }

    @Test void longTextIsTruncatedBeforeEmit() {
        Map<String,Object> start=CreditAgents.agentStartEvent("credit_master",null,"客".repeat(400));
        assertEquals("collab",start.get("type"));
        assertEquals("agent_start",start.get("ev"));
        assertNull(start.get("parent"));
        assertTrue(((String)start.get("task")).length()<=240,start.get("task").toString());
        Map<String,Object> end=CreditAgents.agentEndEvent("credit_master",null,true,"答".repeat(900));
        assertEquals("agent_end",end.get("ev"));
        assertTrue(((String)end.get("output")).length()<=500,end.get("output").toString());
        Map<String,Object> toolEnd=CreditAgents.toolEndEvent("risk_agent","check_risk","结".repeat(900));
        assertTrue(((String)toolEnd.get("result")).length()<=240,toolEnd.get("result").toString());
    }

    @Test void agentEventsKeepStateContract() {
        Map<String,Object> working=CreditAgents.agentStateEvent("credit_master","working");
        assertEquals("agent",working.get("type"));
        assertEquals("working",working.get("state"));
        Map<String,Object> end=CreditAgents.agentEndEvent("review_master","credit_master",false,"（无文本输出）");
        assertEquals("credit_master",end.get("parent"));
        assertEquals(Boolean.FALSE,end.get("ok"));
    }
}
