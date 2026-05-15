package com.kuoge.agentstudy.production.runtime.client;

import com.kuoge.agentstudy.production.runtime.session.ConversationMessage;

import java.util.List;

/**
 * LLM 客户端抽象接口 —— 生产级版本。
 *
 * <p>与基础版字符串上下文 LLM 客户端的关键升级：
 * <ul>
 *   <li><b>输入结构化</b>：传入 {@link ConversationMessage} 列表而非纯字符串</li>
 *   <li><b>输出结构化</b>：返回 {@link LlmResponse}，包含文本、思考、工具调用、用量</li>
 *   <li><b>流式支持预留</b>：接口设计预留流式响应扩展点</li>
 * </ul>
 *
 * <p>参考 claw-code Rust 实现：{@code conversation.rs/ApiClient}
 */
public interface LlmClient {

    /**
     * 发送对话请求到 LLM，获取完整响应。
     *
     * @param systemPrompt 系统提示词（通常不变）
     * @param messages     当前会话消息列表（不含 system prompt）
     * @return 结构化响应（包含文本、工具调用、用量）
     */
    LlmResponse call(String systemPrompt, List<ConversationMessage> messages);

    /**
     * 估算输入消息的 token 数（用于预算管理）。
     */
    default int estimateInputTokens(String systemPrompt, List<ConversationMessage> messages) {
        int tokens = systemPrompt.length() / 4 + 1;
        for (ConversationMessage msg : messages) {
            tokens += msg.estimateTokens();
        }
        return tokens;
    }
}
