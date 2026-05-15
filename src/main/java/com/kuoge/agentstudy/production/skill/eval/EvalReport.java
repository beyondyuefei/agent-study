package com.kuoge.agentstudy.production.skill.eval;

import java.time.Instant;
import java.util.List;

/**
 * Eval 报告 —— 一次 Eval 运行的完整结果。
 */
public record EvalReport(
        String reportId,
        String suiteId,
        String skillId,
        String skillVersion,
        List<EvalResult> results,
        Instant executedAt
) {
    public EvalReport {
        results = results != null ? List.copyOf(results) : List.of();
        executedAt = executedAt != null ? executedAt : Instant.now();
    }

    public long passedCount() {
        return results.stream().filter(EvalResult::passed).count();
    }

    public long failedCount() {
        return results.size() - passedCount();
    }

    public double passRate() {
        return results.isEmpty() ? 0.0 : (double) passedCount() / results.size();
    }

    public double averageScore() {
        return results.isEmpty() ? 0.0
                : results.stream().mapToDouble(EvalResult::score).average().orElse(0.0);
    }

    public double averageLatencyMs() {
        return results.isEmpty() ? 0.0
                : results.stream().mapToLong(EvalResult::executionTimeMs).average().orElse(0.0);
    }

    public List<EvalResult> failures() {
        return results.stream().filter(r -> !r.passed()).toList();
    }

    public List<EvalResult> regressions(List<String> previouslyPassedCaseIds) {
        return results.stream()
                .filter(r -> !r.passed() && previouslyPassedCaseIds.contains(r.caseId()))
                .toList();
    }

    @Override
    public String toString() {
        return String.format(
                "EvalReport[%s@%s] %s: %.1f%% passed (%d/%d), avg score=%.2f, avg latency=%.0fms, failures=%d",
                skillId, skillVersion, suiteId,
                passRate() * 100, passedCount(), results.size(),
                averageScore(), averageLatencyMs(), failures().size()
        );
    }
}
