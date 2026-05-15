package com.kuoge.agentstudy.tutorial.tool;

import java.util.*;

/**
 * 工具注册中心：管理所有可用的 Tool。
 *
 * <p>对应 Spring AI 中的 {@code ToolCallingManager}，但这里是手动实现的简化版，
 * 目的是让学习者可清晰看到"工具注册 → 查找 → 执行"的完整链路。
 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public void register(Tool tool) {
        if (tools.containsKey(tool.name())) {
            throw new IllegalArgumentException("Tool already registered: " + tool.name());
        }
        tools.put(tool.name(), tool);
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Collection<Tool> all() {
        return Collections.unmodifiableCollection(tools.values());
    }

    /**
     * 生成供 LLM 使用的工具定义文本。
     *
     * <p>在真实的 Spring AI 中，这一步由框架自动将 @Tool 注解转换为 JSON Schema。
     * 这里手动拼接为文本格式，便于学习理解。
     */
    public String toPromptText() {
        final StringBuilder sb = new StringBuilder();
        sb.append("You have access to the following tools:\n\n");
        for (Tool tool : tools.values()) {
            sb.append("- ").append(tool.name()).append(": ").append(tool.description()).append("\n");
        }
        sb.append("\n");
        sb.append("When you need to use a tool, respond with:\n");
        sb.append("TOOL: <tool_name>\n");
        sb.append("ARGS: <json_args>\n\n");
        sb.append("If you don't need a tool, respond with the final answer directly.");
        return sb.toString();
    }
}
