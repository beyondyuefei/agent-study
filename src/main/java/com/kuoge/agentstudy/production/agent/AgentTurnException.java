package com.kuoge.agentstudy.production.agent;

import com.kuoge.agentstudy.production.skill.Skill;
import lombok.Getter;

/**
 * Agent 在执行 ConversationRuntime 阶段抛出的异常包装器。
 *
 * <p>携带本次失败时已经路由到的 Skill 与已发生的耗时，便于上层做降级 / 切换 Skill。
 */
@Getter
public class AgentTurnException extends RuntimeException {

    private final Skill skill;
    private final long latencyMs;

    public AgentTurnException(Skill skill, long latencyMs, Throwable cause) {
        super("Agent turn failed (skill=" + (skill != null ? skill.getSkillId() : "?")
                + ", latencyMs=" + latencyMs + "): " + cause.getMessage(), cause);
        this.skill = skill;
        this.latencyMs = latencyMs;
    }
}
