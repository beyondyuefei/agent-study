package com.kuoge.agentstudy.production.skill.governance;

import java.time.Instant;

/**
 * 部署记录 —— 记录每次部署/灰度/回滚操作。
 *
 * <p>参考 claw-code 中 TaskMessage / LaneHeartbeat 的可观测性设计：
 * 所有关键操作都留下审计记录。
 */
public record Deployment(
        String deploymentId,
        String skillId,
        String versionId,
        DeploymentAction action,
        String operator,
        String previousVersionId,
        String reason,
        Instant executedAt
) {
    public enum DeploymentAction {
        DEPLOY, GRAYSCALE, ROLLBACK, DEPRECATE, ACTIVATE
    }

    public Deployment {
        executedAt = executedAt != null ? executedAt : Instant.now();
    }
}
