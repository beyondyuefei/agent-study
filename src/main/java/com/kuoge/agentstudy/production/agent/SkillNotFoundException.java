package com.kuoge.agentstudy.production.agent;

/**
 * 路由阶段未匹配到任何可用 Skill。
 */
public class SkillNotFoundException extends RuntimeException {
    public SkillNotFoundException(String message) {
        super(message);
    }
}
