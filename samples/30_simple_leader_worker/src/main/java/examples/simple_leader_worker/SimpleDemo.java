/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package examples.simple_leader_worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.agentteams.LeaderTeammateAgentTeam;
import com.openjiuwen.agentteams.agent.TeamAgent;
import com.openjiuwen.agentteams.monitor.TeamMonitor;
import com.openjiuwen.agentteams.schema.team.ModelPoolEntry;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.session.stream.TraceSchema;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

/**
 * 极简 Leader-Worker 动态编排验证入口（交互问答模式）。
 *
 * <p>角色定义在 classpath 的 team-config.json 中，Leader 根据成员能力和用户需求
 * 自主规划任务 DAG，执行过程中根据成员回报动态调整。</p>
 *
 * <p>启动后进入交互问答循环：用户从标准输入输入需求，Leader-Worker 团队
 * 执行完毕后打印本轮摘要，然后等待下一次输入。输入 exit 退出。</p>
 *
 * <p>运行：</p>
 * <pre>
 * # 前置：core 已 install 到本地仓库
 * mvn -pl core install -DskipTests
 *
 * # 交互问答模式（启动后输入需求，输入 exit 退出）
 * mvn -f examples/simple_leader_worker/pom.xml compile exec:java
 *
 * # 只装配不调 LLM
 * mvn -f examples/simple_leader_worker/pom.xml compile exec:java -Ddemo.assembleOnly=true
 *
 * # 纯单测
 * mvn -f examples/simple_leader_worker/pom.xml test
 * </pre>
 */
public final class SimpleDemo {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SimpleDemo() {
    }

