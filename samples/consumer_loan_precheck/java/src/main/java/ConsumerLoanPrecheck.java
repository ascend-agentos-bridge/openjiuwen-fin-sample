import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.base.TagMatchStrategy;
import com.openjiuwen.core.session.checkpointer.CheckpointerFactory;
import com.openjiuwen.core.session.checkpointer.InMemoryCheckpointer;
import com.openjiuwen.core.session.interaction.InteractionOutput;
import com.openjiuwen.core.session.interaction.InteractiveInput;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.singleagent.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.interrupt.ToolCallInterruptRequest;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.rails.interrupt.AskUserRail;
import com.openjiuwen.harness.rails.interrupt.AskUserTool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 消费贷预审 Agent（Java 版）—— 客户信息不全时先向客户澄清，再按规则给出预审意见。
 *
 * <p>使用开源 openjiuwen Agent Core 真实 API（com.openjiuwen:agent-core-java:0.1.16）：
 * <ul>
 *   <li>{@link AskUserRail} 拦截内置 {@code ask_user} 工具，Agent 信息不全时返回中断等待客户回答；</li>
 *   <li>{@link #runPrecheck} 跑一次预审对话：追问 → 宿主收集客户回答 → 恢复执行；</li>
 *   <li>{@code consumer_loan_precheck} 规则工具负责额度/利率/月供计算，模型只组织结论。</li>
 * </ul>
 *
 * <p>运行：在本目录执行 {@code mvn -q compile exec:java}，按提示输入客户问题与澄清信息；模型配置见 README。
 */
public final class ConsumerLoanPrecheck {
    static final String SESSION_ID = "consumer-loan-precheck";
    private static final String AGENT_ID = "consumer_loan_precheck_agent";
    private static final String ASK_USER_TOOL = "ask_user";
    private static final String PRECHECK_TOOL = "consumer_loan_precheck";
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    private static final String SYSTEM_PROMPT =
            "你是银行消费贷预审助手。客户申请信息不全（缺金额、期限等关键要素）时，"
                    + "必须先调用 ask_user 工具向客户澄清，不要自行假设；"
                    + "拿到客户回答后，必须调用 consumer_loan_precheck 工具做规则预审，"
                    + "再根据工具返回结果给出预审意见，不要编造额度、利率和月供。";

    private ConsumerLoanPrecheck() {
    }

    /** 业务入口：读终端输入 → 跑一次预审对话 → 打印预审意见。 */
    public static void main(String[] args) {
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        CheckpointerFactory.setDefaultCheckpointer(new InMemoryCheckpointer());
        try {
            String query = readQuery(stdin);
            if (query == null) {
                return;
            }
            Map<String, Object> opinion = runPrecheck(query, question -> askInTerminal(stdin, question));
            System.out.println("\n=== 预审意见 ===");
            System.out.println(opinion.getOrDefault("output", ""));
        } finally {
            Runner.resourceMgr().removeTool(PRECHECK_TOOL, AGENT_ID, TagMatchStrategy.ALL, true);
            Runner.resourceMgr().removeTool(ASK_USER_TOOL, AGENT_ID, TagMatchStrategy.ALL, true);
            Runner.release(SESSION_ID);
            Runner.stop();
        }
    }

    // ---- 1. 一次预审对话：Agent 追问时由宿主收集客户回答，然后恢复 ----

    /**
     * 跑一次预审，返回 Agent 的最终结果（{@code output} 即预审意见）。
     *
     * <p>askUser 是宿主提供的交互回调：收到 Agent 的追问（含选项）后返回客户回答。
     * 终端里从 stdin 读取，UT 里用脚本化的回答。
     */
    static Map<String, Object> runPrecheck(String query, Function<Map<String, Object>, String> askUser) {
        ReActAgent agent = createAgent();
        Map<String, Object> result = invoke(agent, query);
        while ("interrupt".equals(result.get("result_type"))) {
            result = resumeWithAnswers(agent, result, askUser);
        }
        return result;
    }

    /** 把中断里的问题逐条交给宿主收集答案，再带着答案恢复同一会话。 */
    private static Map<String, Object> resumeWithAnswers(ReActAgent agent, Map<String, Object> interrupted,
            Function<Map<String, Object>, String> askUser) {
        ToolCallInterruptRequest request = askUserRequest(interrupted);
        List<Map<String, Object>> questions = request.getQuestions() == null ? List.of() : request.getQuestions();
        if (questions.isEmpty()) {
            throw new IllegalStateException("Agent 追问缺少结构化问题: " + request.getMessage());
        }
        Map<String, String> answers = new LinkedHashMap<>();
        for (Map<String, Object> question : questions) {
            answers.put(text(question.get("question")), askUser.apply(question));
        }
        InteractiveInput input = new InteractiveInput();
        input.update(request.getInterruptId(), Map.of("answers", answers));
        return invoke(agent, input);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> invoke(ReActAgent agent, Object query) {
        return (Map<String, Object>) Runner.runAgent(agent,
                Map.of("query", query, "conversation_id", SESSION_ID), null, null);
    }

    @SuppressWarnings("unchecked")
    private static ToolCallInterruptRequest askUserRequest(Map<String, Object> result) {
        List<OutputSchema> state = (List<OutputSchema>) result.get("state");
        if (state == null || state.isEmpty()) {
            throw new IllegalStateException("中断结果缺少交互内容: " + result);
        }
        if (!(state.get(0).getPayload() instanceof InteractionOutput output)
                || !(output.getValue() instanceof ToolCallInterruptRequest request)) {
            throw new IllegalStateException("中断结果不是 ask_user 请求: " + state.get(0).getPayload());
        }
        return request;
    }

    // ---- 2. Agent 构建：模型走环境变量，注册规则工具与 ask_user 提问能力 ----

    static ReActAgent createAgent() {
        String model = env("MODEL_NAME", "google/gemma-4-e4b");
        ReActAgent agent = new ReActAgent(AgentCard.builder()
                .id(AGENT_ID).name("消费贷预审助手")
                .description("消费贷预审助手：先向客户澄清金额与期限，再按规则给出预审意见").build());
        agent.configure(ReActAgentConfig.builder()
                .modelName(model)
                .modelClientConfig(ModelClientConfig.builder()
                        .clientProvider(env("MODEL_PROVIDER", "openai"))
                        .apiBase(env("API_BASE", "http://127.0.0.1:1234/v1"))
                        .apiKey(env("API_KEY", "lm-studio"))
                        .timeout(600.0).maxRetries(1).verifySsl(false).build())
                .modelConfigObj(ModelRequestConfig.builder().modelName(model).temperature(0.2).build())
                .promptTemplate(List.of(Map.of("role", "system", "content", SYSTEM_PROMPT)))
                .maxIterations(5).maxParallelToolCalls(1).shouldFailTaskOnToolError(true).build());

        List<Tool> tools = List.of(createPrecheckTool(), new AskUserTool());
        for (Tool tool : tools) {
            Runner.resourceMgr().addTool(tool, agent.getCard().getId());
            agent.getAbilityManager().add(tool.getCard());
        }
        agent.registerRail(new AskUserRail());
        return agent;
    }

    /** 业务规则以 LocalFunction 暴露给 Agent；额度/利率/月供全部由确定性规则计算。 */
    private static Tool createPrecheckTool() {
        ToolCard card = ToolCard.builder().id(PRECHECK_TOOL).name(PRECHECK_TOOL)
                .description("消费贷预审规则计算。入参为贷款用途、金额区间、期限区间的原文，"
                        + "返回可贷额度(万元)、年利率(%)、每月还款(元)、是否需人工复核与预审结论。")
                .inputParams(Map.of("type", "object", "properties", Map.of(
                        "purpose", Map.of("type", "string", "description", "贷款用途原文，例如 装修"),
                        "amount_range", Map.of("type", "string", "description", "金额区间原文，例如 10万 - 20万元"),
                        "term_range", Map.of("type", "string", "description", "期限区间原文，例如 3 - 5年")),
                        "required", List.of("purpose", "amount_range", "term_range"))).build();
        return new LocalFunction(card, inputs -> evaluatePrecheck(text(inputs.get("purpose")),
                text(inputs.get("amount_range")), text(inputs.get("term_range"))));
    }

    // ---- 3. 消费贷预审规则（纯业务计算，模型只负责组织结论） ----

    /**
     * 消费贷预审规则：装修贷上限 30 万元、年利率 3.6%；其它用途上限 20 万元、年利率 4.2%。
     * 金额/期限取区间中位数（“以上”取起始值），月供按等额本息计算，超上限转人工复核。
     */
    static Map<String, Object> evaluatePrecheck(String purpose, String amountRange, String termRange) {
        double targetWan = amountWan(amountRange);
        int months = termMonths(termRange);
        boolean isRenovation = purpose != null && purpose.contains("装修");
        double capWan = isRenovation ? 30.0 : 20.0;
        double annualRate = isRenovation ? 0.036 : 0.042;
        double approvedWan = Math.min(targetWan, capWan);

        double monthlyRate = annualRate / 12;
        double principal = approvedWan * 10000;
        double factor = Math.pow(1 + monthlyRate, months);
        double monthlyPayment = principal * monthlyRate * factor / (factor - 1);
        boolean manualReview = targetWan > capWan;
        String conclusion = manualReview
                ? String.format(Locale.ROOT, "超出%.0f万元预审上限，需人工复核后确定额度", capWan)
                : String.format(Locale.ROOT, "预审通过，可批 %.1f 万元，进入正式审批流程", approvedWan);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("purpose", purpose);
        result.put("target_amount_wan", round(targetWan, 1));
        result.put("approved_amount_wan", round(approvedWan, 1));
        result.put("annual_rate_pct", round(annualRate * 100, 2));
        result.put("term_months", months);
        result.put("monthly_payment_yuan", round(monthlyPayment, 2));
        result.put("manual_review", manualReview);
        result.put("conclusion", conclusion);
        return result;
    }

    /** 把 "10万 - 20万元" / "30万元以上" 这类区间转成中位数金额（万元）。 */
    static double amountWan(String amountRange) {
        List<Double> nums = numbers(amountRange);
        if (nums.isEmpty()) {
            return 20.0;
        }
        if (amountRange != null && amountRange.contains("以上")) {
            return nums.get(0);
        }
        return nums.size() >= 2 ? (nums.get(0) + nums.get(1)) / 2 : nums.get(0);
    }

    /** 把 "3 - 5年" / "12-24月" 这类期限转成月数。 */
    static int termMonths(String termRange) {
        List<Double> nums = numbers(termRange);
        if (nums.isEmpty()) {
            return 36;
        }
        double value = nums.size() >= 2 ? (nums.get(0) + nums.get(1)) / 2 : nums.get(0);
        if (termRange != null && termRange.contains("月") && !termRange.contains("年")) {
            return (int) Math.round(value);
        }
        return (int) Math.round(value * 12);
    }

    private static List<Double> numbers(String text) {
        List<Double> values = new ArrayList<>();
        if (text == null) {
            return values;
        }
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            values.add(Double.valueOf(matcher.group()));
        }
        return values;
    }

    private static double round(double value, int decimals) {
        double scale = Math.pow(10, decimals);
        return Math.round(value * scale) / scale;
    }

    // ---- 4. CLI 宿主：把 Agent 的追问交给运行样例的人输入 ----

    private static String readQuery(BufferedReader stdin) {
        while (true) {
            System.out.print("客户问题> ");
            System.out.flush();
            String line = readLine(stdin);
            if (line == null) {
                return null;
            }
            if (!line.isBlank()) {
                return line.trim();
            }
            System.out.println("请输入客户问题，例如：我想贷点钱装修，能批多少？");
        }
    }

    private static String askInTerminal(BufferedReader stdin, Map<String, Object> question) {
        System.out.printf("%n[Agent 追问] %s: %s%n", text(question.get("header")), text(question.get("question")));
        if (question.get("options") instanceof List<?> options) {
            int index = 1;
            for (Object option : options) {
                if (option instanceof Map<?, ?> item) {
                    System.out.printf("  %d. %s —— %s%n", index++,
                            text(item.get("label")), text(item.get("description")));
                }
            }
        }
        while (true) {
            System.out.print("请选择/输入答案> ");
            System.out.flush();
            String answer = readLine(stdin);
            if (answer == null) {
                throw new IllegalStateException("等待客户输入时标准输入已结束");
            }
            if (!answer.isBlank()) {
                return answer.trim();
            }
        }
    }

    private static String readLine(BufferedReader stdin) {
        try {
            return stdin.readLine();
        } catch (IOException error) {
            throw new UncheckedIOException("读取终端输入失败", error);
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
