package com.kuoge.agentstudy.production.skill.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class EvalRunnerTest {

    @Test
    void shouldRunSuiteAndGenerateReport() {
        EvalRunner runner = new EvalRunner();

        EvalSuite suite = EvalSuite.builder()
                .suiteId("test-suite")
                .skillId("math-skill")
                .suiteName("Math Operations")
                .evalType(EvalSuite.EvalType.UNIT)
                .cases(List.of(
                        EvalCase.of("add-1", "2+2", "2+2", "4"),
                        EvalCase.of("add-2", "3+3", "3+3", "6")
                ))
                .build();

        Function<Object, Object> executor = input -> {
            String s = (String) input;
            if (s.equals("2+2")) return "4";
            if (s.equals("3+3")) return "6";
            return "unknown";
        };

        Evaluator evaluator = new ExactMatchEvaluator();

        EvalReport report = runner.run(suite, "1.0.0", executor, evaluator);

        assertNotNull(report);
        assertEquals("math-skill", report.skillId());
        assertEquals(2, report.results().size());
        assertEquals(2, report.passedCount());
        assertEquals(1.0, report.passRate(), 0.001);
        assertTrue(report.averageLatencyMs() >= 0);
    }

    @Test
    void shouldDetectFailures() {
        EvalRunner runner = new EvalRunner();

        EvalSuite suite = EvalSuite.builder()
                .suiteId("fail-suite")
                .skillId("skill")
                .suiteName("Failing Suite")
                .evalType(EvalSuite.EvalType.UNIT)
                .cases(List.of(
                        EvalCase.of("ok", "ok", "in", "expected"),
                        EvalCase.of("fail", "fail", "in", "wrong")
                ))
                .build();

        Function<Object, Object> executor = input -> "expected";
        Evaluator evaluator = new ExactMatchEvaluator();

        EvalReport report = runner.run(suite, "1.0", executor, evaluator);

        assertEquals(1, report.passedCount());
        assertEquals(1, report.failedCount());
        assertEquals(0.5, report.passRate(), 0.001);
        assertEquals(1, report.failures().size());
    }
}
