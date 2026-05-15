package com.kuoge.agentstudy.production.runtime.client;

import com.kuoge.agentstudy.production.runtime.session.ContentBlock;
import com.kuoge.agentstudy.production.runtime.usage.TokenUsage;
import lombok.Builder;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 结构化响应。
 *
 * <p>对应 claw-code Rust 实现中 {@code conversation.rs/AssistantEvent} 的聚合结果：
 * 将流式事件（TextDelta、ToolUse、Thinking、Usage）聚合成一条完整的消息。
 *
 * <p>与教学版 {@link com.kuoge.agentstudy.tutorial.ReActLoop.LlmResponse} 的区别：
 * <ul>
 *   <li>支持多个 ContentBlock（一条消息可同时包含文本 + 多个工具调用）</li>
 *   <li>支持 Thinking block（Claude 的 extended thinking）</li>
 *   <li>附带 TokenUsage（精确用量追踪）</li>
 * </ul>
 */
@Builder
public record LlmResponse(
        List<ContentBlock> blocks,
        TokenUsage usage
) {

    public LlmResponse {
        blocks = blocks != null ? List.copyOf(blocks) : List.of();
        usage = usage != null ? usage : TokenUsage.empty();
    }

    /**
     * 快速构造纯文本响应。
     */
    public static LlmResponse text(String text) {
        return new LlmResponse(
                List.of(new ContentBlock.TextBlock(text)),
                TokenUsage.empty()
        );
    }

    /**
     * 快速构造纯文本响应（带用量）。
     */
    public static LlmResponse text(String text, TokenUsage usage) {
        return new LlmResponse(List.of(new ContentBlock.TextBlock(text)), usage);
    }

    /**
     * 构造包含工具调用的响应。
     */
    public static LlmResponse withToolUse(String toolUseId, String toolName, String input) {
        return new LlmResponse(
                List.of(new ContentBlock.ToolUseBlock(toolUseId, toolName, input)),
                TokenUsage.empty()
        );
    }

    /**
     * 提取响应中的所有纯文本内容。
     */
    public String extractText() {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof ContentBlock.TextBlock t) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }

    /**
     * 提取响应中的所有思考内容。
     */
    public String extractThinking() {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof ContentBlock.ThinkingBlock t) {
                sb.append(t.thinking());
            }
        }
        return sb.toString();
    }

    /**
     * 提取响应中的所有工具调用请求。
     */
    public List<ContentBlock.ToolUseBlock> toolUses() {
        return blocks.stream()
                .filter(b -> b instanceof ContentBlock.ToolUseBlock)
                .map(b -> (ContentBlock.ToolUseBlock) b)
                .toList();
    }

    /**
     * 是否包含工具调用请求。
     */
    public boolean hasToolUse() {
        return blocks.stream().anyMatch(b -> b instanceof ContentBlock.ToolUseBlock);
    }

    /**
     * 获取文本内容（便捷方法）。
     */
    public String text() {
        return extractText();
    }

    /**
     * 是否为空响应。
     *
     * <p>注意：包含 ToolUseBlock 的响应不算空（模型明确请求了工具）。
     */
    public boolean isEmpty() {
        if (blocks.isEmpty()) {
            return true;
        }
        // 如果有 ToolUse 或 Thinking，不算空
        if (hasToolUse() || !extractThinking().isBlank()) {
            return false;
        }
        // 只剩下 TextBlock，检查是否有实质内容
        return extractText().isBlank();
    }
}
