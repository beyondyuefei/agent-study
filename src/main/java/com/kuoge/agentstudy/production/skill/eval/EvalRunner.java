package com.kuoge.agentstudy.production.skill.eval;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Eval 执行器 —— 自动化运行 EvalSuite，生成回归报告。
 *
 * <p>对应 claw-code 中 green_contract.rs 的验证逻辑：
 * 将 Skill 的成功标准转化为可自动执行的测试流程。
 */
@Slf4j
public class EvalRunner {

    /**
     * 运行 EvalSuite。
     *
     * @param suite     评估套件
     * @param skillVersion 被测 Skill 版本
     * @param executor  Skill 执行函数（input → output）
     * @param evaluator 评估器
     * @return 评估报告
     */
    public EvalReport run(EvalSuite suite, String skillVersion,
                          Function<Object, Object> executor, Evaluator evaluator) {
        log.info("Eval started: suite={}, skillVersion={}, cases={}",
                suite.suiteId(), skillVersion, suite.caseCount());

        List<EvalResult> results = new ArrayList<>();
        long suiteStart = System.currentTimeMillis();

        for (EvalCase c : suite.cases()) {
            long start = System.currentTimeMillis();
            try {
                Object actual = executor.apply(c.input());
                long elapsed = System.currentTimeMillis() - start;

                EvalResult result = evaluator.evaluate(c, actual);
                // 补充执行时间
                result = new EvalResult(
                        UUID.randomUUID().toString().substring(0, 8),
                        result.caseId(), skillVersion, result.passed(), result.score(),
                        result.actualOutput(), result.failureReason(), elapsed, Instant.now()
                );
                results.add(result);

                log.debug("Eval case {}: passed={}, score={:.2f}, time={}ms",
                        c.caseId(), result.passed(), result.score(), elapsed);
            } catch (Exception e) {
                results.add(EvalResult.fail(c.caseId(), skillVersion,
                        e.getMessage(),
                        "Exception: " + e.getClass().getSimpleName()));
                log.warn("Eval case {} failed with exception: {}", c.caseId(), e.getMessage());
            }
        }

        long totalElapsed = System.currentTimeMillis() - suiteStart;
        EvalReport report = new EvalReport(
                "report_" + suite.suiteId() + "_" + Instant.now().toEpochMilli(),
                suite.suiteId(), suite.skillId(), skillVersion, results, Instant.now()
        );

        log.info("Eval completed: {} in {}ms", report, totalElapsed);
        return report;
    }
}
