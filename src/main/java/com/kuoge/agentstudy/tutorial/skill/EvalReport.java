package com.kuoge.agentstudy.tutorial.skill;

import java.time.Instant;
import java.util.List;

/**
 * Tutorial 层 Eval 报告 —— 一次 Eval 运行的完整结果。
 *
 * <p>参考 claw-code 中 LaneBoard 的设计：将多个 EvalResult 聚合成一个看板，
 * 便于快速判断 Skill 的质量状态。
 */
public record EvalReport(
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

    public List<EvalResult> failures() {
        return results.stream().filter(r -> !r.passed()).toList();
    }

    @Override
    public String toString() {
        return String.format(
                "EvalReport[%s@%s]: %.1f%% passed (%d/%d), avg score: %.2f, failures: %d",
                skillId, skillVersion, passRate() * 100, passedCount(), results.size(),
                averageScore(), failures().size()
        );
    }
}
