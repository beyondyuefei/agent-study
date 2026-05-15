package com.kuoge.agentstudy.production.skill.governance;

import com.kuoge.agentstudy.production.skill.Skill;
import com.kuoge.agentstudy.production.skill.SkillStatus;
import com.kuoge.agentstudy.production.skill.eval.EvalHistory;
import com.kuoge.agentstudy.production.skill.eval.EvalReport;

import java.time.Instant;
import java.util.*;

/**
 * 治理看板 —— 聚合 Skill 的治理数据，生成可读的状态报告。
 *
 * <p>参考 claw-code 中 LaneBoard 的设计：
 * 将多个 Skill 的运行状态聚合成一个看板，便于运营人员快速判断系统健康度。
 */
public class GovernanceDashboard {

    private final SkillRegistry skillRegistry;
    private final EvalHistory evalHistory;

    public GovernanceDashboard(SkillRegistry skillRegistry, EvalHistory evalHistory) {
        this.skillRegistry = skillRegistry;
        this.evalHistory = evalHistory;
    }

    /**
     * 生成整体看板。
     */
    public DashboardReport generateReport() {
        List<Skill> allSkills = new ArrayList<>(skillRegistry.listAll());

        int total = allSkills.size();
        long active = allSkills.stream().filter(s -> s.getStatus() == SkillStatus.ACTIVE).count();
        long grayscale = allSkills.stream().filter(s -> s.getStatus() == SkillStatus.GRAYSCALE).count();
        long draft = allSkills.stream().filter(s -> s.getStatus() == SkillStatus.DRAFT).count();
        long deprecated = allSkills.stream().filter(s -> s.getStatus() == SkillStatus.DEPRECATED).count();

        List<SkillHealth> healthChecks = allSkills.stream()
                .map(this::checkHealth)
                .toList();

        List<SkillHealth> alerts = healthChecks.stream()
                .filter(SkillHealth::needsAttention)
                .toList();

        return new DashboardReport(
                total, (int) active, (int) grayscale, (int) draft, (int) deprecated,
                healthChecks, alerts, Instant.now()
        );
    }

    /**
     * 检查单个 Skill 的健康状态。
     */
    public SkillHealth checkHealth(Skill skill) {
        Optional<EvalReport> latestEval = evalHistory.latest(skill.getSkillId());

        double successRate = latestEval.map(EvalReport::passRate).orElse(0.0);
        double avgScore = latestEval.map(EvalReport::averageScore).orElse(0.0);
        int evalCount = latestEval.map(r -> r.results().size()).orElse(0);

        String status;
        boolean needsAttention = false;

        if (skill.getStatus() == SkillStatus.ACTIVE && successRate < 0.85) {
            status = "WARNING: success rate below 85%";
            needsAttention = true;
        } else if (skill.getStatus() == SkillStatus.GRAYSCALE) {
            status = "GRAYSCALE: monitoring";
        } else if (skill.getStatus() == SkillStatus.DRAFT) {
            status = "DRAFT: not deployed";
        } else if (skill.getStatus() == SkillStatus.DEPRECATED) {
            status = "DEPRECATED";
        } else {
            status = "HEALTHY";
        }

        return new SkillHealth(
                skill.getSkillId(), skill.getName(), skill.getStatus(),
                skill.getCurrentVersionId(), successRate, avgScore, evalCount,
                status, needsAttention
        );
    }

    // ── 报告记录 ──────────────────────────────────────────

    public record DashboardReport(
            int totalSkills,
            int active,
            int grayscale,
            int draft,
            int deprecated,
            List<SkillHealth> healthChecks,
            List<SkillHealth> alerts,
            Instant generatedAt
    ) {
        @Override
        public String toString() {
            return String.format(
                    "DashboardReport{total=%d, active=%d, grayscale=%d, draft=%d, deprecated=%d, alerts=%d}",
                    totalSkills, active, grayscale, draft, deprecated, alerts.size()
            );
        }
    }

    public record SkillHealth(
            String skillId,
            String skillName,
            SkillStatus status,
            String currentVersion,
            double successRate,
            double averageScore,
            int evalCount,
            String statusMessage,
            boolean needsAttention
    ) {}
}
