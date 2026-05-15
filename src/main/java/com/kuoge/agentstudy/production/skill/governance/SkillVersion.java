package com.kuoge.agentstudy.production.skill.governance;

import lombok.Builder;

import java.time.Instant;
import java.util.Map;

/**
 * Skill 版本 —— 不可变部署单元。
 *
 * <p>参考 claw-code 中版本不可变原则：
 * 已发布的版本不允许修改，只能创建新版本。
 */
@Builder
public record SkillVersion(
        String versionId,
        String skillId,
        String promptTemplate,
        Map<String, Object> runtimeParameters,
        DeploymentStatus status,
        int grayscalePercent,
        String createdBy,
        Instant createdAt,
        Instant activatedAt
) {
    public SkillVersion {
        runtimeParameters = runtimeParameters != null ? Map.copyOf(runtimeParameters) : Map.of();
        status = status != null ? status : DeploymentStatus.INACTIVE;
        grayscalePercent = Math.max(0, Math.min(100, grayscalePercent));
        createdAt = createdAt != null ? createdAt : Instant.now();
    }

    /**
     * 判断是否为活跃版本。
     */
    public boolean isActive() {
        return status == DeploymentStatus.ACTIVE;
    }

    /**
     * 判断是否为灰度版本。
     */
    public boolean isGrayscale() {
        return status == DeploymentStatus.GRAYSCALE;
    }

    /**
     * 检查指定用户是否在灰度桶中。
     */
    public boolean inGrayscaleBucket(String userId) {
        if (!isGrayscale()) return isActive();
        int bucket = Math.abs(userId.hashCode()) % 100;
        return bucket < grayscalePercent;
    }
}
