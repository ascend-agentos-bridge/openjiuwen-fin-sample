package com.openjiuwen.samples.creditmemory.service;

import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.output_parsers.BaseOutputParser;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;

import java.util.Map;

/**
 * 0.1.16 GraphMemory 关系抽取的兼容适配器（临时）。
 *
 * <p>0.1.16 GraphMemory 的关系 prompt 只声明了 `extractedRelations: array`，模型会自然输出
 * `subject_id / object_id / relation / content`；但同版本解析器读取的是
 * `source_id / target_id / name / fact`，导致关系被静默丢弃。这里只对关系响应做字段改名，
 * 不改 GraphMemory、不替代其 API。等 agent-core 0.1.17 修复（或上游 PR 合入）后应删除本类。</p>
 */
final class GraphRelationCompatModel extends Model {

    GraphRelationCompatModel(Model source) {
        super(source);
    }

    @Override
    public AssistantMessage invoke(Object messages, Object tools, Float temperature, Float topP, String model,
                                   Integer maxTokens, String stop, BaseOutputParser outputParser,
                                   Float timeout, Map<String, Object> kwargs) throws Exception {
        AssistantMessage result = super.invoke(messages, tools, temperature, topP, model, maxTokens, stop,
                outputParser, timeout, kwargs);
        String content = result == null ? null : result.getContentAsString();
        if (content != null && content.contains("extractedRelations") && content.contains("subject_id")) {
            String fixed = content
                    .replace("\"relation_name\"", "\"name\"")
                    .replace("\"subject_id\"", "\"source_id\"")
                    .replace("\"object_id\"", "\"target_id\"")
                    .replace("\"relation\"", "\"name\"")
                    .replace("\"content\"", "\"fact\"");
            return AssistantMessage.builder().content(fixed).build();
        }
        return result;
    }
}
