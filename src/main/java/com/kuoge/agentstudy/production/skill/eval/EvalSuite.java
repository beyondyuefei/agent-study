package com.kuoge.agentstudy.production.skill.eval;

import lombok.Builder;

import java.time.Instant;
import java.util.List;

/**
 * Eval 套件 —— 一组相关的测试用例。
 *
 * <p>对应 claw-code 中 green_contract.rs 的思想：
 * 每个 Skill 必须定义"成功标准"，用可验证的断言代替主观判断。
 *
 * <p>EvalSuite 分为三类：
 * <ul>
 *   <li>UNIT：单元测试（针对单个工具的精确性）</li>
 *   <li>INTEGRATION：集成测试（针对多工具链的协作）</li>
 *   <li>E2E：端到端测试（针对完整用户任务的完成度）</li>
 * </ul>
 */
@Builder
public record EvalSuite(
        String suiteId,
        String skillId,
        String suiteName,
        EvalType evalType,
        List<EvalCase> cases,
        String evaluatorClass,
        Instant createdAt
) {
    public enum EvalType {
        UNIT, INTEGRATION, E2E
    }

    public EvalSuite {
        cases = cases != null ? List.copyOf(cases) : List.of();
        createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public int caseCount() {
        return cases.size();
    }
}
