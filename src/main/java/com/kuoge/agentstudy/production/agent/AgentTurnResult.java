package com.kuoge.agentstudy.production.agent;

import com.kuoge.agentstudy.production.runtime.core.TurnSummary;
import com.kuoge.agentstudy.production.skill.Skill;
import com.kuoge.agentstudy.production.skill.runtime.SkillBinding;

/**
 * Agent 单次 handle() 的结果。
 *
 * @param skill     本次路由到的 Skill
 * @param binding   本次执行使用的 SkillBinding（含拼好的 system prompt 与工具子集）
 * @param summary   底层 ConversationRuntime 返回的 TurnSummary
 * @param latencyMs 端到端耗时（含路由 + 绑定 + LLM + 工具执行）
 */
public record AgentTurnResult(
        Skill skill,
        SkillBinding binding,
        TurnSummary summary,
        long latencyMs
) {
    public String finalAnswer() {
        return summary.finalAnswer();
    }
}
