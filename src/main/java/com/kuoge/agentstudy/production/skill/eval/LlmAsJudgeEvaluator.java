package com.kuoge.agentstudy.production.skill.eval;

/**
 * LLM-as-Judge 评估器 —— 用另一个 LLM 评判输出质量。
 *
 * <p>适合开放性问题（如"生成的文案是否吸引人"）。
 * 生产级实现应调用外部 LLM API，这里提供接口和简化版实现。
 */
public class LlmAsJudgeEvaluator implements Evaluator {

    private final String judgePrompt;

    public LlmAsJudgeEvaluator() {
        this.judgePrompt = """
                You are an expert evaluator. Rate the following output on a scale of 0-100,
                where 100 is perfect. Return ONLY a number.
                
                Expected: {{expected}}
                Actual: {{actual}}
                """;
    }

    public LlmAsJudgeEvaluator(String judgePrompt) {
        this.judgePrompt = judgePrompt;
    }

    @Override
    public EvalResult evaluate(EvalCase testCase, Object actualOutput) {
        // 生产级：调用 LLM API 进行评判
        // 简化版：使用启发式规则（如包含关键词、长度检查等）
        String expected = String.valueOf(testCase.expected());
        String actual = String.valueOf(actualOutput);

        double score = heuristicScore(expected, actual);
        boolean passed = score >= 0.7;

        return EvalResult.scored(testCase.caseId(), null, score, actual,
                passed ? null : "LLM judge score below threshold: " + score,
                0);
    }

    private double heuristicScore(String expected, String actual) {
        if (expected == null || actual == null) return 0.0;
        if (expected.equalsIgnoreCase(actual)) return 1.0;

        // 简单启发式：关键词覆盖率
        String[] keywords = expected.toLowerCase().split("\\s+");
        int matched = 0;
        String actualLower = actual.toLowerCase();
        for (String kw : keywords) {
            if (kw.length() > 2 && actualLower.contains(kw)) matched++;
        }
        return keywords.length == 0 ? 0.0 : (double) matched / keywords.length;
    }
}
