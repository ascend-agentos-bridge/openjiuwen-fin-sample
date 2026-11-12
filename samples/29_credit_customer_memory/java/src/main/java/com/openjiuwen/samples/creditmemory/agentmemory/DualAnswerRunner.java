package com.openjiuwen.samples.creditmemory.agentmemory;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.core.session.AgentSessionApi;
import com.openjiuwen.core.session.checkpointer.CheckpointerFactory;
import com.openjiuwen.core.session.checkpointer.InMemoryCheckpointer;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.factory.HarnessFactory;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;
import com.openjiuwen.memory.harness.rails.ExternalMemoryRail;
import com.openjiuwen.samples.creditmemory.config.ModelSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 「双回答」运行器：同一个客户问题分别交给
 *
 * <ol>
 *   <li><b>有记忆 Agent</b>：{@link DeepAgent} + {@link ExternalMemoryRail}，
 *       rail 在 {@code beforeModelCall} 调 {@link AgentMemoryProvider#prefetch}，
 *       把召回结果包成 {@code <memory-context>} 注入提示词；</li>
 *   <li><b>基线 Agent</b>：同一模型、同一系统提示词，但<b>不挂任何记忆 rail</b>。</li>
 * </ol>
 *
 * <p>返回两个真实回答 + rail 本轮召回内容，用同题对比证明召回是否生效。
 * 答案全部来自真实 DeepSeek 调用，不做任何缓存/兜底/伪回答；调用失败直接抛异常。</p>
 *
 * <p>0.1.16 API 差异（相对 agent-core 0.1.17 develop 示例）：
 * {@code CheckpointerFactory.setDefaultCheckpointer(new InMemoryCheckpointer())}、
 * {@code Runner.start()}、{@code DeepAgentConfig.builder()...rails(List.of(rail))}、
 * {@code HarnessFactory.createDeepAgent(card, config, workspace)}、
 * {@code AgentSessionApi.create(sessionId, envs, card)}、
 * {@code Runner.runAgent(agent, inputs, session, modelContext)} 在 0.1.16 均存在。
 * 特别注意：{@code DeepAgent.invoke} 在 {@code enableTaskLoop(false)} 时只返回调用信封
 * 而不执行模型（返回 {@code {agent_name, mode, workspace, inputs}}），必须开启 task loop。</p>
 */
public final class DualAnswerRunner implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DualAnswerRunner.class);
    private static final AtomicInteger RUN_SEQUENCE = new AtomicInteger();

    private static final String UI_DAY_PROMPT = """
            你是「信贷智能体」，场景是经营贷业务咨询。
            回答要自然、简洁、专业，像客户经理一样对话；不要逐条罗列信息，也不要把内部实现、
            字段名、数据库、向量检索、记忆库等术语告诉客户。
            【表达约束】绝对不要在回复中说“我记下了/已记录/已记住/系统已保存”等记忆动作；
            直接回应客户当前问题、推进业务即可。不确定的信息不要编造。
            """;

    /** 一轮真实对话写回记忆后的结果（用于验证 rail 的 afterInvoke → syncTurn）。 */
    public record SeedResult(String statement, String agentAnswer, String syncedContent,
                             List<AgentMemoryUnit> extractedUnits, String syncError) {
        public SeedResult {
            extractedUnits = extractedUnits == null ? List.of() : List.copyOf(extractedUnits);
        }
    }

    /** 一次双回答的结果。 */
    public record DualAnswer(String question,
                             String withMemoryAnswer,
                             String baselineAnswer,
                             String recalledContext,
                             String injectedMemoryBlock,
                             int prefetchCount,
                             List<AgentMemoryHit> recalledHits) {

        public DualAnswer {
            recalledHits = recalledHits == null ? List.of() : List.copyOf(recalledHits);
        }

        /** 结构化摘要（供 Lead 接入 WebServer/测试断言）。 */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("question", question);
            map.put("withMemoryAnswer", withMemoryAnswer);
            map.put("baselineAnswer", baselineAnswer);
            map.put("recalledContext", recalledContext);
            map.put("injectedMemoryBlock", injectedMemoryBlock);
            map.put("prefetchCount", prefetchCount);
            List<Map<String, Object>> hits = new ArrayList<>();
            for (AgentMemoryHit hit : recalledHits) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("unitId", hit.unitId());
                item.put("score", hit.score());
                item.put("content", hit.displayText());
                hits.add(item);
            }
            map.put("recalledHits", hits);
            return map;
        }
    }

    private final ModelSettings settings;
    private final AgentMemoryClient client;
    private final AgentMemoryProvider provider;
    private final String customerId;
    private final String conversationId;
    private final AgentMemoryScope memoryScope;
    private final String railScopeId;

    private DeepAgent memoryAgent;
    private DeepAgent baselineAgent;
    private Path workspaceRoot;
    private boolean started;

    /**
     * @param settings       对话模型配置（复用现有 {@link ModelSettings}，Key 只来自环境变量/本地配置）
     * @param client         Agent Memory REST 客户端
     * @param provider       已注入的 MemoryProvider
     * @param customerId     客户标识；dev auth 下作为 session 轴承载客户维度
     * @param conversationId 会话标识；当前 dev 单身份部署无独立轴，仅用于 rail 构造与日志
     */
    public DualAnswerRunner(ModelSettings settings, AgentMemoryClient client, AgentMemoryProvider provider,
                            String customerId, String conversationId) {
        this.settings = settings;
        this.client = client;
        this.provider = provider;
        this.customerId = customerId;
        this.conversationId = conversationId;
        Map<String, Object> railScope = railScope();
        this.memoryScope = provider.resolveScope(railScope);
        this.railScopeId = provider.resolveScope(railScope).agent();
    }

    // ------------------------------------------------------------------ 生命周期

    /** 注册 InMemoryCheckpointer、启动 Runner、创建并初始化两个 DeepAgent。 */
    public synchronized void start() {
        if (started) {
            return;
        }
        try {
            workspaceRoot = Files.createTempDirectory("agent-memory-deepagent-");
        } catch (IOException exception) {
            throw new IllegalStateException("创建 DeepAgent 工作目录失败: " + exception.getMessage(), exception);
        }
        CheckpointerFactory.setDefaultCheckpointer(new InMemoryCheckpointer());
        Runner.setConfig(RunnerConfig.DEFAULT);
        Runner.start();
        try {
            memoryAgent = buildAgent("credit-agent-memory", true);
            baselineAgent = buildAgent("credit-agent-baseline", false);
        } catch (RuntimeException exception) {
            close();
            throw exception;
        }
        started = true;
        LOG.info("[agent-memory] DualAnswerRunner 已启动: customer={} conversation={} scope={} model={}",
                customerId, conversationId, memoryScope.display(), settings.chat.model);
    }

    // ------------------------------------------------------------------ 记忆写入

    /**
     * 用 provider 的 syncTurn 真实写入一轮客户陈述（服务端 infer=true 做 LLM 抽取），
     * 作为后续问答题的记忆来源。返回本次抽取出的记忆单元。
     */
    public List<AgentMemoryUnit> rememberTurn(String customerStatement, String agentReply) throws Exception {
        ensureStarted();
        if (!provider.isInitialized()) {
            provider.initialize(railScope());
        }
        provider.syncTurn(customerStatement, agentReply, railScope());
        if (!provider.lastError().isBlank()) {
            throw new IllegalStateException("记忆写入失败: " + provider.lastError());
        }
        return provider.lastSyncedUnits();
    }

    /**
     * 让「有记忆 Agent」真实回答一轮客户陈述：rail 在 afterInvoke 调 provider.syncTurn
     * 把 {@code user: ...\nassistant: <真实模型回答>} 写回服务端（infer=true 真实抽取）。
     * 返回模型回答与本次抽取出的记忆单元，用于验证 rail 写回链路。
     */
    public SeedResult rememberByAgent(String customerStatement) {
        ensureStarted();
        if (customerStatement == null || customerStatement.isBlank()) {
            throw new IllegalArgumentException("customerStatement 不能为空");
        }
        String answer = runAgent(memoryAgent, customerStatement, "seed");
        return new SeedResult(customerStatement, answer, provider.lastSyncContent(),
                provider.lastSyncedUnits(), provider.lastError());
    }

    /** 直接列出当前 scope 下的记忆（含历史版本），用于展示写入/演进结果。 */
    public List<AgentMemoryUnit> listMemories(int limit) {
        return client.list(memoryScope, 0, Math.max(1, limit));
    }

    /** 当前会话的 HTTP scope（供 probe 直接调用 client.update/evolve 演示）。 */
    public AgentMemoryScope memoryScope() {
        return memoryScope;
    }

    public AgentMemoryProvider provider() {
        return provider;
    }

    // ------------------------------------------------------------------ 双回答

    /**
     * 同题双问：先跑挂 rail 的 Agent（捕获本轮召回），再跑无 rail 基线。
     *
     * @throws IllegalStateException 模型调用失败或返回空内容
     */
    public DualAnswer ask(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        ensureStarted();
        String withMemory = runAgent(memoryAgent, question, "with-memory");
        String recalledContext = provider.lastRecalledContext();
        String injectedBlock = provider.lastMemoryContextBlock();
        int prefetchCount = provider.lastPrefetchCount();
        List<AgentMemoryHit> hits = provider.lastRecalledHits();
        String baseline = runAgent(baselineAgent, question, "baseline");
        DualAnswer answer = new DualAnswer(question, withMemory, baseline,
                recalledContext, injectedBlock, prefetchCount, hits);
        LOG.info("[agent-memory] dual answer done: prefetchCount={} withMemoryLen={} baselineLen={}",
                prefetchCount, withMemory.length(), baseline.length());
        return answer;
    }

    // ------------------------------------------------------------------ 内部

    private DeepAgent buildAgent(String agentId, boolean withMemoryRail) {
        List<Object> rails = new ArrayList<>();
        if (withMemoryRail) {
            rails.add(new ExternalMemoryRail(provider, customerId, railScopeId, conversationId));
        }
        DeepAgentConfig config = DeepAgentConfig.builder()
                .enableTaskLoop(true)
                .enableTaskPlanning(false)
                .workspacePath(workspaceRoot.toString())
                .systemPrompt(UI_DAY_PROMPT)
                .maxIterations(4)
                .completionTimeout(120.0)
                .language("cn")
                .model(modelConfig())
                .backend(backendConfig())
                .rails(rails)
                .build();
        AgentCard card = AgentCard.builder()
                .id(agentId)
                .name(agentId)
                .description("信贷客户记忆双回答样例（" + (withMemoryRail ? "挂 ExternalMemoryRail" : "基线，无记忆") + "）")
                .build();
        Workspace workspace = Workspace.builder()
                .rootPath(workspaceRoot.toString())
                .language("cn")
                .build();
        DeepAgent agent = HarnessFactory.createDeepAgent(card, config, workspace);
        agent.ensureInitialized();
        return agent;
    }

    private Map<String, Object> modelConfig() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("model", settings.chat.model);
        model.put("temperature", settings.chat.temperature);
        model.put("max_tokens", Math.max(512, settings.chat.maxTokens));
        // DeepSeek 等推理模型可关闭 thinking，避免思考型输出挤占回答内容；
        // 对不支持该字段的 OpenAI 兼容端点，服务端会忽略未知参数。
        model.put("thinking", Map.of("type", "disabled"));
        return model;
    }

    private Map<String, Object> backendConfig() {
        Map<String, Object> backend = new LinkedHashMap<>();
        backend.put("client_provider", "openai");
        backend.put("api_key", settings.chat.apiKey);
        backend.put("api_base", settings.chat.apiBase);
        backend.put("timeout", 120.0);
        backend.put("max_retries", 1);
        backend.put("verify_ssl", true);
        return backend;
    }

    private String runAgent(DeepAgent agent, String query, String tag) {
        String sessionId = tag + "-" + customerId + "-" + RUN_SEQUENCE.incrementAndGet();
        AgentSessionApi session = AgentSessionApi.create(sessionId, new LinkedHashMap<>(), agent.getCard());
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("query", query);
        inputs.put("conversation_id", sessionId);
        // 0.1.16 实测坑位（Lead 实测 + 本地复跑确认）：
        //   - enableTaskLoop(false) 时 Runner.runAgent(deepAgent, ...) 反射命中 DeepAgent.invoke，
        //     只返回 {agent_name, mode, workspace, inputs} 信封、不执行模型 → 必须用底层 ReActAgent；
        //   - enableTaskLoop(true) 时 DeepAgent.invoke 走 runTaskLoop，返回真实回答，
        //     且 rails 在 task-loop 线程里正常触发（beforeInvoke/beforeModelCall/afterInvoke）。
        // 本样例统一开启 task loop 走 DeepAgent 标准入口，保留 DeepAgent 的任务编排能力。
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                Object result = Runner.runAgent(agent, inputs, session, null);
                return extractAnswer(result);
            } catch (RuntimeException exception) {
                lastError = exception;
                System.err.println("[agent-memory] 第 " + attempt + " 次模型调用失败: "
                        + rootMessage(exception));
                if (attempt < 2) {
                    try { Thread.sleep(800L); } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw new IllegalStateException("模型服务调用失败（已自动重试一次）：" + rootMessage(lastError)
                + "。请检查网络、CHAT_API_BASE / CHAT_API_KEY，或稍后重试。", lastError);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    @SuppressWarnings("unchecked")
    private String extractAnswer(Object result) {
        String answer = "";
        if (result instanceof Map<?, ?> map) {
            for (String key : List.of("output", "content", "text", "response")) {
                Object value = map.get(key);
                if (value != null && !String.valueOf(value).isBlank()) {
                    answer = String.valueOf(value);
                    break;
                }
            }
            if (answer.isBlank() && map.get("message") instanceof Map<?, ?> message
                    && message.get("content") != null) {
                answer = String.valueOf(message.get("content"));
            }
            if (answer.isBlank() && map.containsKey("agent_name") && map.containsKey("inputs")) {
                throw new IllegalStateException("DeepAgent 返回了 invoke 信封而不是模型回答（0.1.16 已知行为）: "
                        + result);
            }
        } else if (result != null) {
            answer = String.valueOf(result);
        }
        if (answer.isBlank()) {
            throw new IllegalStateException("Agent 返回空回答，原始结果: " + result);
        }
        return answer.strip();
    }

    private Map<String, Object> railScope() {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("user_id", customerId);
        scope.put("scope_id", provider.resolveScope(Map.of("user_id", customerId)).agent());
        scope.put("session_id", conversationId);
        return scope;
    }

    private void ensureStarted() {
        if (!started) {
            throw new IllegalStateException("DualAnswerRunner 未启动，请先调用 start()");
        }
    }

    @Override
    public synchronized void close() {
        if (memoryAgent != null) {
            try {
                memoryAgent.close();
            } catch (RuntimeException exception) {
                LOG.warn("[agent-memory] 关闭有记忆 Agent 失败: {}", exception.getMessage());
            }
            memoryAgent = null;
        }
        if (baselineAgent != null) {
            try {
                baselineAgent.close();
            } catch (RuntimeException exception) {
                LOG.warn("[agent-memory] 关闭基线 Agent 失败: {}", exception.getMessage());
            }
            baselineAgent = null;
        }
        try {
            CheckpointerFactory.setDefaultCheckpointer(null);
        } catch (RuntimeException ignored) {
            // best-effort
        }
        try {
            Runner.stop();
        } catch (RuntimeException ignored) {
            // best-effort
        }
        started = false;
        if (workspaceRoot != null) {
            deleteRecursively(workspaceRoot);
            workspaceRoot = null;
        }
    }

    private static void deleteRecursively(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort
                }
            });
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
