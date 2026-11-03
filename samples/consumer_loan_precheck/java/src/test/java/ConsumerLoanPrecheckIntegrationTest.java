import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.base.TagMatchStrategy;
import com.openjiuwen.core.session.checkpointer.CheckpointerFactory;
import com.openjiuwen.core.session.checkpointer.InMemoryCheckpointer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 真实模型集成验收：ask_user 追问 → 客户回答 → 恢复 → 预审意见。
 *
 * <p>不 mock 模型，必须配置可用的 API_BASE / API_KEY / MODEL_NAME / MODEL_PROVIDER。
 * 客户回答由脚本化 askUser 回调注入（金额挑最接近 15 万、期限挑最接近 4.5 年的选项），
 * 与 {@code main} 里终端交互走的是同一个 {@link ConsumerLoanPrecheck#runPrecheck} 入口。
 */
@Tag("integration")
class ConsumerLoanPrecheckIntegrationTest {
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
    private final List<Map<String, Object>> askedQuestions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        CheckpointerFactory.setDefaultCheckpointer(new InMemoryCheckpointer());
    }

    @AfterEach
    void tearDown() {
        Runner.resourceMgr().removeTool("consumer_loan_precheck", "consumer_loan_precheck_agent",
                TagMatchStrategy.ALL, true);
        Runner.resourceMgr().removeTool("ask_user", "consumer_loan_precheck_agent",
                TagMatchStrategy.ALL, true);
        Runner.release(ConsumerLoanPrecheck.SESSION_ID);
        Runner.stop();
    }

    @Test
    @Timeout(300)
    void asksForMissingLoanDetailsThenReturnsPrecheckOpinion() {
        Map<String, Object> result = ConsumerLoanPrecheck.runPrecheck(
                "客户陈女士咨询：我想贷点钱装修，你们能批多少？", this::scriptedCustomer);

        System.out.println("ask_user 追问 " + askedQuestions.size() + " 题: " + askedQuestions);
        assertFalse(askedQuestions.isEmpty(), "Agent 应先通过 ask_user 追问客户，再给出预审意见");
        assertTrue(askedQuestions.size() <= 4, "每次追问 1–4 题（任一追问轮次超 4 题都会使总数超 4）: " + askedQuestions);
        for (Map<String, Object> question : askedQuestions) {
            assertFalse(String.valueOf(question.get("question")).isBlank(), "问题文本不能为空: " + question);
            assertNotNull(question.get("options"), "每个问题都应带选项: " + question);
            int optionCount = ((List<?>) question.get("options")).size();
            assertTrue(optionCount >= 2 && optionCount <= 4, "每题 2–4 个选项: " + question);
        }

        assertEquals("answer", result.get("result_type"));
        String opinion = String.valueOf(result.get("output"));
        assertFalse(opinion.isBlank(), "恢复后应返回非空预审意见");
        System.out.println("真实模型验收（ask_user 预审）: " + result);
    }

    /** 脚本化客户：金额挑最接近 15 万、期限挑最接近 4.5 年的选项。 */
    private String scriptedCustomer(Map<String, Object> question) {
        askedQuestions.add(question);
        String text = text(question.get("header")) + text(question.get("question"));
        List<String> labels = new ArrayList<>();
        if (question.get("options") instanceof List<?> options) {
            for (Object option : options) {
                if (option instanceof Map<?, ?> item) {
                    labels.add(text(item.get("label")));
                }
            }
        }
        double target;
        if (text.contains("金额") || text.contains("额度")) {
            target = 15.0;
        } else if (text.contains("期限") || text.contains("期数") || text.contains("还款")) {
            target = 4.5;
        } else {
            return labels.isEmpty() ? "按推荐方案" : labels.get(0);
        }
        String best = labels.isEmpty() ? "按推荐方案" : labels.get(0);
        double bestDistance = Double.MAX_VALUE;
        for (String label : labels) {
            Double value = optionValue(label);
            if (value != null && Math.abs(value - target) < bestDistance) {
                bestDistance = Math.abs(value - target);
                best = label;
            }
        }
        return best;
    }

    /** 从选项文案估算数值：区间取中位数、“以上”取起始值、月换算成年（与 Python 版一致）。 */
    private static Double optionValue(String label) {
        Matcher matcher = NUMBER.matcher(label == null ? "" : label);
        List<Double> nums = new ArrayList<>();
        while (matcher.find()) {
            nums.add(Double.valueOf(matcher.group()));
        }
        if (nums.isEmpty()) {
            return null;
        }
        double value = nums.get(0);
        if (!label.contains("以上") && nums.size() >= 2) {
            value = (nums.get(0) + nums.get(1)) / 2;
        }
        if (label.contains("月") && !label.contains("年")) {
            value = value / 12;
        }
        return value;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
