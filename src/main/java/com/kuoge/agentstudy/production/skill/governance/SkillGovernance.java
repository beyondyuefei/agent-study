package com.kuoge.agentstudy.production.skill.governance;

import lombok.Builder;

import java.util.List;

/**
 * Skill 治理配置 —— 管理 Skill 的质量阈值和告警策略。
 *
 * <p>对应 claw-code 中 green_contract.rs 的设计思想：
 * 为每个 Skill 定义可量化的成功标准和告警规则。
 */
@Builder
public record SkillGovernance(
        String governanceId,
        String skillId,
        /** Eval 触发策略 */
        EvalTrigger evalTrigger,
        /** 成功率告警阈值 */
        double successRateThreshold,
        /** 延迟告警阈值（ms） */
        long latencyThresholdMs,
        /** 是否收集用户显式反馈 */
        boolean feedbackCollectionEnabled,
        /** 告警通道 */
        List<String> alertChannels,
        /** 自动回滚：成功率低于阈值时是否自动回滚 */
        boolean autoRollbackOnFailure,
        /** 自动回滚阈值 */
        double autoRollbackThreshold
) {
    public enum EvalTrigger {
        ON_DEPLOY,   // 部署时触发
        SCHEDULED,   // 定时触发
        MANUAL       // 手动触发
    }

    public SkillGovernance {
        alertChannels = alertChannels != null ? List.copyOf(alertChannels) : List.of();
        successRateThreshold = Math.max(0.0, Math.min(1.0, successRateThreshold));
        autoRollbackThreshold = Math.max(0.0, Math.min(1.0, autoRollbackThreshold));
    }

    public boolean shouldAlert(double currentSuccessRate) {
        return currentSuccessRate < successRateThreshold;
    }

    public boolean shouldAutoRollback(double currentSuccessRate) {
        return autoRollbackOnFailure && currentSuccessRate < autoRollbackThreshold;
    }
}
