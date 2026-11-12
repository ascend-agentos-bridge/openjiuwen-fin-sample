package com.openjiuwen.samples.creditmemory.agentmemory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent Memory 的五维 Scope 中本样例使用的四维：org / user / agent / session。
 *
 * <p><b>dev auth 下的身份约束</b>：本机 Agent Memory 以 {@code --auth-mode dev} 运行，
 * 服务端固定身份为 {@code local/developer}，写操作要求 target 的 org/user 与认证身份一致，
 * 直接写 {@code user=客户ID} 会返回 403。因此样例沿用官方 Python 适配器
 * （{@code agent_memory_provider.py} §6.2-D）的「身份覆盖式」映射：</p>
 *
 * <ul>
 *   <li>{@code org} / {@code user}：认证身份（dev 缺省 {@code local} / {@code developer}）；</li>
 *   <li>{@code agent}：语义上的 Agent（本样例 {@code credit-agent}）；</li>
 *   <li>{@code session}：语义上的「客户/会话」轴（由 rail 的 {@code user_id} 下沉而来）。</li>
 * </ul>
 *
 * <p>生产部署接入真实认证后，把 {@code user} 配成客户 ID 即可回到五维直映射；
 * 映射值是 {@link AgentMemoryConfig} 的配置项，不在代码里写死。</p>
 */
public record AgentMemoryScope(String org, String user, String agent, String session) {

    public AgentMemoryScope {
        org = blankToDefault(org, AgentMemoryConfig.DEFAULT_ORG);
        user = blankToDefault(user, AgentMemoryConfig.DEFAULT_USER);
        agent = agent == null ? "" : agent.strip();
        session = session == null ? "" : session.strip();
    }

    /** 序列化为 /v1/* 请求体里的 scope 对象。 */
    public Map<String, Object> toMap() {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("org", org);
        scope.put("user", user);
        scope.put("agent", agent);
        scope.put("session", session);
        return scope;
    }

    /** 日志/打印用的可读形式。 */
    public String display() {
        return "org=" + org + ", user=" + user + ", agent=" + agent + ", session=" + session;
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
