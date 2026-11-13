/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package examples.simple_leader_worker;

import com.openjiuwen.agentteams.LeaderTeammateAgentTeam;
import com.openjiuwen.agentteams.agent.Allocation;
import com.openjiuwen.agentteams.agent.TeamAgent;
import com.openjiuwen.agentteams.schema.team.ModelPoolEntry;
import com.openjiuwen.agentteams.schema.team.TeamRole;
import com.openjiuwen.agentteams.tools.TeamBackend;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 框架适配层：把 {@link TeamConfig} 转化为 AgentTeams 运行时对象。
 *
 * <p>封装四个不可省略的框架约束：</p>
 * <ol>
 *   <li><b>模型只能通过 modelPool 注入</b>：{@code LeaderTeammateAgentTeam.Builder}
 *       没有 {@code configureModelClient}。</li>
 *   <li><b>预置成员拿不到模型</b>：{@code syncMembers} 落库时 {@code modelRefJson=null}，
 *       而成员模型只从 DB 的 model_ref_json 解析。因此成员用
 *       {@code TeamBackend.spawnMember} 预配，一次写入模型引用与角色指令。</li>
 *   <li><b>TeamRail 提示词注入失效</b>：{@code TeamRail.owner} 永远为 null，
 *       人设段进不了系统提示词。因此把「人设 + 花名册 + 规则」拼进首条 query。</li>
 *   <li><b>成员靠 send_message 懒启动、靠 claim_task(completed) 才算完成</b>：
 *       Leader 人设与成员角色指令里都写死这两点。</li>
 * </ol>
 */
public final class TeamAssembler {

    private TeamAssembler() {
    }

    /**
     * 成员通用工作约束（拼在每个成员的 rolePrompt 前面）。
     *
     * <p>不加这段时，成员会花大量回合去 find/ls 探索仓库根目录（每轮都是 LLM 调用），
     * 单任务轻易超过 5 分钟。这里直接收口。</p>
     */
    private static final String COMMON_RULES = """
            【工作方式（必须遵守）】
            - 产出文件写在工作区相对路径下（例如 reports/T1-requirements.md）。
            - 不要执行 find / ls / git / cat 等探索命令，不要去读与本任务无关的文件。
            - 不要编译或运行程序（javac / mvn / java 可能不可用，失败会白烧回合）；
              需要「自测 / 执行用例」时，用人工推演关键分支并在报告里给出预期结果。
            - 用 file_io 写文件时一次写完整个文件，不要分多次追加。
            - 单个任务的工具调用控制在 8 次以内；信息不足时按合理假设推进，并在回报里列出假设。
            - 完成后立刻 send_message 回报 leader，并马上 claim_task(task_id, status="completed")。

            【等待协议】
            - 广播消息到达时，如果你的任务还没解锁（上游未完成），
              只回一条 "[WAITING] task_id 上游未完成"，不要 claim_task，不要报 [BLOCKED]，
              等任务解锁后再动手。[BLOCKED] 只用于真正卡住、需要 leader 决策的情况。

            """;

    /**
     * 模型连接参数。
     *
     * @param apiBase   模型服务地址
     * @param apiKey    访问密钥
     * @param provider  供应商标识（如 DeepSeek / OpenAI）
     * @param modelName 模型名
     * @param sslVerify 是否校验 TLS 证书
     */
    public record ModelSettings(
            String apiBase,
            String apiKey,
            String provider,
            String modelName,
            boolean sslVerify) {
    }

    /**
     * 构建模型池条目（模型注入的唯一入口）。
     *
     * @param model 模型连接参数
     * @return 单条模型池条目
     */
    public static ModelPoolEntry buildModelEntry(ModelSettings model) {
        Map<String, Object> client = new LinkedHashMap<>();
        client.put("connectTimeoutSeconds", 15);
        client.put("streamIdleSeconds", 600);
        client.put("verify_ssl", model.sslVerify());
        client.put("max_retries", 2);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("temperature", 0.2);
        request.put("top_p", 0.9);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("client", client);
        metadata.put("request", request);

        return ModelPoolEntry.builder()
                .modelId("demo-model")
                .provider(model.provider())
                .modelName(model.modelName())
                .apiKey(model.apiKey())
                .apiBaseUrl(model.apiBase())
                .description("Leader-Worker 演示模型")
                .weight(1)
                .metadata(metadata)
                .build();
    }

