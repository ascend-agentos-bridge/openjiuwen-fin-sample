import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实模型验收；脚本化输入仅位于测试，不进入业务入口。 */
@Tag("integration")
class CreditModelIntegrationTest {
    private Object run(String query, CreditDomainService domain, Set<String> active) {
        assertNotNull(System.getenv("API_KEY"), "真实模型测试必须配置 API_KEY");
        Object result = CreditAgents.run(query, domain, event -> {
            if ("agent".equals(event.get("type"))) active.add(String.valueOf(event.get("agent")));
        });
        assertNotNull(result);
        assertFalse(result instanceof Map<?, ?> map && "error".equals(map.get("result_type")), String.valueOf(result));
        System.out.println("输入: " + query);
        System.out.println("激活: " + active);
        System.out.println("结果: " + result);
        return result;
    }

    @Test void realConsultationActivatesSingleAgent() {
        Set<String> active = new HashSet<>();
        run("请仅解释经营贷产品利率，不要创建申请。", new CreditDomainService(), active);
        assertTrue(active.contains("credit_master"));
        assertTrue(active.contains("service_agent"));
        assertFalse(active.contains("review_master"));
    }

    @Test void realFuzzyRequestAsksBeforeReview() {
        Set<String> active = new HashSet<>();
        Object result = run("我最近想买一批设备，手头资金有点紧，看看你们能不能帮忙。", new CreditDomainService(), active);
        assertTrue(active.contains("credit_master"));
        assertFalse(active.contains("review_master"));
        assertFalse(active.contains("execution_master"));
        String text = String.valueOf(result);
        assertTrue(text.contains("金额") || text.contains("多少") || text.contains("资金"), text);
    }

    @Test void realCreditLifecycleHonorsOperatorDecisions() {
        CreditDomainService domain = new CreditDomainService();
        Map<String, Object> customer = Map.of("customer_id", "C1001");
        Set<String> active = new HashSet<>();
        run("客户C1001申请经营贷800000元、36个月，用于设备采购。请提交全流程计划并完成申请审查，不要准备合同或放款。", domain, active);
        assertTrue(active.containsAll(Set.of("credit_master", "review_master", "intake_agent", "risk_agent", "pricing_agent")), active.toString());
        assertFalse(active.contains("execution_master"));
        assertEquals("待人工审批", domain.execute("query_application", customer).get("status"));

        domain.decision("C1001", "approve");
        active.clear();
        run("客户C1001的800000元36个月设备经营贷已由运行者审批批准，请查询状态并准备合同，不要放款。", domain, active);
        assertTrue(active.contains("execution_master"), active.toString());
        assertTrue(active.contains("contract_agent"), active.toString());
        assertFalse(active.contains("disbursement_agent"));
        assertEquals("合同待签署", domain.execute("query_application", customer).get("status"));

        domain.decision("C1001", "sign");
        active.clear();
        run("客户C1001的800000元36个月经营贷已经运行者批准，并确认合同签署与放款授权。请继续办理虚构放款。", domain, active);
        assertTrue(active.contains("execution_master"), active.toString());
        assertTrue(active.contains("disbursement_agent"), active.toString());
        assertEquals("已放款", domain.execute("query_application", customer).get("status"));

        active.clear();
        run("查询已放款客户C1001的还款安排，请使用贷后管理专员。", domain, active);
        assertTrue(active.contains("postloan_agent"), active.toString());
        assertFalse(active.contains("review_master"));
        Map<String, Object> repayment = domain.execute("query_repayment", customer);
        assertEquals("已放款", repayment.get("status"));
        assertEquals(36, repayment.get("months"));
        assertTrue(((Number) repayment.get("monthly_payment")).doubleValue() > 0);
    }
}
