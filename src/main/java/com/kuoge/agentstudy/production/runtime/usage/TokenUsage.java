package com.kuoge.agentstudy.production.runtime.usage;

/**
 * 单次 LLM 调用的 Token 用量。
 *
 * <p>对应 claw-code Rust 实现：{@code usage.rs/TokenUsage}
 *
 * <p>包含四类 token（对应 Anthropic API 的计费维度）：
 * <ul>
 *   <li>{@code inputTokens} — 输入 prompt 的 token</li>
 *   <li>{@code outputTokens} — 输出内容的 token</li>
 *   <li>{@code cacheCreationInputTokens} — 缓存写入（首次创建缓存）</li>
 *   <li>{@code cacheReadInputTokens} — 缓存读取（命中已有缓存）</li>
 * </ul>
 */
public record TokenUsage(
        int inputTokens,
        int outputTokens,
        int cacheCreationInputTokens,
        int cacheReadInputTokens
) {

    public TokenUsage {
        inputTokens = Math.max(0, inputTokens);
        outputTokens = Math.max(0, outputTokens);
        cacheCreationInputTokens = Math.max(0, cacheCreationInputTokens);
        cacheReadInputTokens = Math.max(0, cacheReadInputTokens);
    }

    /**
     * 仅含输入/输出的简化用量。
     */
    public TokenUsage(int inputTokens, int outputTokens) {
        this(inputTokens, outputTokens, 0, 0);
    }

    public static TokenUsage empty() {
        return new TokenUsage(0, 0, 0, 0);
    }

    /**
     * 总 token 数（所有维度之和）。
     */
    public int totalTokens() {
        return inputTokens + outputTokens + cacheCreationInputTokens + cacheReadInputTokens;
    }

    /**
     * 两两相加。
     */
    public TokenUsage add(TokenUsage other) {
        return new TokenUsage(
                this.inputTokens + other.inputTokens,
                this.outputTokens + other.outputTokens,
                this.cacheCreationInputTokens + other.cacheCreationInputTokens,
                this.cacheReadInputTokens + other.cacheReadInputTokens
        );
    }

    @Override
    public String toString() {
        return String.format("TokenUsage{in=%d, out=%d, cache_create=%d, cache_read=%d, total=%d}",
                inputTokens, outputTokens, cacheCreationInputTokens, cacheReadInputTokens, totalTokens());
    }
}