    /**
     * 装配团队（只含 Leader，成员稍后预配）。
     *
     * @param config 团队配置
     * @param entry  模型池条目
     * @return 团队门面，调用 {@code agent()} 时才真正创建运行时
     */
    public static LeaderTeammateAgentTeam buildTeam(TeamConfig config, ModelPoolEntry entry) {
        return LeaderTeammateAgentTeam.builder()
                .teamName(config.teamName())
                .description(config.teamDescription())
                .lifecycle(LeaderTeammateAgentTeam.LIFECYCLE_PERSISTENT)
                .teammateMode(LeaderTeammateAgentTeam.TEAMMATE_MODE_BUILD)
                .spawnMode(LeaderTeammateAgentTeam.SPAWN_MODE_INPROCESS)
                .transport(LeaderTeammateAgentTeam.TRANSPORT_INPROCESS)
                .storage(LeaderTeammateAgentTeam.STORAGE_MEMORY)
                .teamMode(LeaderTeammateAgentTeam.TEAM_MODE_HYBRID)
                .language("cn")
                .leaderMemberName(config.leader().name())
                .leaderDisplayName(config.leader().displayName())
                .leaderPersona(config.leader().persona())
                .modelPool(List.of(entry))
                .modelPoolStrategy("round_robin")
                .build();
    }

    /**
     * 预配全部成员（spawnMember 是唯一能同时写入模型引用 + 角色指令的入口）。
     *
     * @param leader  已创建的 Leader 运行时
     * @param config  团队配置
     * @param entry   模型池条目（与 buildTeam 使用同一实例）
     * @return 成功预配的成员名列表
     */
    public static List<String> provisionMembers(TeamAgent leader, TeamConfig config, ModelPoolEntry entry) {
        TeamBackend backend = leader.getTeamBackend();
        if (backend == null) {
            throw new IllegalStateException("Leader 的 TeamBackend 尚未初始化，无法预配成员");
        }
        List<String> bound = new ArrayList<>();
        for (TeamConfig.MemberConfig member : config.members()) {
            backend.spawnMember(TeamBackend.SpawnMemberParams.builder()
                    .memberName(member.name())
                    .displayName(member.displayName())
                    .agentCard(AgentCard.builder()
                            .id(member.name())
                            .name(member.displayName())
                            .description(member.description())
                            .build())
                    .role(TeamRole.MEMBER)
                    .prompt(COMMON_RULES + member.rolePrompt())
                    .allocation(new Allocation(entry, 0))
                    .build()).join();
            bound.add(member.name());
        }
        return bound;
    }

    /**
     * 拼装 Leader 的首条输入。
     *
     * <p>把「人设 + 花名册 + 硬性规则 + 用户需求」拼进首条 query，
     * 绕过 TeamRail 提示词注入失效的问题。</p>
     *
     * @param config    团队配置
     * @param userQuery 用户需求
     * @return 带团队约定的完整 query
     */
    public static String composeFirstQuery(TeamConfig config, String userQuery) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("# 团队协作约定（最高优先级，覆盖系统提示词里的通用工作流）\n\n");
        sb.append(config.leader().persona()).append("\n\n");

        sb.append("# 可用团队成员（按需选人，禁止 spawn_member）\n\n");
        for (TeamConfig.MemberConfig member : config.members()) {
            sb.append("- member_name=").append(member.name())
                    .append(" display_name=").append(member.displayName())
                    .append(" :: ").append(member.description()).append('\n');
        }

        sb.append("""

                # 硬性规则

                1. 团队已经建好，不要调用 build_team；成员已经就位，不要调用 spawn_member。
                2. 按需求范围按需选人：
                   - 技术问答/知识咨询 → 直接回答，不建任务、不派成员。
                   - 部分环节需求 → 只为相关能力的成员建任务，跳过不需要的成员。
                     例如用户只要设计 → 只给 req_analyst、architect 建任务，不建编码/测试/评审任务。
                   - 全流程交付 → 为全部相关成员建任务 DAG。
                   - 不属于团队能力范围（如查天气、写诗、翻译、画画等）→ 友好拒绝，不建任务。
                   需要建任务时，先 list_members 确认成员，再 create_task。
                   task_id 用 T1、T2…，assignee 用上面的 member_name，dependencies 描述依赖关系。
                   有依赖的串行，无依赖的可并行——根据任务实际关系判断，不要人为制造不必要的串行。
                3. 建完任务必须 send_message(to="*") 广播启动指令（成员只有收到消息才会启动），
                   指令里逐人写清 task_id 与产出要求。
                4. 每完成一步用 view_task 核对看板；成员回报含 [BLOCKED]/[FAILED]/[REVIEW_REJECT]
                   时按人设追加返工任务并派发。
                5. 全部任务进入终态后，用 send_message(to="user") 发出交付汇总。

                # 用户需求

                """);
        sb.append(userQuery == null ? "" : userQuery.trim()).append('\n');
        return sb.toString();
    }
}
