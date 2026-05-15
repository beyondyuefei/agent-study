package com.kuoge.agentstudy.production.runtime.compact;

/**
 * 会话压缩配置。
 *
 * <p>对应 claw-code Rust 实现：{@code compact.rs/CompactionConfig}
 *
 * <p>控制何时以及如何压缩会话上下文：
 * <ul>
 *   <li>{@code preserveRecentMessages} — 保留最近 N 条消息不被压缩</li>
 *   <li>{@code maxEstimatedTokens} — 可压缩部分的最大 token 数，超过则触发压缩</li>
 * </ul>
 */
public record CompactionConfig(
        int preserveRecentMessages,
        int maxEstimatedTokens
) {

    public CompactionConfig {
        preserveRecentMessages = Math.max(1, preserveRecentMessages);
        maxEstimatedTokens = Math.max(100, maxEstimatedTokens);
    }

    public static CompactionConfig defaults() {
        return new CompactionConfig(4, 10_000);
    }

    /**
     * 激进的压缩配置（用于超长会话）。
     */
    public static CompactionConfig aggressive() {
        return new CompactionConfig(2, 5_000);
    }

    /**
     * 保守的压缩配置（尽量保留历史）。
     */
    public static CompactionConfig conservative() {
        return new CompactionConfig(8, 20_000);
    }
}
