package com.openjiuwen.samples.creditmemory.service;

import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.llm.schema.SystemMessage;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.samples.creditmemory.config.ModelSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 对话回答模型（与记忆抽取模型分开配置）。
 */
public final class ChatService {

    private final ModelSettings settings;
    private volatile Model model;

    public ChatService(ModelSettings settings) {
        this.settings = settings;
        this.model = settings.buildChatModel();
    }

    public void reload(ModelSettings nextSettings) {
        this.model = nextSettings.buildChatModel();
    }

    public String answer(List<SessionStore.Message> messages, MemoryService.RecallResult recall) throws Exception {
        List<BaseMessage> request = new ArrayList<>();
        request.add(new SystemMessage(systemPrompt(recall)));
        List<SessionStore.Message> history = messages.size() > 10
                ? messages.subList(messages.size() - 10, messages.size())
                : messages;
        for (SessionStore.Message message : history) {
            if ("customer".equals(message.role)) {
                request.add(new UserMessage(message.text));
            } else if ("agent".equals(message.role)) {
                request.add(new AssistantMessage(message.text));
            }
        }
        AssistantMessage reply = model.invoke(request, null, (float) settings.chat.temperature, null,
                settings.chat.model, settings.chat.maxTokens, null, null, null, Map.of());
        String content = reply == null ? "" : reply.getContentAsString();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("对话模型返回了空内容（本地推理模型可能把 token 用在了 reasoning 上，请调大 maxTokens 或换模型）");
        }
        return content.strip();
    }

    private String systemPrompt(MemoryService.RecallResult recall) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是「信贷智能体」，服务对象是客户周砚清，场景是经营贷咨询。\n");
        prompt.append("回答要自然、简洁、专业，像客户经理一样对话；不要逐条罗列记忆，也不要把内部实现、字段名、数据库、向量检索等术语告诉客户。\n");
        prompt.append("【表达约束】绝对不要在回复中说“我记下了/已记录/已记住/记住了/系统已保存/已写入”等记忆动作，也不要复述客户刚提供的全部信息；直接回应客户当前问题、推进业务即可。\n");
        prompt.append("如果客户问之前聊过什么，可以结合下面提供的历史记忆回答；不确定的信息不要编造。\n");
        if (recall != null && !recall.contextText().isBlank()) {
            prompt.append("\n【本轮检索到的客户记忆】\n").append(recall.contextText());
            prompt.append("【记忆结束】\n");
        }
        return prompt.toString();
    }
}
