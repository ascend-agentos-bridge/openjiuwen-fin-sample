/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package examples.simple_leader_worker;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 团队配置（从 classpath 的 team-config.json 加载）。
 *
 * <p>只定义角色（Leader 人设 + 成员能力），不定义任务 DAG——
 * 任务由 Leader 运行时根据成员能力和用户需求自主规划。</p>
 *
 * @param teamName        团队名（也是 ~/.openjiuwen/.agent_teams/ 下的目录名）
 * @param teamDescription 团队描述
 * @param leader          Leader 配置（名称 + 人设）
 * @param members         成员列表（名称 + 能力描述 + 角色指令）
 * @param defaultQuery    默认用户需求（命令行未传参时使用）
 */
public record TeamConfig(
        String teamName,
        String teamDescription,
        LeaderConfig leader,
        List<MemberConfig> members,
        String defaultQuery
) {

    /**
     * Leader 配置。
     *
     * @param name        Leader 成员名（不能用 team_leader / human_agent / user 等保留名）
     * @param displayName 展示名
     * @param persona     Leader 人设（决策流程：规划→派发→等待→调整→收尾）
     */
    public record LeaderConfig(String name, String displayName, String persona) {
    }

    /**
     * 成员配置。
     *
     * @param name        成员名（路由主键，不可重复）
     * @param displayName 展示名
     * @param description 能力描述——同时作为成员 persona 和 Leader 选人的依据
     * @param rolePrompt  成员首条消息：职责边界、产出文件、完成与回报协议
     */
    public record MemberConfig(String name, String displayName, String description, String rolePrompt) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 从 classpath 加载 team-config.json。
     *
     * @return 解析后的团队配置
     * @throws IllegalStateException 配置缺失或格式错误
     */
    public static TeamConfig load() {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("team-config.json")) {
            if (in == null) {
                throw new IllegalStateException("classpath 未找到 team-config.json");
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return MAPPER.readValue(json, TeamConfig.class);
        } catch (Exception e) {
            throw new IllegalStateException("加载 team-config.json 失败: " + e.getMessage(), e);
        }
    }
}
