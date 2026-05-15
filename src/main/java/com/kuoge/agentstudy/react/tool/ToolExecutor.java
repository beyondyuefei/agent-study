package com.kuoge.agentstudy.react.tool;

import com.kuoge.agentstudy.react.Action;
import com.kuoge.agentstudy.react.Observation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 工具执行器：负责解析 Action 并调用对应的 Tool。
 *
 * <p>对应 Spring AI {@code ToolCallAdvisor} 中的工具执行逻辑。
 * 这里提取为独立类，便于单元测试和 debug。
 */
@Slf4j
@RequiredArgsConstructor
public class ToolExecutor {

    private final ToolRegistry registry;

    /**
     * 执行一个 Action。
     *
     * @param action 要执行的动作
     * @return 观察结果
     */
    public Observation execute(Action action) {
        if (action == null || action.toolName() == null || action.toolName().isBlank()) {
            return Observation.error("Empty action");
        }

        final Tool tool = registry.find(action.toolName())
                .orElse(null);

        if (tool == null) {
            return Observation.error("Tool not found: " + action.toolName());
        }

        try {
            log.debug("Executing tool: {} with args: {}", action.toolName(), action.arguments());
            final String result = tool.execute(action.arguments());
            log.debug("Tool result: {}", result);
            return Observation.ok(result);
        } catch (Exception e) {
            log.warn("Tool execution failed: {}", action.toolName(), e);
            return Observation.error(e.getMessage());
        }
    }
}
