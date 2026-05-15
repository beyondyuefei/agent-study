package com.kuoge.agentstudy.production.skill.eval;

/**
 * 评估器接口 —— 生产级。
 *
 * <p>支持多种评估策略：
 * <ul>
 *   <li>Exact Match：精确匹配（适合结构化输出）</li>
 *   <li>LLM-as-Judge：用另一个 LLM 评判（适合开放性问题）</li>
 *   <li>Code Execution：执行代码验证（适合数学/编程任务）</li>
 * </ul>
 */
@FunctionalInterface
public interface Evaluator {

    /**
     * 评估单条用例。
     *
     * @param testCase 测试用例
     * @param actualOutput 实际输出
     * @return 评估结果
     */
    EvalResult evaluate(EvalCase testCase, Object actualOutput);
}
