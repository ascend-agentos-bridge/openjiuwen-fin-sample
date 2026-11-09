import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class CreditDomainServiceTest {
    void plan(CreditDomainService s,String id){s.execute("submit_plan",Map.of("customer_id",id,"steps",java.util.List.of("受理","审查","审批","签约","放款","贷后")));}
    Map<String,Object> input(String id) {return Map.of("customer_id",id,"amount",800000,"months",36);}
    @Test void approvalSignatureAndDisbursementAreOrderedAndIdempotent() {
        var service=new CreditDomainService();var in=input("C1001");plan(service,"C1001");
        assertThrows(IllegalStateException.class,()->service.execute("disburse",in));
        service.execute("check_documents",in);service.execute("check_risk",in);
        assertEquals("待人工审批",service.execute("quote_loan",in).get("status"));
        assertThrows(IllegalStateException.class,()->service.execute("quote_loan",Map.of("customer_id","C1001","amount",200000,"months",36)));
        assertThrows(IllegalStateException.class,()->service.execute("prepare_contract",in));
        service.decision("C1001","approve");service.execute("prepare_contract",in);
        assertThrows(IllegalStateException.class,()->service.execute("disburse",in));
        service.decision("C1001","sign");
        assertEquals("已签署并授权放款",service.execute("prepare_contract",in).get("status"));
        assertEquals(service.execute("disburse",in),service.execute("disburse",in));
        assertEquals("已放款",service.execute("query_repayment",in).get("status"));
    }
    @Test void rejectionCannotBeResetByIntake() {
        var s=new CreditDomainService();var in=input("C1002");plan(s,"C1002");s.execute("check_documents",in);s.execute("check_risk",in);
        assertEquals("拒绝",s.execute("check_documents",in).get("status"));
        assertThrows(IllegalStateException.class,()->s.execute("quote_loan",in));
        assertThrows(IllegalStateException.class,()->s.decision("C1002","approve"));
    }
    @Test void missingDocumentsStopReviewAndCasesAreIsolated() {
        var s=new CreditDomainService();var in=input("C1003");plan(s,"C1003");assertEquals("待补件",s.execute("check_documents",in).get("status"));
        assertThrows(IllegalStateException.class,()->s.execute("check_risk",in));
        assertEquals("未申请",new CreditDomainService().execute("query_application",input("C1001")).get("status"));
    }
    @Test void invalidAmountsAreRejected() {
        var s=new CreditDomainService();plan(s,"C1004");s.execute("check_documents",input("C1004"));s.execute("check_risk",input("C1004"));
        assertThrows(IllegalArgumentException.class,()->s.execute("quote_loan",Map.of("customer_id","C1004","amount",Double.NaN,"months",36)));
    }
    @Test void planGateAndSupplementResume(){
        var s=new CreditDomainService();var in=input("C1003");
        assertThrows(IllegalStateException.class,()->s.execute("check_documents",in));
        plan(s,"C1003");assertEquals("待补件",s.execute("check_documents",in).get("status"));
        s.decision("C1003","supplement");assertEquals("资料齐全",s.execute("check_documents",in).get("status"));
        assertEquals("审查通过",s.execute("check_risk",in).get("status"));
    }
}
