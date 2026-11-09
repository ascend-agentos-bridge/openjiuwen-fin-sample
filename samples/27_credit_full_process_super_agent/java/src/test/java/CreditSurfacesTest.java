import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CreditSurfacesTest {
 @Test void factsAndActionsComeFromDomain() {
  var domain=new CreditDomainService();assertEquals(List.of(),CreditSurfaces.event(domain).get("messages"));
  var in=Map.<String,Object>of("customer_id","C1001","amount",800000,"months",36);
  domain.execute("submit_plan",Map.of("customer_id","C1001","steps",List.of("受理","审查","审批","签约","放款","贷后")));
  domain.execute("check_documents",in);domain.execute("check_risk",in);domain.execute("quote_loan",in);
  String before=CreditSurfaces.event(domain).toString();assertTrue(before.contains("approve"));assertTrue(before.contains("800000"));assertFalse(before.contains("name=sign"));
  domain.decision("C1001","approve");domain.execute("prepare_contract",in);
  String after=CreditSurfaces.event(domain).toString();assertTrue(after.contains("name=sign"));assertFalse(after.contains("name=approve"));
  assertEquals("合同待签署",domain.execute("query_application",in).get("status"));
 }
}
