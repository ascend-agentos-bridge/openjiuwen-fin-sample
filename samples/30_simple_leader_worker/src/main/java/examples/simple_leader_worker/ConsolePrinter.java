/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package examples.simple_leader_worker;

import com.openjiuwen.agentteams.monitor.TeamMonitor;
import com.openjiuwen.agentteams.schema.events.EventMessage;

import java.io.PrintStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 简单事件打印器 + 结束任务板摘要。
 *
 * <p>实现 {@link Consumer}，注册为 Leader 的事件监听器。
 * 事件在框架发布线程同步回调，这里直接 println——足够简单，
 * 如果事件频率过高再考虑加队列。</p>
 */
public final class ConsolePrinter implements Consumer<EventMessage> {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final String leaderName;
    private final PrintStream out;

    /**
     * 构造打印器。
     *
     * @param leaderName Leader 成员名（用于区分派发与回报）
     * @param out        输出流
     */
    public ConsolePrinter(String leaderName, PrintStream out) {
        this.leaderName = leaderName;
        this.out = out;
    }

    @Override
    public void accept(EventMessage event) {
        if (event == null || event.getEventType() == null) {
            return;
        }
        String type = event.getEventType();
        Map<String, Object> payload = event.getPayload() == null ? Map.of() : event.getPayload();
        String taskId = str(payload, "task_id");
        String member = str(payload, "member_name");
        String from = str(payload, "from_member_name");
        String to = str(payload, "to_member_name");
        String time = LocalTime.now().format(TIME);

        switch (type) {
            case "task_created" -> out.printf("[%s][任务] %s 已创建%s%n", time, taskId,
                    member.isBlank() ? "" : " → " + member);
            case "task_claimed" -> {
                if (member.isBlank()) {
                    out.printf("[%s][任务] %s 被领取%n", time, taskId);
                } else {
                    out.printf("[%s][任务] %s 预分配给 %s%n", time, taskId, member);
                }
            }
            case "task_completed" -> out.printf("[%s][任务] %s 已完成%n", time, taskId);
            case "task_cancelled" -> out.printf("[%s][任务] %s 已取消%n", time, taskId);
            case "task_unblocked" -> out.printf("[%s][任务] %s 依赖满足，已解锁%n", time, taskId);
            case "message" -> out.printf("[%s][消息] %s → %s%n", time, from, to);
            case "broadcast" -> out.printf("[%s][广播] %s → 全员%n", time, from);
            case "member_spawned" -> out.printf("[%s][成员] %s 就位%n", time, member);
            case "member_status_changed" -> out.printf("[%s][成员] %s 状态: %s → %s%n",
                    time, member, str(payload, "old_status"), str(payload, "new_status"));
            case "team_completed" -> out.printf("[%s][完成] 团队任务全部结束（成员 %s 人 / 任务 %s 个）%n",
                    time, str(payload, "member_count"), str(payload, "task_count"));
            case "team_cleaned" -> out.printf("[%s][解散] 团队已清理%n", time);
            default -> { }
        }
    }

    /**
     * 打印结束时的任务板与交付汇总。
     *
     * @param monitor 团队监控器
     */
    public void printSummary(TeamMonitor monitor) {
        out.println();
        out.println("════════════ 任务板 ════════════");
        var tasks = monitor.getTasks();
        if (tasks.isEmpty()) {
            out.println("  （无任务）");
        } else {
            for (var task : tasks) {
                out.printf("  %-6s %-8s %-14s %s%n",
                        task.getTaskId(),
                        statusLabel(task.getStatus()),
                        blankToDash(task.getAssignee()),
                        blankToDash(task.getTitle()));
            }
        }

        out.println();
        out.println("【交付汇总】");
        var userMessages = monitor.getMessages("user", leaderName);
        if (userMessages.isEmpty()) {
            out.println("  （未收到 Leader 发送给 user 的汇总）");
        } else {
            for (var msg : userMessages) {
                String content = msg.getContent();
                if (content != null && !content.isBlank()) {
                    for (String line : content.split("\n")) {
                        out.println("  " + line);
                    }
                }
            }
        }
        out.println("═══════════════════════════════");
    }

    /**
     * 打印本轮任务板与交付汇总（只显示本轮新增的任务）。
     *
     * @param monitor         团队监控器
     * @param baselineTaskIds 本轮开始前已存在的任务 ID 集合
     */
    public void printRoundSummary(TeamMonitor monitor, Set<String> baselineTaskIds) {
        out.println();
        out.println("════════════ 本轮任务板 ════════════");
        var tasks = monitor.getTasks();
        var newTasks = tasks.stream()
                .filter(t -> !baselineTaskIds.contains(t.getTaskId()))
                .toList();
        if (newTasks.isEmpty()) {
            out.println("  （本轮无任务，Leader 直接回复）");
        } else {
            for (var task : newTasks) {
                out.printf("  %-6s %-8s %-14s %s%n",
                        task.getTaskId(),
                        statusLabel(task.getStatus()),
                        blankToDash(task.getAssignee()),
                        blankToDash(task.getTitle()));
            }
        }

        out.println();
        out.println("【交付汇总】");
        var userMessages = monitor.getMessages("user", leaderName);
        if (userMessages.isEmpty()) {
            out.println("  （未收到 Leader 发送给 user 的汇总）");
        } else {
            var lastMsg = userMessages.get(userMessages.size() - 1);
            String content = lastMsg.getContent();
            if (content != null && !content.isBlank()) {
                for (String line : content.split("\n")) {
                    out.println("  " + line);
                }
            }
        }
        out.println("═══════════════════════════════");
    }

    private static String statusLabel(String status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case "pending" -> "待领取";
            case "blocked" -> "阻塞";
            case "claimed" -> "进行中";
            case "completed" -> "已完成";
            case "cancelled" -> "已取消";
            default -> status;
        };
    }

    private static String str(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
