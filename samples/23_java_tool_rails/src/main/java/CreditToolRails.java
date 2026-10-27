import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.base.TagMatchStrategy;
import com.openjiuwen.core.session.checkpointer.CheckpointerFactory;
import com.openjiuwen.core.session.checkpointer.InMemoryCheckpointer;
import com.openjiuwen.core.session.interaction.InteractiveInput;
import com.openjiuwen.core.singleagent.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.interrupt.InterruptRequest;
import com.openjiuwen.core.singleagent.interrupt.ToolInterruptException;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.rails.interrupt.BaseInterruptRail;
import com.openjiuwen.harness.rails.interrupt.InterruptDecision;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Official Rail extension points in a small loan-approval flow. */
public final class CreditToolRails {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private CreditToolRails() {
    }

    public static void main(String[] args) {
        String sessionId = "loan-rails-" + UUID.randomUUID();
        CheckpointerFactory.setDefaultCheckpointer(new InMemoryCheckpointer());
        try {
            ReActAgent agent = createAgent();
            Map<?, ?> first = (Map<?, ?>) Runner.runAgent(agent,
                    Map.of("query", "为客户 C1001 放款 50000 元。", "conversation_id", sessionId), null, null);
            System.out.println("首次结果: " + first.get("result_type"));
            if ("interrupt".equals(first.get("result_type"))) {
                // 演示业务系统提交审批结果；实际项目从审批页面或审批服务接收。
                InteractiveInput approval = new InteractiveInput();
                for (Object id : (List<?>) first.get("interrupt_ids")) {
                    approval.update(String.valueOf(id), true);
                }
                System.out.println("审批通过，恢复原会话");
                System.out.println("恢复结果: " + Runner.runAgent(agent,
                        Map.of("query", approval, "conversation_id", sessionId), null, null));
            }
        } finally {
            Runner.resourceMgr().removeTool("disburse_loan", "credit_tool_rails", TagMatchStrategy.ALL, true);
            Runner.release(sessionId);
            Runner.stop();
        }
    }

    static ReActAgent createAgent() {
        String model = env("MODEL_NAME", "google/gemma-4-e4b");
        ReActAgent agent = new ReActAgent(AgentCard.builder()
                .id("credit_tool_rails").name("贷款放款助手").description("演示工具校验、审批恢复和重试").build());
        agent.configure(ReActAgentConfig.builder()
                .modelName(model)
                .modelClientConfig(ModelClientConfig.builder()
                        .clientProvider(env("MODEL_PROVIDER", "openai"))
                        .apiBase(env("API_BASE", "http://127.0.0.1:1234/v1"))
                        .apiKey(env("API_KEY", "lm-studio")).timeout(60.0).build())
                .modelConfigObj(ModelRequestConfig.builder().modelName(model)
                        .temperature(0.0).maxTokens(256).build())
                .promptTemplate(List.of(Map.of("role", "system", "content",
                        "你是银行放款助手。收到放款申请必须调用 disburse_loan，严格使用用户给定的客户编号和金额。"
                                + "工具返回后不要再次调用工具，直接依据工具结果简短回答，不得自行声称放款成功。")))
                .maxIterations(4).maxParallelToolCalls(1).shouldFailTaskOnToolError(true).build());
        Tool tool = createTool();
        Runner.resourceMgr().addTool(tool, agent.getCard().getId());
        agent.getAbilityManager().add(tool.getCard());
        agent.registerRail(new LoanApprovalRail());
        agent.registerRail(new BankRetryRail());
        return agent;
    }

    private static Tool createTool() {
        ToolCard card = ToolCard.builder().id("disburse_loan").name("disburse_loan")
                .description("为指定客户发放贷款；业务规则和审批由系统校验")
                .inputParams(Map.of("type", "object", "properties", Map.of(
                        "customer_id", Map.of("type", "string", "description", "客户编号，例如 C1001"),
                        "amount", Map.of("type", "number", "description", "放款金额，单位元")),
                        "required", List.of("customer_id", "amount"))).build();
        AtomicBoolean isFirstAttempt = new AtomicBoolean(true);
        return new LocalFunction(card, inputs -> {
            // 本地演示银行工具：首次故障用于展示重试，不连接真实账户或发放资金。
            if (isFirstAttempt.getAndSet(false)) {
                throw new IllegalStateException("BANK_BUSY: 演示银行接口暂时繁忙");
            }
            return Map.of("status", "DISBURSED", "customer_id", inputs.get("customer_id"),
                    "amount", inputs.get("amount"), "reference", "DEMO-LOAN-001");
        });
    }

    /** 业务校验和恢复后的审批决策，均使用官方中断 Rail。 */
    static final class LoanApprovalRail extends BaseInterruptRail {
        LoanApprovalRail() {
            super(List.of("disburse_loan"));
        }

        @Override
        protected InterruptDecision resolveInterrupt(AgentCallbackContext ctx, ToolCall call, Object approval) {
            JsonNode arguments;
            try {
                String rawArguments = call.getArguments();
                arguments = rawArguments == null ? null : JSON.readTree(rawArguments);
            } catch (JsonProcessingException error) {
                return reject(Map.of("status", "REJECTED", "reason", "工具参数必须是合法 JSON"));
            }
            JsonNode value = arguments == null ? null : arguments.get("amount");
            if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue())
                    || value.decimalValue().signum() <= 0
                    || value.decimalValue().compareTo(new BigDecimal("100000")) > 0) {
                return reject(Map.of("status", "REJECTED", "reason", "放款金额必须大于 0 且不超过 100000 元"));
            }
            if (value.decimalValue().compareTo(new BigDecimal("20000")) <= 0) {
                return approve();
            }
            if (approval == null) {
                return interrupt(InterruptRequest.builder().interruptId(call.getId())
                        .message("大额放款需要人工审批").context(Map.of("amount", value.decimalValue())).build());
            }
            return Boolean.TRUE.equals(approval) ? approve()
                    : reject(Map.of("status", "REJECTED", "reason", "审批未通过，未执行放款"));
        }
    }

    /** 只对明确的临时错误重试一次，不重试人工审批中断。 */
    static final class BankRetryRail extends AgentRail {
        @Override
        public void onToolException(AgentCallbackContext ctx) {
            Exception error = ctx.getException();
            if (error instanceof ToolInterruptException) {
                return;
            }
            if (ctx.getRetryAttempt() < 1 && error.getMessage() != null
                    && error.getMessage().contains("BANK_BUSY")) {
                System.out.println("异常处理: BANK_BUSY，重试一次");
                ctx.requestRetry(0.1);
            }
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
