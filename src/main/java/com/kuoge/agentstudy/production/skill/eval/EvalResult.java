package com.kuoge.agentstudy.production.skill.eval;

import java.time.Instant;

/**
 * Eval 结果 —— 单条用例的执行结果。
 */
public record EvalResult(
        String resultId,
        String caseId,
        String skillVersion,
        boolean passed,
        double score,
        String actualOutput,
        String failureReason,
        long executionTimeMs,
        Instant executedAt
) {
    public EvalResult {
        score = Math.max(0.0, Math.min(1.0, score));
        executedAt = executedAt != null ? executedAt : Instant.now();
    }

    public static EvalResult pass(String caseId, String skillVersion, String actualOutput) {
        return new EvalResult(null, caseId, skillVersion, true, 1.0,
                actualOutput, null, 0, Instant.now());
    }

    public static EvalResult fail(String caseId, String skillVersion,
                                   String actualOutput, String failureReason) {
        return new EvalResult(null, caseId, skillVersion, false, 0.0,
                actualOutput, failureReason, 0, Instant.now());
    }

    public static EvalResult scored(String caseId, String skillVersion, double score,
                                     String actualOutput, String failureReason, long ms) {
        return new EvalResult(null, caseId, skillVersion, score >= 1.0, score,
                actualOutput, failureReason, ms, Instant.now());
    }
}
