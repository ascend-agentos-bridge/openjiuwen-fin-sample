import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.base.TagMatchStrategy;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.core.session.stream.TraceSchema;
import com.openjiuwen.core.session.tracer.TraceAgentSpan;
import com.openjiuwen.core.singleagent.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.extensions.tracerotel.OtelRail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 通过官方 trace span 验收真实的 Agent、模型和工具调用。 */
public final class CreditCallTrace {
    private static final Logger LOG = LoggerFactory.getLogger("sample.call_trace");
    private static final String AGENT_ID = "credit_trace_agent";
    private static final String TOOL_ID = "query_credit_report";

    public static void main(String[] args) throws Exception {
        String sessionId = "credit-trace-" + UUID.randomUUID();
        String reportId = "CR-" + UUID.randomUUID().toString().substring(0, 8);
        Tool tool = null;
        Iterator<Object> stream = null;
        try {
            String apiBase = env("API_BASE", "http://127.0.0.1:1234/v1");
            String modelName = env("MODEL_NAME", "google/gemma-4-e4b");
            LOG.info("CONFIG: agent-core-java=0.1.16 model={} apiBase={}", modelName, apiBase);

            ReActAgent agent = new ReActAgent(AgentCard.builder()
                    .id(AGENT_ID).name(AGENT_ID).description("贷前征信查询助手").build());
            ReActAgentConfig config = ReActAgentConfig.builder()
                    .promptTemplate(List.of(Map.of("role", "system", "content",
                            "你是银行贷前审查助手。必须先调用 query_credit_report 查询客户征信，"
                                    + "每次只调用一次，获得结果后不要再次调用工具。"
                                    + "用中文简短回答，必须原样包含报告编号、客户编号和信用评分。"
                                    + "不得编造征信记录，不得作出实际放款承诺。")))
                    .maxIterations(3).build()
                    .configureModelClient("openai", env("API_KEY", "lm-studio"),
                            apiBase, modelName, true);
            config.getModelConfigObj().setTemperature(0.1);
            config.getModelConfigObj().setMaxTokens(512);
            agent.configure(config);

            // 官方 rail 自动创建 Agent 根 span、LLM 子 span、工具子 span。
            // 只消费框架 TRACE 事件，不需要初始化 OTLP exporter 或外部服务。
            agent.registerRail(new OtelRail());
            tool = new LocalFunction(ToolCard.builder()
                    .id(TOOL_ID).name(TOOL_ID).description("查询客户的演示征信档案")
                    .inputParams(Map.of("type", "object", "properties", Map.of(
                            "customer_id", Map.of("type", "string", "description", "客户编号，例如 C1001")),
                            "required", List.of("customer_id")))
                    .build(), inputs -> {
                        if (!"C1001".equals(inputs.get("customer_id"))) {
                            throw new IllegalArgumentException("演示档案只包含客户 C1001");
                        }
                        return Map.of("customer_id", "C1001", "credit_score", 735,
                                "overdue_months", 0, "report_id", reportId, "data_source", "演示数据");
                    });
            Runner.resourceMgr().addTool(tool, AGENT_ID);
            agent.getAbilityManager().add(tool.getCard());

            Map<String, TraceAgentSpan> latestSpans = new LinkedHashMap<>();
            stream = Runner.runAgentStreaming(agent, Map.of("query",
                    "请查询客户 C1001 的征信档案，给出简短的贷前核验摘要，保留报告编号。",
                    "conversation_id", sessionId), null, null, List.of(StreamMode.TRACE, StreamMode.OUTPUT));
            while (stream.hasNext()) {
                Object frame = stream.next();
                if (frame instanceof TraceSchema trace && trace.getPayload() instanceof TraceAgentSpan span) {
                    latestSpans.put(span.getInvokeId(), span);
                    LOG.info("TRACE traceId={} invokeId={} parentInvokeId={} type={} name={} status={} elapsed={}",
                            span.getTraceId(), span.getInvokeId(), span.getParentInvokeId(),
                            span.getInvokeType(), span.getName(), span.getStatus(), span.getElapsedTime());
                } else if (frame instanceof OutputSchema output && "error".equals(output.getType())) {
                    throw new IllegalStateException("Agent 流执行失败: " + output.getPayload());
                }
            }
            TraceAgentSpan root = verifyTrace(new ArrayList<>(latestSpans.values()), reportId);
            if (!Files.readString(Path.of("logs", "call_trace.log")).contains(root.getTraceId())) {
                throw new IllegalStateException("当前调用链没有写入 logs/call_trace.log");
            }
            LOG.info("RESULT: {}", root.getOutputs());
            LOG.info("SUCCESS: Java 调用链验收通过，{} 个 span，Agent → LLM → 征信工具 → LLM → 回答，日志位于 logs/call_trace.log",
                    latestSpans.size());
        } finally {
            try {
                if (stream instanceof AutoCloseable closeable) {
                    closeable.close();
                }
            } finally {
                if (tool != null) {
                    Runner.resourceMgr().removeTool(TOOL_ID, AGENT_ID, TagMatchStrategy.ALL, true);
                }
                Runner.release(sessionId);
                Runner.stop();
            }
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static TraceAgentSpan verifyTrace(List<TraceAgentSpan> spans, String reportId) {
        List<TraceAgentSpan> roots = spans.stream()
                .filter(s -> "chain".equals(s.getInvokeType()) && AGENT_ID.equals(s.getName()))
                .toList();
        if (roots.size() != 1) {
            throw new IllegalStateException("必须有且仅有一个 Agent 根 span，实际为 " + roots.size());
        }
        TraceAgentSpan root = roots.get(0);
        if (root.getTraceId() == null || root.getTraceId().isBlank()
                || (root.getParentInvokeId() != null && !root.getParentInvokeId().isBlank())) {
            throw new IllegalStateException("根 span 的 traceId 或 parentInvokeId 不符");
        }
        long modelCalls = 0;
        long toolCalls = 0;
        for (TraceAgentSpan span : spans) {
            if (!root.getTraceId().equals(span.getTraceId()) || !"finish".equals(span.getStatus())
                    || span.getError() != null || span.getEndTime() == null) {
                throw new IllegalStateException("轨迹未成功完成: " + span.getInvokeId());
            }
            if (span != root && !root.getInvokeId().equals(span.getParentInvokeId())) {
                throw new IllegalStateException("子 span 没有指向 Agent 根 span: " + span.getInvokeId());
            }
            if ("llm".equals(span.getInvokeType())) {
                modelCalls++;
            }
            if ("plugin".equals(span.getInvokeType()) && TOOL_ID.equals(span.getName())
                    && String.valueOf(span.getInputs()).contains("C1001")
                    && String.valueOf(span.getOutputs()).contains(reportId)) {
                toolCalls++;
            }
        }
        if (modelCalls < 2 || toolCalls < 1) {
            throw new IllegalStateException("必须观察到至少两次模型调用和一次征信工具调用");
        }
        if (!(root.getOutputs() instanceof Map<?, ?> outputs)
                || !(outputs.get("outputs") instanceof Map<?, ?> result)
                || !"answer".equals(result.get("result_type"))
                || !String.valueOf(result.get("output")).contains(reportId)) {
            throw new IllegalStateException("最终回答没有回传真实工具生成的报告编号");
        }
        return root;
    }
}
