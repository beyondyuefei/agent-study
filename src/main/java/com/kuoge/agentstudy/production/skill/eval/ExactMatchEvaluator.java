package com.kuoge.agentstudy.production.skill.eval;

import java.util.Objects;

/**
 * 精确匹配评估器 —— 适合结构化输出验证。
 */
public class ExactMatchEvaluator implements Evaluator {

    @Override
    public EvalResult evaluate(EvalCase testCase, Object actualOutput) {
        boolean matched = Objects.equals(testCase.expected(), actualOutput);
        if (matched) {
            return EvalResult.pass(testCase.caseId(), null, String.valueOf(actualOutput));
        }
        return EvalResult.fail(testCase.caseId(), null,
                String.valueOf(actualOutput),
                "Expected: " + testCase.expected() + ", Actual: " + actualOutput);
    }
}
