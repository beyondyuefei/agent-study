package com.kuoge.agentstudy.production.runtime.session;

import com.kuoge.agentstudy.production.runtime.usage.TokenUsage;
import lombok.Builder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 结构化对话消息。
 *
 * <p>对应 claw-code Rust 实现：{@code session.rs/ConversationMessage}
 *
 * <p>与教学版 ReActLoop 使用纯字符串拼接上下文的关键区别：
 * <ul>
 *   <li><b>多 block 结构</b>：一条消息可包含文本 + 思考 + 工具调用的组合</li>
 *   <li><b>精确识别工具调用</b>：通过 {@link ContentBlock.ToolUseBlock} 精确提取待执行工具</li>
 *   <li><b>Token 用量追踪</b>：每条消息可附带上次调用的 token 用量</li>
 * </ul>
 */
@Builder
public record ConversationMessage(
        MessageRole role,
        List<ContentBlock> blocks,
        TokenUsage usage
) {

    public ConversationMessage {
        blocks = blocks != null ? List.copyOf(blocks) : List.of();
    }

    /**
     * 快速创建用户文本消息。
     */
    public static ConversationMessage userText(String text) {
        return new ConversationMessage(
                MessageRole.USER,
                List.of(new ContentBlock.TextBlock(text)),
                null
        );
    }

    /**
     * 快速创建系统文本消息。
     */
    public static ConversationMessage systemText(String text) {
        return new ConversationMessage(
                MessageRole.SYSTEM,
                List.of(new ContentBlock.TextBlock(text)),
                null
        );
    }

    /**
     * 快速创建助手文本消息。
     */
    public static ConversationMessage assistantText(String text) {
        return new ConversationMessage(
                MessageRole.ASSISTANT,
                List.of(new ContentBlock.TextBlock(text)),
                null
        );
    }

    /**
     * 创建包含工具调用的助手消息。
     */
    public static ConversationMessage assistantWithToolUse(String toolUseId, String toolName, String input) {
        return new ConversationMessage(
                MessageRole.ASSISTANT,
                List.of(new ContentBlock.ToolUseBlock(toolUseId, toolName, input)),
                null
        );
    }

    /**
     * 创建工具执行结果消息。
     */
    public static ConversationMessage toolResult(String toolUseId, String toolName, String output, boolean isError) {
        return new ConversationMessage(
                MessageRole.TOOL,
                List.of(new ContentBlock.ToolResultBlock(toolUseId, toolName, output, isError)),
                null
        );
    }

    /**
     * 从所有 block 中提取纯文本内容（用于日志和调试）。
     */
    public String extractText() {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof ContentBlock.TextBlock t) {
                sb.append(t.text());
            } else if (block instanceof ContentBlock.ThinkingBlock th) {
                sb.append("[thinking] ").append(th.thinking());
            } else if (block instanceof ContentBlock.ToolUseBlock tu) {
                sb.append("[tool_use: ").append(tu.toolName()).append("]");
            } else if (block instanceof ContentBlock.ToolResultBlock tr) {
                sb.append("[tool_result: ").append(tr.toolName()).append("]: ").append(tr.output());
            }
        }
        return sb.toString();
    }

    /**
     * 估算该消息的总 token 数。
     */
    public int estimateTokens() {
        return blocks.stream().mapToInt(ContentBlock::estimateTokens).sum();
    }

    /**
     * 提取消息中所有的 ToolUseBlock。
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
     * 是否是错误结果。
     */
    public boolean isError() {
        return blocks.stream()
                .filter(b -> b instanceof ContentBlock.ToolResultBlock)
                .map(b -> (ContentBlock.ToolResultBlock) b)
                .anyMatch(ContentBlock.ToolResultBlock::isError);
    }
}
