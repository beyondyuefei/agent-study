package com.kuoge.agentstudy.production.agent;

import com.kuoge.agentstudy.production.runtime.compact.CompactionConfig;
import com.kuoge.agentstudy.production.runtime.permission.PermissionMode;
import com.kuoge.agentstudy.production.runtime.permission.PermissionPolicy;
import lombok.Builder;

/**
 * SkillAwareAgent 配置。
 *
 * <p>Agent 级配置 —— 跨所有 Skill 生效。
 * <ul>
 *   <li>basePrompt：所有 Skill 共享的人设基底（拼到 SkillBinding.composedSystemPrompt 最前）</li>
 *   <li>maxIterationsPerTurn / maxTurnsPerSession：底层 ConversationRuntime 的循环上限</li>
 *   <li>permissionPolicy：跨 Skill 通用的工具权限策略</li>
 *   <li>autoCompactionEnabled：是否启用自动上下文压缩</li>
 * </ul>
 */
@Builder
public record SkillAwareAgentConfig(
        String basePrompt,
        int maxIterationsPerTurn,
        int maxTurnsPerSession,
        boolean autoCompactionEnabled,
        int autoCompactionTokenThreshold,
        CompactionConfig compactionConfig,
        PermissionPolicy permissionPolicy
) {

    public SkillAwareAgentConfig {
        if (basePrompt == null || basePrompt.isBlank()) {
            basePrompt = "You are a helpful AI assistant with access to a set of skills.";
        }
        maxIterationsPerTurn = Math.max(1, maxIterationsPerTurn);
        maxTurnsPerSession = Math.max(1, maxTurnsPerSession);
        autoCompactionTokenThreshold = Math.max(1000, autoCompactionTokenThreshold);
        compactionConfig = compactionConfig != null ? compactionConfig : CompactionConfig.defaults();
        permissionPolicy = permissionPolicy != null
                ? permissionPolicy : new PermissionPolicy(PermissionMode.Allow);
    }

    public static SkillAwareAgentConfig defaults() {
        return SkillAwareAgentConfig.builder()
                .maxIterationsPerTurn(10)
                .maxTurnsPerSession(100)
                .autoCompactionEnabled(true)
                .autoCompactionTokenThreshold(100_000)
                .build();
    }
}
