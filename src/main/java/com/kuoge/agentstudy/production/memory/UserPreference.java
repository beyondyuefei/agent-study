package com.kuoge.agentstudy.production.memory;

import java.time.Instant;
import java.util.Set;

/**
 * 用户偏好条目。
 *
 * <p>设计哲学（来自生产级实践）：
 * <ol>
 *   <li><b>只存偏好，不存事实</b>："用户喜欢用 JUnit 5" 是偏好；
 *       "这个项目的订单表有 100 万行" 是事实（会变，不存）</li>
 *   <li><b>偏好稳定</b>：一旦确认用户喜欢某种代码风格，数月不变</li>
 *   <li><b>带置信度</b>：用户说了一次 ≠ 高置信度；反复确认后才提升</li>
 *   <li><b>带时间戳</b>：支持偏好衰减（很久没提的偏好置信度下降）</li>
 *   <li><b>可溯源</b>：记录偏好从哪次对话中提取的，便于验证</li>
 * </ol>
 *
 * <p>对比 Claude 的 Memory 设计：
 * Claude 的 Memory 功能同样只记录用户偏好（如"用户是素食主义者"、"用户用 Python"），
 * 不记录会话中的具体事实（如"用户昨天查了天气"）。
 */
public record UserPreference(
        String id,
        String userId,            // 用户 ID
        String category,          // 偏好分类：coding_style / communication / tech_stack / workflow
        String key,               // 偏好键：如 "test_framework"
        String value,             // 偏好值：如 "JUnit 5"
        double confidence,        // 置信度 0.0-1.0
        Instant firstObservedAt,  // 首次观察到
        Instant lastConfirmedAt,  // 最后确认时间
        int confirmationCount,    // 被确认的次数
        Set<String> sourceMessageIds  // 溯源：从哪些消息中提取的
) {

    /**
     * 偏好的有效载荷文本，用于注入 Prompt。
     */
    public String toPromptText() {
        return "- %s: %s (confidence: %.0f%%)".formatted(key, value, confidence * 100);
    }

    /**
     * 判断是否为高置信度偏好（值得注入系统提示词）。
     */
    public boolean isHighConfidence() {
        return confidence >= 0.8 && confirmationCount >= 2;
    }

    /**
     * 计算偏好的新鲜度分数（越近期确认分数越高）。
     */
    public double freshnessScore(Instant now) {
        if (lastConfirmedAt == null) return 0;
        long daysSinceConfirmation = java.time.Duration.between(lastConfirmedAt, now).toDays();
        // 指数衰减：30 天后衰减到 50%
        return Math.exp(-daysSinceConfirmation / 30.0);
    }
}
