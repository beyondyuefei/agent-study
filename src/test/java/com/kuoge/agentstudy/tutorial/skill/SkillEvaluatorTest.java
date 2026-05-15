package com.kuoge.agentstudy.tutorial.skill;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class SkillEvaluatorTest {

    @Test
    void shouldEvaluateAllCases() {
        SkillEvaluator evaluator = new SkillEvaluator();

        List<EvalCase> cases = List.of(
                EvalCase.of("case-1", "2+2=4", "2+2", "4"),
                EvalCase.of("case-2", "3+3=6", "3+3", "6"),
                EvalCase.of("case-3", "bad case", "1+1", "3")
        );

        Function<Object, Object> executor = input -> {
            String s = (String) input;
            if (s.equals("2+2")) return "4";
            if (s.equals("3+3")) return "6";
            return "2";
        };

        BiPredicate<Object, Object> matcher = Object::equals;

        EvalReport report = evaluator.evaluate("math-skill", "1.0.0", cases, executor, matcher);

        assertEquals(3, report.results().size());
        assertEquals(2, report.passedCount());
        assertEquals(1, report.failedCount());
        assertEquals(2.0 / 3.0, report.passRate(), 0.001);
    }

    @Test
    void shouldHandleExceptions() {
        SkillEvaluator evaluator = new SkillEvaluator();

        List<EvalCase> cases = List.of(
                EvalCase.of("case-1", "throw", "input", "expected")
        );

        Function<Object, Object> executor = input -> {
            throw new RuntimeException("Boom!");
        };

        EvalReport report = evaluator.evaluate("skill", "1.0", cases, executor, (a, b) -> false);

        assertEquals(1, report.failedCount());
        assertTrue(report.failures().get(0).failureReason().contains("Exception"));
    }
}
