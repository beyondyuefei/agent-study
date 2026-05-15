package com.kuoge.agentstudy.production.cost;

import lombok.Getter;

/**
 * Token 预算管理 —— 生产级成本控制的基石。
 *
 * <p>设计原理：
 * <ol>
 *   <li><b>总预算制</b>：为每次请求设定 token 上限，防止意外消耗</li>
 *   <li><b>分段配额</b>：不同功能分配不同预算（如工具定义最多占 30%）</li>
 *   <li><b>预警机制</b>：接近预算上限时发出警告</li>
 * </ol>
 */
@Getter
public class TokenBudget {

    private final int maxInputTokens;     // 输入 token 上限
    private final int maxOutputTokens;    // 输出 token 上限（预留）
    private final int maxTotalTokens;     // 总上限

    private int currentInputTokens;       // 当前已用输入 token
    private int currentOutputTokens;      // 当前已用输出 token

    public TokenBudget(int maxTotalTokens, double inputRatio) {
        this.maxTotalTokens = maxTotalTokens;
        this.maxInputTokens = (int) (maxTotalTokens * inputRatio);
        this.maxOutputTokens = maxTotalTokens - maxInputTokens;
        this.currentInputTokens = 0;
        this.currentOutputTokens = 0;
    }

    /**
     * 创建标准预算（输入 70%，输出 30%）。
     */
    public static TokenBudget standard(int maxTotalTokens) {
        return new TokenBudget(maxTotalTokens, 0.7);
    }

    /**
     * 记录输入 token 消耗。
     */
    public void recordInput(int tokens) {
        this.currentInputTokens += tokens;
    }

    /**
     * 记录输出 token 消耗。
     */
    public void recordOutput(int tokens) {
        this.currentOutputTokens += tokens;
    }

    /**
     * 检查是否超出输入预算。
     */
    public boolean isInputOverBudget() {
        return currentInputTokens > maxInputTokens;
    }

    /**
     * 检查是否接近预算上限（80%）。
     */
    public boolean isNearLimit() {
        return (currentInputTokens + currentOutputTokens) > (maxTotalTokens * 0.8);
    }

    /**
     * 剩余可用输入 token 数。
     */
    public int remainingInputTokens() {
        return Math.max(0, maxInputTokens - currentInputTokens);
    }

    /**
     * 获取预算使用报告。
     */
    public String report() {
        return "TokenBudget[input=%d/%d, output=%d/%d, total=%d/%d]".formatted(
                currentInputTokens, maxInputTokens,
                currentOutputTokens, maxOutputTokens,
                currentInputTokens + currentOutputTokens, maxTotalTokens);
    }
}
