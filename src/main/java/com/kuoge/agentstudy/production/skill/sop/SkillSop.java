package com.kuoge.agentstudy.production.skill.sop;

import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Skill SOP（标准操作程序）—— 规范化沉淀层。
 *
 * <p>对应 claw-code 中任务定义（TaskPacket）的规范化思想：
 * 将 Skill 的"应该怎么做"沉淀为结构化文档，供 AI Agent 和开发者共同阅读。
 */
@Builder
public record SkillSop(
        String sopId,
        String skillId,
        String documentPath,
        List<String> scoringDimensions,
        Map<String, Object> platformStrategies,
        List<String> iterationRoadmap,
        List<String> boundaryCases,
        String version,
        Instant updatedAt
) {
    public SkillSop {
        scoringDimensions = scoringDimensions != null ? List.copyOf(scoringDimensions) : List.of();
        platformStrategies = platformStrategies != null ? Map.copyOf(platformStrategies) : Map.of();
        iterationRoadmap = iterationRoadmap != null ? List.copyOf(iterationRoadmap) : List.of();
        boundaryCases = boundaryCases != null ? List.copyOf(boundaryCases) : List.of();
        updatedAt = updatedAt != null ? updatedAt : Instant.now();
    }
}
