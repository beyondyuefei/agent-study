package com.kuoge.agentstudy.tutorial;

import java.util.Map;

/**
 * ReAct 中的 Action（动作）。
 *
 * <p>表示 LLM 决定调用某个工具，并携带具体的参数。
 * 例如：
 * <pre>
 * Action {
 *   toolName = "queryOrderStatus",
 *   arguments = { "orderId": "12345" }
 * }
 * </pre>
 *
 * @param toolName  工具名称
 * @param arguments 工具参数（key-value）
 */
public record Action(
        String toolName,
        Map<String, Object> arguments
) {
    public static Action of(String toolName, Map<String, Object> arguments) {
        return new Action(toolName, arguments);
    }

    public static Action none() {
        return new Action(null, Map.of());
    }

    @Override
    public String toString() {
        if (toolName == null || toolName.isBlank()) {
            return "Action[FINISH]";
        }
        return "Action[" + toolName + ", args=" + arguments + "]";
    }
}
