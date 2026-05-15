package com.kuoge.agentstudy.production.runtime.core;

import com.kuoge.agentstudy.production.runtime.compact.CompactionResult;
import com.kuoge.agentstudy.production.runtime.session.ConversationMessage;
import com.kuoge.agentstudy.production.runtime.usage.TokenUsage;
import lombok.Builder;

import java.util.List;

/**
 * 单次 Turn 的执行摘要。
 *
 * <p>对应 claw-code Rust 实现：{@code conversation.rs/TurnSummary}
 *
 * <p>一次 Turn 可能包含多轮 LLM 调用（每轮对应一个 Assistant 消息 + 0-N 个 ToolResult）。
 */
@Builder
public record TurnSummary(
        String userInput,
        List<ConversationMessage> assistantMessages,
        List<ConversationMessage> toolResults,
        int iterations,
        TokenUsage usage,
        String stopReason,
        CompactionResult autoCompaction
) {

    public TurnSummary {
        assistantMessages = assistantMessages != null ? List.copyOf(assistantMessages) : List.of();
        toolResults = toolResults != null ? List.copyOf(toolResults) : List.of();
        usage = usage != null ? usage : TokenUsage.empty();
        stopReason = stopReason != null ? stopReason : "unknown";
    }

    public boolean wasCompacted() {
        return autoCompaction != null && autoCompaction.wasCompacted();
    }

    public String finalAnswer() {
        if (assistantMessages.isEmpty()) return "";
        ConversationMessage last = assistantMessages.get(assistantMessages.size() - 1);
        return last.extractText();
    }

    @Override
    public String toString() {
        return String.format(
                "TurnSummary{iterations=%d, assistantMsgs=%d, toolResults=%d, usage=%s, stopReason='%s'%s}",
                iterations,
                assistantMessages.size(),
                toolResults.size(),
                usage,
                stopReason,
                wasCompacted() ? ", compacted=" + autoCompaction.removedMessageCount() : ""
        );
    }
}
