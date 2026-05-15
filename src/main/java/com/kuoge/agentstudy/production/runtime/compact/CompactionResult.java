package com.kuoge.agentstudy.production.runtime.compact;

import com.kuoge.agentstudy.production.runtime.session.AgentSession;

/**
 * 会话压缩结果。
 *
 * <p>对应 claw-code Rust 实现：{@code compact.rs/CompactionResult}
 */
public record CompactionResult(
        String summary,
        String formattedSummary,
        AgentSession compactedSession,
        int removedMessageCount
) {

    /**
     * 空结果（未进行压缩）。
     */
    public static CompactionResult empty(AgentSession session) {
        return new CompactionResult("", "", session, 0);
    }

    public boolean wasCompacted() {
        return removedMessageCount > 0;
    }

    @Override
    public String toString() {
        if (!wasCompacted()) {
            return "CompactionResult{no compaction needed}";
        }
        return String.format("CompactionResult{removed=%d, summary='%s...'}",
                removedMessageCount,
                formattedSummary.substring(0, Math.min(formattedSummary.length(), 60)));
    }
}
