package com.kuoge.agentstudy.tutorial.skill;

import java.time.Instant;

/**
 * Tutorial 层 Eval 结果 —— 单条用例的执行结果。
 *
 * <p>对应 claw-code 中 PolicyDecisionEvent 的设计思想：
 * 每次评估都产生结构化结果，包含通过状态、得分、失败原因。
 */
public record EvalResult(
        String caseId,
        boolean passed,
        double score,
        String actualOutput,
        String failureReason,
        Instant executedAt,
        long executionTimeMs
) {
    public EvalResult {
        score = Math.max(0.0, Math.min(1.0, score));
        executedAt = executedAt != null ? executedAt : Instant.now();
    }

    public static EvalResult pass(String caseId, String actualOutput) {
        return new EvalResult(caseId, true, 1.0, actualOutput, null, Instant.now(), 0);
    }

    public static EvalResult fail(String caseId, String actualOutput, String failureReason) {
        return new EvalResult(caseId, false, 0.0, actualOutput, failureReason, Instant.now(), 0);
    }

    public static EvalResult scored(String caseId, double score, String actualOutput, String failureReason) {
        return new EvalResult(caseId, score >= 1.0, score, actualOutput, failureReason, Instant.now(), 0);
    }
}
