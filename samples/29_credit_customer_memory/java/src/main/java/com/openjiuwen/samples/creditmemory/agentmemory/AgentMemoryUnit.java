package com.openjiuwen.samples.creditmemory.agentmemory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent Memory 的一条记忆单元（{@code /v1/add}、{@code /v1/list}、{@code /v1/update} 返回）。
 *
 * <p>HTTP 契约（S02）：{@code add} 返回顶层数组；单元正文在 {@code segments[0].content}；
 * {@code update} 通过 {@code supersedes} 指向被替换的旧版本，旧版本 {@code lifecycle=superseded}。</p>
 */
public record AgentMemoryUnit(String unitId, String content, String lifecycle, String supersedes,
                              String scopeDisplay, Map<String, Object> systemMetadata) {

    public AgentMemoryUnit {
        unitId = unitId == null ? "" : unitId;
        content = content == null ? "" : content;
        lifecycle = lifecycle == null ? "" : lifecycle;
        supersedes = supersedes == null ? "" : supersedes;
        scopeDisplay = scopeDisplay == null ? "" : scopeDisplay;
        systemMetadata = systemMetadata == null || systemMetadata.isEmpty()
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(systemMetadata));
    }

    public boolean active() {
        return "active".equals(lifecycle);
    }

    public String mdTitle() {
        Object title = systemMetadata.get("md_title");
        return title == null ? "" : String.valueOf(title);
    }
}
