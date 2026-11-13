/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package examples.simple_leader_worker;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TeamConfigTest {

    private static final Set<String> RESERVED_NAMES =
            Set.of("team_leader", "human_agent", "user");

    @Test
    void configLoadsFromClasspathWithFiveMembers() {
        TeamConfig config = TeamConfig.load();

        assertThat(config.teamName()).isEqualTo("demo_team");
        assertThat(config.members()).hasSize(5);
        assertThat(config.defaultQuery()).isNotBlank();
    }

    @Test
    void memberNamesAreUniqueAndNotReserved() {
        TeamConfig config = TeamConfig.load();

        List<String> names = config.members().stream()
                .map(TeamConfig.MemberConfig::name)
                .toList();
        assertThat(names).doesNotHaveDuplicates();
        for (String name : names) {
            assertThat(name).isNotIn(RESERVED_NAMES);
        }
    }

    @Test
    void leaderPersonaContainsCoreInstructions() {
        TeamConfig config = TeamConfig.load();
        String persona = config.leader().persona();

        assertThat(persona).contains("list_members");
        assertThat(persona).contains("create_task");
        assertThat(persona).contains("send_message");
        assertThat(persona).contains("动态调整");
    }

    @Test
    void everyMemberHasDescriptionAndRolePrompt() {
        TeamConfig config = TeamConfig.load();

        assertThat(config.members()).allSatisfy(member -> {
            assertThat(member.description()).isNotBlank();
            assertThat(member.rolePrompt()).isNotBlank();
            assertThat(member.rolePrompt()).contains("claim_task");
        });
    }

    @Test
    void composeFirstQueryCarriesPersonaRosterAndRules() {
        TeamConfig config = TeamConfig.load();
        String query = "测试需求";

        String briefing = TeamAssembler.composeFirstQuery(config, query);

        assertThat(briefing).contains(config.leader().persona());
        assertThat(briefing).endsWith(query + "\n");
        for (TeamConfig.MemberConfig member : config.members()) {
            assertThat(briefing).contains("member_name=" + member.name());
        }
        assertThat(briefing).contains("不要调用 build_team");
        assertThat(briefing).contains("不要调用 spawn_member");
    }

    @Test
    void composeFirstQueryHandlesNullQuery() {
        TeamConfig config = TeamConfig.load();

        String briefing = TeamAssembler.composeFirstQuery(config, null);

        assertThat(briefing).contains("# 用户需求");
    }
}
