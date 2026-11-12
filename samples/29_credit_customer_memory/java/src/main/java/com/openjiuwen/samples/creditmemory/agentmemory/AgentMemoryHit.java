package com.openjiuwen.samples.creditmemory.agentmemory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code POST /v1/search} 返回的一条命中。
 *
 * <p>服务端每条 item 含 {@code unit_id}、{@code score}、{@code content}/{@code abstract}、
 * {@code level}、{@code system_metadata}。样例只保留回答召回需要展示的字段。</p>
 */
public record AgentMemoryHit(String unitId, double score, String content,
                             String abstractText, String level,
                             Map<String, Object> systemMetadata) {

    public AgentMemoryHit {
        unitId = unitId == null ? "" : unitId;
        content = content == null ? "" : content;
        abstractText = abstractText == null ? "" : abstractText;
        level = level == null ? "" : level;
        systemMetadata = systemMetadata == null || systemMetadata.isEmpty()
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(systemMetadata));
    }

    /** 优先全文，其次摘要。 */
    public String displayText() {
        return content.isBlank() ? abstractText : content;
    }
}
