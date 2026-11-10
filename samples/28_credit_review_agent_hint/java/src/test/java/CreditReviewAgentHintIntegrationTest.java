import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
/** Scripted terminal input is test-only; every answer comes from the real model. */
@Tag("integration")
class CreditReviewAgentHintIntegrationTest {
 @Test void realModelTwoTurnsAndPauseResume() throws Exception {
  assertNotNull(System.getenv("API_BASE"),"请配置真实模型API_BASE");
  assertFalse(CreditReviewAgentHint.parseSwitch(CreditReviewAgentHint.env("AGENT_HINT_ENABLED","false")),"此基线验收要求AGENT_HINT_ENABLED=false");
  InputStream oldIn=System.in;PrintStream oldOut=System.out;ByteArrayOutputStream output=new ByteArrayOutputStream();
  try {
   System.setIn(new ByteArrayInputStream("请查询客户C1001的评分和逾期笔数\n/pause\n/resume\n请重新查询C1001\n/exit\n".getBytes(StandardCharsets.UTF_8)));
   System.setOut(new PrintStream(output,true,StandardCharsets.UTF_8));
   CreditReviewAgentHint.main(new String[0]);
  }finally {System.setIn(oldIn);System.setOut(oldOut);}
  String transcript=output.toString(StandardCharsets.UTF_8);oldOut.println(transcript);
  assertTrue(transcript.contains("会话已暂停"));assertTrue(transcript.contains("可以继续提问"));
  assertEquals(2,transcript.split("735",-1).length-1,"每轮应真实返回演示评分");
  assertTrue(transcript.contains("C1001"));assertFalse(transcript.contains("result_type=error"));
 }
}
