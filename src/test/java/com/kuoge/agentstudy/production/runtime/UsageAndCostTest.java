package com.kuoge.agentstudy.production.runtime;

import com.kuoge.agentstudy.production.runtime.usage.CostEstimator;
import com.kuoge.agentstudy.production.runtime.usage.TokenUsage;
import com.kuoge.agentstudy.production.runtime.usage.UsageTracker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用量追踪与成本估算测试。
 */
class UsageAndCostTest {

    @Test
    void tokenUsage_addition() {
        TokenUsage a = new TokenUsage(10, 5, 2, 1);
        TokenUsage b = new TokenUsage(20, 8, 3, 2);
        TokenUsage sum = a.add(b);

        assertEquals(30, sum.inputTokens());
        assertEquals(13, sum.outputTokens());
        assertEquals(5, sum.cacheCreationInputTokens());
        assertEquals(3, sum.cacheReadInputTokens());
        assertEquals(51, sum.totalTokens());
    }

    @Test
    void tokenUsage_empty() {
        TokenUsage empty = TokenUsage.empty();
        assertEquals(0, empty.totalTokens());
    }

    @Test
    void tokenUsage_negativeValues_clampedToZero() {
        TokenUsage u = new TokenUsage(-5, -3, -1, -2);
        assertEquals(0, u.inputTokens());
        assertEquals(0, u.outputTokens());
        assertEquals(0, u.totalTokens());
    }

    @Test
    void usageTracker_cumulativeTracking() {
        UsageTracker tracker = new UsageTracker();
        tracker.record(new TokenUsage(100, 50));
        tracker.record(new TokenUsage(200, 80));

        assertEquals(2, tracker.getTurns());
        assertEquals(200, tracker.getLatestTurn().inputTokens());
        assertEquals(80, tracker.getLatestTurn().outputTokens());
        assertEquals(300, tracker.getCumulative().inputTokens());
        assertEquals(130, tracker.getCumulative().outputTokens());
    }

    @Test
    void costEstimator_sonnetPricing() {
        CostEstimator estimator = new CostEstimator();
        TokenUsage usage = new TokenUsage(1_000_000, 500_000, 100_000, 200_000);

        CostEstimator.CostEstimate cost = estimator.estimate(usage, "claude-sonnet");
        double expectedTotal = 15.0 + 37.5 + 1.875 + 0.3; // input + output + cache_create + cache_read
        assertEquals(expectedTotal, cost.totalCostUsd(), 0.001);
        assertTrue(cost.formattedTotal().startsWith("$"));
    }

    @Test
    void costEstimator_haikuIsCheaperThanSonnet() {
        CostEstimator estimator = new CostEstimator();
        TokenUsage usage = new TokenUsage(1_000_000, 500_000);

        CostEstimator.CostEstimate haiku = estimator.estimate(usage, "claude-haiku");
        CostEstimator.CostEstimate sonnet = estimator.estimate(usage, "claude-sonnet");

        assertTrue(haiku.totalCostUsd() < sonnet.totalCostUsd(),
                "Haiku should be cheaper than Sonnet");
    }

    @Test
    void costEstimator_qwenPlus() {
        CostEstimator estimator = new CostEstimator();
        TokenUsage usage = new TokenUsage(1_000_000, 500_000);

        CostEstimator.CostEstimate cost = estimator.estimate(usage, "qwen-plus");
        assertTrue(cost.totalCostUsd() > 0);
    }

    @Test
    void costEstimator_unknownModelFallsBackToSonnet() {
        CostEstimator estimator = new CostEstimator();
        TokenUsage usage = new TokenUsage(1_000_000, 0);

        CostEstimator.CostEstimate unknown = estimator.estimate(usage, "unknown-model-xyz");
        CostEstimator.CostEstimate sonnet = estimator.estimate(usage, "claude-sonnet");

        assertEquals(sonnet.inputCostUsd(), unknown.inputCostUsd(), 0.001);
    }

    @Test
    void costEstimator_customPricing() {
        CostEstimator estimator = new CostEstimator();
        estimator.registerPricing("custom", new CostEstimator.ModelPricing(1.0, 2.0, 0.0, 0.0));

        CostEstimator.CostEstimate cost = estimator.estimate(new TokenUsage(1_000_000, 0), "custom");
        assertEquals(1.0, cost.totalCostUsd(), 0.001);
    }

    @Test
    void usageTracker_summaryContainsData() {
        UsageTracker tracker = new UsageTracker();
        tracker.record(new TokenUsage(100, 50));
        String summary = tracker.summary();
        assertTrue(summary.contains("turns=1"));
        assertTrue(summary.contains("total_tokens=150"));
        assertTrue(summary.contains("$")); // cost estimate
    }
}
