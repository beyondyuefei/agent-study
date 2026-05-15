package com.kuoge.agentstudy.tutorial;

import java.time.Instant;

/**
 * ReAct 循环中的单步记录。
 *
 * <p>对应 ReAct 论文中的 (Thought, Action, Observation) 三元组。
 * 每一步包含：
 * <ol>
 *   <li>Thought — LLM 的推理过程（"我需要先查一下订单状态"）</li>
 *   <li>Action — 决定调用的工具及参数</li>
 *   <li>Observation — 工具执行后的观察结果</li>
 * </ol>
 *
 * @param stepNumber   步骤序号（从 0 开始）
 * @param thought      LLM 的推理文本
 * @param action       执行的动作（工具调用）
 * @param observation  观察结果
 * @param timestamp    时间戳
 */
public record ReActStep(
        int stepNumber,
        String thought,
        Action action,
        Observation observation,
        Instant timestamp
) {
    /**
     * 判断此步骤是否为最终回答步骤（没有 Action，直接给出结论）。
     */
    public boolean isFinalAnswer() {
        return action == null || action.toolName() == null || action.toolName().isBlank();
    }

    /**
     * 判断此步骤是否成功完成了工具调用。
     */
    public boolean isActionSuccess() {
        return action != null && observation != null && observation.success();
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("=== Step ").append(stepNumber).append(" ===\n");
        sb.append("Thought: ").append(thought).append("\n");
        if (action != null) {
            sb.append("Action: ").append(action).append("\n");
        }
        if (observation != null) {
            sb.append("Observation: ").append(observation.content()).append("\n");
        }
        return sb.toString();
    }
}
