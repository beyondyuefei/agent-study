package com.kuoge.agentstudy.production.skill.governance;

import java.time.Instant;

/**
 * 反馈 —— 隐式/显式反馈的统一抽象。
 *
 * <p>参考 claw-code 中 LaneHeartbeat 的设计：
 * 持续收集信号，用于判断 Skill 的健康状态。
 */
public record Feedback(
        String feedbackId,
        String skillId,
        String sessionId,
        String userId,
        FeedbackType feedbackType,
        FeedbackChannel channel,
        String content,
        Integer score,
        String category,
        Instant createdAt
) {
    public Feedback {
        createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public boolean isPositive() {
        return score != null && score > 0;
    }

    public boolean isNegative() {
        return score != null && score < 0;
    }
}
