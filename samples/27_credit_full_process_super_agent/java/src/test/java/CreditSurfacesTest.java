import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CreditSurfacesTest {
 @Test void factsAndActionsComeFromDomain() {
  var domain=new CreditDomainService();assertEquals(List.of(),CreditSurfaces.event(domain).get("messages"));assertEquals(List.of(),CreditSurfaces.reply(domain).get("cases"));
  var in=Map.<String,Object>of("customer_id","C1001","amount",800000,"months",36);
  domain.execute("submit_plan",Map.of("customer_id","C1001","steps",List.of("受理","审查","审批","签约","放款","贷后")));
  domain.execute("check_documents",in);domain.execute("check_risk",in);domain.execute("quote_loan",in);
  String before=CreditSurfaces.event(domain).toString();assertTrue(before.contains("approve"));assertTrue(before.contains("¥800,000"),before);assertFalse(before.contains("name=sign"));
  List<?> cases=(List<?>)CreditSurfaces.reply(domain).get("cases");assertEquals(1,cases.size());
  Map<?,?> reply=(Map<?,?>)cases.get(0);assertEquals("C1001",reply.get("customer_id"));assertEquals("待人工审批",reply.get("status"));assertEquals("warn",reply.get("tone"));
  assertEquals("等待审批人员确认（演示角色），批准后可继续准备合同",reply.get("next_step"));
  assertTrue(reply.get("facts").toString().contains("¥800,000"),String.valueOf(reply));
  domain.decision("C1001","approve");domain.execute("prepare_contract",in);
  String after=CreditSurfaces.event(domain).toString();assertTrue(after.contains("name=sign"));assertFalse(after.contains("name=approve"));
  assertEquals("合同待签署",domain.execute("query_application",in).get("status"));
 }
}
