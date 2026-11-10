import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CreditReviewAgentHintTest {
 @Test void validatesSwitch(){ assertTrue(CreditReviewAgentHint.parseSwitch("true")); assertFalse(CreditReviewAgentHint.parseSwitch("false")); assertThrows(IllegalArgumentException.class,()->CreditReviewAgentHint.parseSwitch("yes")); }
 @Test void rejectsUnknownCustomers(){assertThrows(IllegalArgumentException.class,()->CreditReviewAgentHint.creditRecord("NO"));assertTrue(CreditReviewAgentHint.creditRecord("C1001").contains("735"));}
}
