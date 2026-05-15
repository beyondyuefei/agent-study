package com.kuoge.agentstudy.production.cost;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 成本追踪器 —— 记录每次请求的成本，支持分析和预算控制。
 *
 * <p>生产级需要追踪：
 * <ul>
 *   <li>输入/输出 token 数</li>
 *   <li>每次 LLM 调用的成本（按模型定价计算）</li>
 *   <li>工具执行耗时</li>
 *   <li>缓存命中率</li>
 * </ul>
 */
@Getter
public class CostTracker {

    private final List<CallRecord> records = new ArrayList<>();

    private int totalInputTokens = 0;
    private int totalOutputTokens = 0;
    private int totalToolCalls = 0;
    private long totalToolExecutionMs = 0;

    /**
     * 记录一次 LLM 调用。
     */
    public void recordLlmCall(int inputTokens, int outputTokens, long latencyMs, String model) {
        records.add(new CallRecord(CallType.LLM, inputTokens, outputTokens, latencyMs, model));
        totalInputTokens += inputTokens;
        totalOutputTokens += outputTokens;
    }

    /**
     * 记录一次工具调用。
     */
    public void recordToolCall(String toolName, long executionMs) {
        records.add(new CallRecord(CallType.TOOL, 0, 0, executionMs, toolName));
        totalToolCalls++;
        totalToolExecutionMs += executionMs;
    }

    /**
     * 估算总成本（USD）。
     *
     * @param inputPricePer1k  输入价格（每 1k tokens）
     * @param outputPricePer1k 输出价格（每 1k tokens）
     */
    public double estimateCost(double inputPricePer1k, double outputPricePer1k) {
        return (totalInputTokens / 1000.0) * inputPricePer1k
                + (totalOutputTokens / 1000.0) * outputPricePer1k;
    }

    public String report() {
        return "CostTracker[llmCalls=%d, inputTokens=%d, outputTokens=%d, toolCalls=%d, toolTime=%dms]"
                .formatted(records.stream().filter(r -> r.type == CallType.LLM).count(),
                        totalInputTokens, totalOutputTokens, totalToolCalls, totalToolExecutionMs);
    }

    public record CallRecord(
            CallType type,
            int inputTokens,
            int outputTokens,
            long latencyMs,
            String detail
    ) {}

    public enum CallType { LLM, TOOL }
}
