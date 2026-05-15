package com.kuoge.agentstudy.production.skill.eval;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Eval 历史存储 —— 记录每次 Eval 运行的报告，支持回归分析。
 *
 * <p>参考 claw-code 中 LaneBoard 的历史追踪设计：
 * 保留历史状态，便于发现退化趋势。
 */
public class EvalHistory {

    private final Map<String, List<EvalReport>> history = new ConcurrentHashMap<>();

    /**
     * 记录一次 Eval 报告。
     */
    public void record(EvalReport report) {
        String key = report.skillId() + "@" + report.skillVersion();
        history.computeIfAbsent(key, k -> new ArrayList<>()).add(report);
    }

    /**
     * 获取某 Skill 版本的所有历史报告。
     */
    public List<EvalReport> getHistory(String skillId, String skillVersion) {
        String key = skillId + "@" + skillVersion;
        return List.copyOf(history.getOrDefault(key, List.of()));
    }

    /**
     * 获取某 Skill 的最新报告（跨版本）。
     */
    public Optional<EvalReport> latest(String skillId) {
        return history.entrySet().stream()
                .filter(e -> e.getKey().startsWith(skillId + "@"))
                .flatMap(e -> e.getValue().stream())
                .max(Comparator.comparing(EvalReport::executedAt));
    }

    /**
     * 检测回归：当前报告相比上一次是否有新增的失败用例。
     */
    public List<EvalResult> detectRegressions(EvalReport current) {
        List<EvalReport> past = getHistory(current.skillId(), current.skillVersion());
        if (past.size() < 2) return List.of();

        EvalReport previous = past.get(past.size() - 2);
        List<String> previouslyPassed = previous.results().stream()
                .filter(EvalResult::passed)
                .map(EvalResult::caseId)
                .toList();

        return current.regressions(previouslyPassed);
    }

    public void clear() {
        history.clear();
    }
}
