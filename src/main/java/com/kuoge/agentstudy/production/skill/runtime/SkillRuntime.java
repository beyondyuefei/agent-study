package com.kuoge.agentstudy.production.skill.runtime;

import com.kuoge.agentstudy.production.runtime.core.ConversationRuntime;
import com.kuoge.agentstudy.production.tool.ToolRegistry;
import lombok.Getter;

/**
 * Skill Runtime 抽象 —— 生产级实现。
 *
 * <p>对应 claw-code 中 ConversationRuntime 的 Skill 层封装。
 * 每个 Skill 在运行时都绑定一个 Runtime 实例，负责：
 * <ol>
 *   <li>管理 Skill 特有的上下文（system prompt、工具集、记忆策略）</li>
 *   <li>将 Skill 配置转化为可执行的 ConversationRuntime</li>
 *   <li>提供 Skill 级别的可观测性（成功率、延迟、token 消耗）</li>
 * </ol>
 *
 * <p>与底层 ConversationRuntime 的关系：
 * <pre>
 * SkillRuntime（Skill 级配置）
 *   │
 *   ▼ 构建
 * ConversationRuntime（对话级执行）
 *   │
 *   ▼ 运行
 * TurnSummary（单次 Turn 结果）
 * </pre>
 */
@Getter
public class SkillRuntime {

    private final String runtimeId;
    private final String skillId;
    private final SkillRuntimeConfig config;
    private final ToolRegistry toolRegistry;

    /** 运行统计 */
    private int totalTurns = 0;
    private int successfulTurns = 0;
    private long totalLatencyMs = 0;

    public SkillRuntime(String runtimeId, String skillId,
                        SkillRuntimeConfig config, ToolRegistry toolRegistry) {
        this.runtimeId = runtimeId;
        this.skillId = skillId;
        this.config = config;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 构建底层 ConversationRuntime。
     *
     * <p>生产级实现：将 Skill 级配置（system prompt、工具集、上下文预算）
     * 转化为 ConversationRuntime 所需的参数。
     */
    public ConversationRuntime buildConversationRuntime(
            com.kuoge.agentstudy.production.runtime.session.AgentSession session,
            com.kuoge.agentstudy.production.runtime.client.LlmClient llmClient
    ) {
        var runtimeConfig = com.kuoge.agentstudy.production.runtime.core.RuntimeConfig.builder()
                .systemPrompt(config.getSystemPrompt())
                .maxIterationsPerTurn(config.getMaxIterationsPerTurn())
                .maxTurnsPerSession(config.getMaxTurnsPerSession())
                .autoCompactionEnabled(config.isAutoCompactionEnabled())
                .autoCompactionTokenThreshold(config.getAutoCompactionTokenThreshold())
                .build();

        return new ConversationRuntime(session, llmClient, toolRegistry, runtimeConfig);
    }

    /**
     * 记录一次 Turn 的执行结果（用于统计）。
     */
    public void recordTurn(boolean success, long latencyMs) {
        this.totalTurns++;
        if (success) this.successfulTurns++;
        this.totalLatencyMs += latencyMs;
    }

    /**
     * 获取当前成功率。
     */
    public double successRate() {
        return totalTurns == 0 ? 0.0 : (double) successfulTurns / totalTurns;
    }

    /**
     * 获取平均延迟。
     */
    public double averageLatencyMs() {
        return totalTurns == 0 ? 0.0 : (double) totalLatencyMs / totalTurns;
    }

    @Override
    public String toString() {
        return String.format("SkillRuntime[%s] skill=%s turns=%d success=%.1f%% avgLatency=%.0fms",
                runtimeId, skillId, totalTurns, successRate() * 100, averageLatencyMs());
    }
}