    /**
     * 程序入口。启动后进入交互问答循环。
     *
     * @param args 保留参数（交互模式下不使用）
     */
    public static void main(String[] args) {
        TeamConfig teamConfig = TeamConfig.load();
        TeamAssembler.ModelSettings model = loadModelSettings();

        boolean assembleOnly = Boolean.parseBoolean(
                System.getProperty("demo.assembleOnly", "false"));
        long timeoutSeconds = Long.parseLong(
                System.getProperty("demo.timeout.seconds", "300"));

        printBanner(teamConfig, model);

        LeaderTeammateAgentTeam team = null;
        TeamAgent leader = null;
        TeamMonitor monitor = null;
        ConsolePrinter printer = null;

        try {
            Runner.start().toCompletableFuture().join();

            ModelPoolEntry entry = TeamAssembler.buildModelEntry(model);
            team = TeamAssembler.buildTeam(teamConfig, entry);
            leader = team.agent();

            List<String> provisioned = TeamAssembler.provisionMembers(leader, teamConfig, entry);
            System.out.println("[装配] 成员预配完成: " + provisioned.size() + "/"
                    + teamConfig.members().size() + " → " + provisioned);

            monitor = TeamMonitor.createMonitor(leader);
            monitor.start();

            if (assembleOnly) {
                printRoster(monitor, teamConfig);
                System.out.println("[自检] 装配完成，未调用 LLM（-Ddemo.assembleOnly=true）");
                return;
            }

            printer = new ConsolePrinter(teamConfig.leader().name(), System.out);
            leader.addEventListener(printer);

            Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);
            int round = 0;
            while (true) {
                String query = readQuery(scanner);
                if (query == null) {
                    System.out.println("[退出] 再见");
                    break;
                }
                if (query.isEmpty()) {
                    System.out.println("[提示] 需求不能为空，请重新输入");
                    continue;
                }
                round++;
                System.out.println();
                System.out.println(">>> 第 " + round + " 轮需求: " + query);
                System.out.println(">>> 超时: " + timeoutSeconds + "s");
                System.out.println();

                Set<String> baselineTaskIds = collectTaskIds(monitor);
                runOneRound(leader, monitor, printer, teamConfig, query,
                        timeoutSeconds, round, baselineTaskIds);
            }
        } catch (RuntimeException e) {
            System.err.println("[失败] " + e.getMessage());
            e.printStackTrace(System.err);
        } finally {
            cleanup(team, leader, monitor, printer, assembleOnly);
        }
    }

    private static String readQuery(Scanner scanner) {
        System.out.println();
        System.out.print("请输入需求（输入 exit 退出）: ");
        System.out.flush();
        if (!scanner.hasNextLine()) {
            return null;
        }
        String line = scanner.nextLine().trim();
        if ("exit".equalsIgnoreCase(line)
                || "quit".equalsIgnoreCase(line)
                || ":q".equalsIgnoreCase(line)) {
            return null;
        }
        return line;
    }

    private static void runOneRound(TeamAgent leader, TeamMonitor monitor,
                                     ConsolePrinter printer, TeamConfig teamConfig,
                                     String query, long timeoutSeconds, int round,
                                     Set<String> baselineTaskIds) {
        String briefing = TeamAssembler.composeFirstQuery(teamConfig, query);
        if (round > 1) {
            briefing = "【多轮对话】这是第 " + round + " 轮交互。"
                    + "任务板上可能有之前轮次的已完成任务，"
                    + "请不要被它们干扰，基于当前需求自主规划新任务。\n\n" + briefing;
        }

        String sessionId = "simple-demo-r" + round + "-"
                + Long.toHexString(System.currentTimeMillis());
        Iterator<Object> stream = leader.stream(Map.of("query", briefing), sessionId);

        Thread streamThread = new Thread(
                () -> consumeStream(stream, System.out), "leader-stream-" + round);
        streamThread.setDaemon(true);
        streamThread.start();

        waitRoundDone(monitor, timeoutSeconds * 1000, baselineTaskIds, streamThread);

        try {
            leader.cancelAgent();
        } catch (RuntimeException ignored) {
        }

        try {
            leader.getStreamController().closeStream();
        } catch (RuntimeException ignored) {
        }
        try {
            streamThread.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        printer.printRoundSummary(monitor, baselineTaskIds);
    }

    private static void consumeStream(Iterator<Object> stream, PrintStream out) {
        StringBuilder lineBuffer = new StringBuilder();
        try {
            while (stream.hasNext()) {
                Object chunk = stream.next();
                if (!(chunk instanceof TraceSchema schema)) {
                    continue;
                }
                String type = schema.getType() == null ? "" : schema.getType();
                Object payloadObj = schema.getPayload();
                Map<String, Object> payload = payloadObj instanceof Map<?, ?> map
                        ? toStringMap(map) : Map.of();
                switch (type) {
                    case "llm_output" -> {
                        String content = str(payload.get("content"));
                        if (content.isBlank()) {
                            continue;
                        }
                        lineBuffer.append(content);
                        int nl;
                        while ((nl = lineBuffer.indexOf("\n")) >= 0) {
                            String line = lineBuffer.substring(0, nl).strip();
                            if (!line.isBlank()) {
                                out.println("[leader] " + line);
                            }
                            lineBuffer.delete(0, nl + 1);
                        }
                    }
                    case "tool_call" -> {
                        flushLineBuffer(out, lineBuffer);
                        out.println("  ● " + str(payload.get("tool_name"))
                                + "(" + abbreviate(str(payload.get("arguments")), 120) + ")");
                    }
                    case "tool_output" -> {
                        flushLineBuffer(out, lineBuffer);
                        out.println("  ⎿ " + str(payload.get("status"))
                                + ": " + abbreviate(str(payload.containsKey("error")
                                        ? payload.get("error") : payload.get("result")), 160));
                    }
                    case "answer" -> {
                        flushLineBuffer(out, lineBuffer);
                        out.println("[交付结论] " + str(payload.get("content")));
                    }
                    default -> { }
                }
            }
            flushLineBuffer(out, lineBuffer);
        } catch (RuntimeException e) {
            flushLineBuffer(out, lineBuffer);
            out.println("[warn] 流式输出异常: " + e);
        }
    }

    private static void flushLineBuffer(PrintStream out, StringBuilder lineBuffer) {
        if (lineBuffer.length() > 0) {
            String remaining = lineBuffer.toString().strip();
            if (!remaining.isBlank()) {
                out.println("[leader] " + remaining);
            }
            lineBuffer.setLength(0);
        }
    }

    private static void waitRoundDone(TeamMonitor monitor, long timeoutMs,
                                       Set<String> baselineTaskIds, Thread streamThread) {
        long start = System.currentTimeMillis();
        long lastHeartbeat = start;
        boolean newTasksSeen = false;
        long tasksAllDoneTime = 0;
        long settleTimeoutMs = Long.parseLong(
                System.getProperty("demo.settle.seconds", "15")) * 1000;

        while (true) {
            long now = System.currentTimeMillis();
            if (now - start >= timeoutMs) {
                System.out.println("[结束] 达到硬超时 (" + (timeoutMs / 1000) + "s)");
                return;
            }

            var tasks = monitor.getTasks();
            var newTasks = tasks.stream()
                    .filter(t -> !baselineTaskIds.contains(t.getTaskId()))
                    .toList();
            if (!newTasks.isEmpty()) {
                newTasksSeen = true;
            }

            boolean allTasksDone = newTasksSeen && newTasks.stream().allMatch(t ->
                    "completed".equals(t.getStatus()) || "cancelled".equals(t.getStatus()));

            if (allTasksDone) {
                if (tasksAllDoneTime == 0) {
                    tasksAllDoneTime = now;
                }
                if (allMembersIdle(monitor)) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (allMembersIdle(monitor)) {
                        System.out.println("[结束] 本轮所有任务已进入终态");
                        return;
                    }
                }
                if (now - tasksAllDoneTime >= settleTimeoutMs) {
                    System.out.println("[结束] 本轮所有任务已进入终态"
                            + "（部分成员仍在收尾，已强制结束等待）");
                    return;
                }
            }

            if (!streamThread.isAlive() && !newTasksSeen && now - start > 5000) {
                System.out.println("[结束] Leader 已完成回复");
                return;
            }

            if (now - lastHeartbeat >= 30_000) {
                lastHeartbeat = now;
                long elapsed = (now - start) / 1000;
                long done = newTasks.stream()
                        .filter(t -> "completed".equals(t.getStatus())).count();
                long running = newTasks.stream()
                        .filter(t -> "claimed".equals(t.getStatus())).count();
                long blocked = newTasks.stream()
                        .filter(t -> "blocked".equals(t.getStatus())).count();
                long pending = newTasks.stream()
                        .filter(t -> "pending".equals(t.getStatus())).count();
                String settleHint = allTasksDone ? " · 等待成员收尾 "
                        + ((now - tasksAllDoneTime) / 1000) + "s" : "";
                System.out.println("[心跳] 运行 " + elapsed + "s · 本轮任务 " + newTasks.size()
                        + " 个（完成 " + done + " / 进行中 " + running
                        + " / 阻塞 " + blocked + " / 待领取 " + pending + "）" + settleHint);
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static boolean allMembersIdle(TeamMonitor monitor) {
        return monitor.getMembers().stream()
                .noneMatch(m -> "busy".equals(m.getStatus()));
    }

    private static Set<String> collectTaskIds(TeamMonitor monitor) {
        Set<String> ids = new HashSet<>();
        for (var task : monitor.getTasks()) {
            ids.add(task.getTaskId());
        }
        return ids;
    }

    private static TeamAssembler.ModelSettings loadModelSettings() {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("apiconfig.json")) {
            if (in == null) {
                throw new IllegalStateException(
                        "classpath 未找到 apiconfig.json，请先配置模型连接信息");
            }
            JsonNode root = MAPPER.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            String apiBase = expand(text(root, "API_BASE"));
            String provider = expand(text(root, "MODEL_PROVIDER"));
            String modelName = expand(text(root, "MODEL_NAME"));
            boolean sslVerify = !"false".equalsIgnoreCase(text(root, "LLM_SSL_VERIFY"));
            String apiKey = resolveApiKey(root, provider);

            List<String> missing = new java.util.ArrayList<>();
            if (apiBase == null || apiBase.isBlank() || apiBase.startsWith("your-")) {
                missing.add("API_BASE");
            }
            if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("your-")) {
                missing.add("API_KEY（可配 API_KEY_ENV 从环境变量取，或 API_KEY 直写/${ENV:NAME} 展开）");
            }
            if (provider == null || provider.isBlank() || provider.startsWith("your-")) {
                missing.add("MODEL_PROVIDER");
            }
            if (modelName == null || modelName.isBlank() || modelName.startsWith("your-")) {
                missing.add("MODEL_NAME");
            }
            if (!missing.isEmpty()) {
                throw new IllegalStateException("apiconfig.json 缺少或未填写: " + missing);
            }
            return new TeamAssembler.ModelSettings(apiBase, apiKey, provider, modelName, sslVerify);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("加载 apiconfig.json 失败: " + e.getMessage(), e);
        }
    }

    private static String resolveApiKey(JsonNode root, String provider) {
        String envName = expand(text(root, "API_KEY_ENV"));
        if (envName != null && !envName.isBlank()) {
            String value = System.getenv(envName.trim());
            if (value != null && !value.isBlank()) {
                return value;
            }
            throw new IllegalStateException("API_KEY_ENV 指向的环境变量 " + envName
                    + " 取不到值，请先设置该变量");
        }
        String inline = expand(text(root, "API_KEY"));
        if (inline != null && !inline.isBlank() && !inline.startsWith("your-")) {
            return inline;
        }
        if ("DeepSeek".equalsIgnoreCase(provider)) {
            for (String fallback : List.of("DEEPSEEK_API_KEY_OPENJIUWEN", "DEEPSEEK_API_KEY")) {
                String value = System.getenv(fallback);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return inline;
    }

    private static String expand(String value) {
        if (value == null || !value.contains("${")) {
            return value;
        }
        String result = value;
        int guard = 0;
        while (guard++ < 10) {
            int start = result.indexOf("${");
            int end = result.indexOf('}', start);
            if (start < 0 || end < 0) {
                break;
            }
            String token = result.substring(start + 2, end).trim();
            String name = token.regionMatches(true, 0, "ENV:", 0, 4)
                    ? token.substring(4).trim() : token;
            String replacement = System.getenv(name);
            if (replacement == null) {
                replacement = System.getProperty(name, "");
            }
            result = result.substring(0, start) + replacement + result.substring(end + 1);
        }
        return result;
    }

    private static void printBanner(TeamConfig config, TeamAssembler.ModelSettings model) {
        System.out.println("════════════ Leader-Worker 动态编排 ════════════");
        System.out.println("团队   : " + config.teamName() + "（" + config.teamDescription() + "）");
        System.out.println("Leader : " + config.leader().name() + " / " + config.leader().displayName());
        System.out.println("成员   : " + config.members().size() + " 个");
        for (TeamConfig.MemberConfig member : config.members()) {
            System.out.println("  - " + member.name() + " / " + member.displayName()
                    + " · " + firstSentence(member.description()));
        }
        System.out.println("模型   : " + model.provider() + " / " + model.modelName()
                + " @ " + model.apiBase());
        System.out.println("模式   : 交互问答（输入需求后回车执行，输入 exit 退出）");
        System.out.println("════════════════════════════════════════════════");
    }

    private static void printRoster(TeamMonitor monitor, TeamConfig config) {
        System.out.println("[自检] 团队花名册（来自 TeamBackend）：");
        for (var member : monitor.getMembers()) {
            System.out.printf("  - %-14s %-10s %s%n",
                    member.getMemberId(),
                    member.getStatus(),
                    member.getName());
        }
    }

    private static void cleanup(LeaderTeammateAgentTeam team, TeamAgent leader,
                                TeamMonitor monitor, ConsolePrinter printer,
                                boolean assembleOnly) {
        if (leader != null && printer != null) {
            try {
                leader.removeEventListener(printer);
            } catch (RuntimeException ignored) {
            }
        }
        if (monitor != null) {
            monitor.stop();
        }
        if (leader != null) {
            try {
                leader.getStreamController().closeStream();
            } catch (RuntimeException ignored) {
            }
        }
        if (team != null && !assembleOnly) {
            try {
                team.destroyTeam(true);
            } catch (RuntimeException ignored) {
            }
        } else if (leader != null) {
            try {
                leader.close();
            } catch (RuntimeException ignored) {
            }
        }
        try {
            Runner.stop().toCompletableFuture().join();
        } catch (RuntimeException ignored) {
        }
    }

    private static String firstSentence(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int index = text.indexOf('：');
        return index > 0 ? text.substring(0, index) : text;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String abbreviate(String value, int limit) {
        if (value == null) {
            return "";
        }
        String oneLine = value.replace('\n', ' ').trim();
        return oneLine.length() <= limit ? oneLine : oneLine.substring(0, limit) + "…";
    }

    private static Map<String, Object> toStringMap(Map<?, ?> raw) {
        Map<String, Object> map = new LinkedHashMap<>();
        raw.forEach((key, value) -> map.put(String.valueOf(key), value));
        return map;
    }

    private static String text(JsonNode root, String key) {
        JsonNode node = root.get(key);
        return node == null || node.isNull() ? null : node.asText();
    }
}
