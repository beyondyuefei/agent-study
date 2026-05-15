package com.kuoge.agentstudy.tutorial.skill;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Tutorial 层 Skill Eval 执行器 —— 简化版。
 *
 * <p>核心职责：对给定的 Skill，执行其所有 EvalCase，生成 EvalReport。
 * 对应 claw-code 中 PolicyEngine.evaluate() 的设计思想：
 * 输入上下文 → 规则匹配 → 输出决策。
 *
 * <p>这里简化为：输入用例 → 执行 Skill → 比对结果 → 输出报告。
 */
public class SkillEvaluator {

    /**
     * 执行一组 EvalCase。
     *
     * @param skillId      Skill ID
     * @param skillVersion Skill 版本
     * @param cases        用例列表
     * @param executor     Skill 执行函数（input → output）
     * @param matcher      结果比对函数（expected vs actual → boolean）
     * @return Eval 报告
     */
    public EvalReport evaluate(
            String skillId,
            String skillVersion,
            List<EvalCase> cases,
            Function<Object, Object> executor,
            java.util.function.BiPredicate<Object, Object> matcher
    ) {
        List<EvalResult> results = new ArrayList<>();

        for (EvalCase c : cases) {
            long start = System.currentTimeMillis();
            try {
                Object actual = executor.apply(c.input());
                long elapsed = System.currentTimeMillis() - start;

                boolean matched = matcher.test(c.expected(), actual);
                if (matched) {
                    results.add(new EvalResult(c.id(), true, 1.0,
                            String.valueOf(actual), null, Instant.now(), elapsed));
                } else {
                    results.add(new EvalResult(c.id(), false, 0.0,
                            String.valueOf(actual),
                            "Expected: " + c.expected() + ", Actual: " + actual,
                            Instant.now(), elapsed));
                }
            } catch (Exception e) {
                results.add(EvalResult.fail(
                        c.id(),
                        e.getMessage(),
                        "Exception: " + e.getClass().getSimpleName() + ": " + e.getMessage()
                ));
            }
        }

        return new EvalReport(skillId, skillVersion, results, Instant.now());
    }
}
