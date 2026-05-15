package com.kuoge.agentstudy.react.tool;

import java.util.Map;

/**
 * ReAct Agent 可调用的工具接口。
 *
 * <p>对应 Spring AI 中的 {@code ToolCallback} 概念，但这里是简化版，
 * 便于学习 ReAct 的核心机制而不被框架细节干扰。
 *
 * @param name        工具名称（如 "queryOrderStatus"）
 * @param description 工具描述（供 LLM 决策时使用）
 */
public interface Tool {

    String name();

    String description();

    /**
     * 执行工具。
     *
     * @param arguments 工具参数
     * @return 工具执行结果的文本表示
     * @throws Exception 工具执行异常
     */
    String execute(Map<String, Object> arguments) throws Exception;
}
