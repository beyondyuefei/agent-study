package com.kuoge.agentstudy.tutorial.skill;

import java.util.Map;

/**
 * Tutorial 层 Eval 用例 —— 简化版。
 *
 * <p>对应 claw-code 中的测试用例思想：每个 Skill 必须配套 Eval 用例，
 * 用数据驱动的方式验证 Skill 的正确性，而非主观判断。
 *
 * @param id          用例 ID
 * @param description 描述
 * @param input       输入（任意对象）
 * @param expected    期望输出（任意对象）
 * @param difficulty  难度
 */
public record EvalCase(
        String id,
        String description,
        Object input,
        Object expected,
        Difficulty difficulty,
        Map<String, Object> metadata
) {
    public enum Difficulty {
        EASY, MEDIUM, HARD
    }

    public EvalCase {
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public static EvalCase of(String id, String description, Object input, Object expected) {
        return new EvalCase(id, description, input, expected, Difficulty.EASY, Map.of());
    }
}
