package com.kuoge.agentstudy.production.runtime.usage;

import lombok.Getter;

/**
 * 会话级 Token 用量累积追踪器。
 *
 * <p>对应 claw-code Rust 实现：{@code usage.rs/UsageTracker}
 *
 * <p>追踪三个维度：
 * <ul>
 *   <li><b>latestTurn</b>：最近一次 turn 的用量</li>
 *   <li><b>cumulative</b>：整个会话的累积用量</li>
 *   <li><b>turns</b>：已完成的 turn 次数</li>
 * </ul>
 */
public class UsageTracker {

    @Getter
    private TokenUsage latestTurn = TokenUsage.empty();
    @Getter
    private TokenUsage cumulative = TokenUsage.empty();
    @Getter
    private int turns = 0;

    /**
     * 记录一次 turn 的用量。
     */
    public void record(TokenUsage usage) {
        this.latestTurn = usage;
        this.cumulative = this.cumulative.add(usage);
        this.turns++;
    }

    /**
     * 从会话消息中重建用量（用于恢复持久化的会话）。
     */
    public void recordFromMessages(int totalInputTokens, int totalOutputTokens) {
        this.cumulative = new TokenUsage(totalInputTokens, totalOutputTokens);
    }

    /**
     * 生成用量摘要报告。
     */
    public String summary() {
        CostEstimator estimator = new CostEstimator();
        CostEstimator.CostEstimate cost = estimator.estimate(cumulative);
        return String.format(
                "Usage Summary: turns=%d, total_tokens=%d, input=%d, output=%d, " +
                "estimated_cost=%s",
                turns,
                cumulative.totalTokens(),
                cumulative.inputTokens(),
                cumulative.outputTokens(),
                cost.formattedTotal()
        );
    }
}
