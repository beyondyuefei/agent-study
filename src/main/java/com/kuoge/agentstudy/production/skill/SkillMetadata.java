package com.kuoge.agentstudy.production.skill;

import java.time.Instant;
import java.util.Map;

/**
 * Skill 元数据 —— 用于注册表中的轻量级索引。
 *
 * <p>设计目的：SkillRegistry.list() 返回完整 Skill 对象可能过大，
 * 因此提供轻量级的 SkillMetadata 用于列表展示和搜索。
 */
public record SkillMetadata(
        String skillId,
        String name,
        String domain,
        String owner,
        SkillStatus status,
        String currentVersion,
        int toolCount,
        int evalCaseCount,
        double latestSuccessRate,
        Instant createdAt,
        Instant updatedAt
) {}
