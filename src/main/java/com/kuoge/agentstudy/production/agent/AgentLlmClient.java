package com.kuoge.agentstudy.production.agent;

/**
 * LLM 客户端抽象接口 —— 面向 ReAct Agent 的版本。
 *
 * <p>与 {@link com.kuoge.agentstudy.production.runtime.client.LlmClient} 的区别：
 * 本接口面向基于字符串上下文的 ReAct 循环，而 runtime 版本面向结构化消息。
 *
 * <p>在真实环境中，这是 {@code ChatClient} 的包装层。
 */
public interface AgentLlmClient {

    /**
     * 调用 LLM，返回解析后的 Thought + Action。
     *
     * @param context 当前完整上下文（system prompt + history + user query）
     * @return LLM 响应（包含 thought 和 action）
     */
    AgentLlmResponse call(String context);
}
