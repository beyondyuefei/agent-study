package com.kuoge.agentstudy.production.agent;

import com.kuoge.agentstudy.production.model.Action;

/**
 * LLM 响应结构 —— 面向 ReAct Agent 的版本。
 */
public record AgentLlmResponse(String thought, Action action) {

    /**
     * 构造最终回答（无 Action）。
     */
    public static AgentLlmResponse answer(String thought) {
        return new AgentLlmResponse(thought, null);
    }

    /**
     * 构造需要调用工具的响应。
     */
    public static AgentLlmResponse action(String thought, Action action) {
        return new AgentLlmResponse(thought, action);
    }
}
