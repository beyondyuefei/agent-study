package com.kuoge.agentstudy.production.skill.eval;

import java.util.Map;

/**
 * Eval 用例 —— 生产级。
 *
 * <p>对应 claw-code 中 PolicyRule 的"条件-动作"设计思想：
 * 输入（条件）→ 执行 Skill → 比对期望输出（断言）。
 */
public record EvalCase(
        String caseId,
        String suiteId,
        String description,
        Object input,
        Object expected,
        Difficulty difficulty,
        String category,
        Map<String, Object> metadata
) {
    public enum Difficulty {
        EASY, MEDIUM, HARD
    }

    public EvalCase {
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public static EvalCase of(String caseId, String description, Object input, Object expected) {
        return new EvalCase(caseId, null, description, input, expected,
                Difficulty.EASY, "general", Map.of());
    }
}
