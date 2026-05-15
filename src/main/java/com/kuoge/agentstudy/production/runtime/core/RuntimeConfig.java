package com.kuoge.agentstudy.production.runtime.core;

import com.kuoge.agentstudy.production.runtime.compact.CompactionConfig;
import com.kuoge.agentstudy.production.runtime.permission.PermissionMode;
import com.kuoge.agentstudy.production.runtime.permission.PermissionPolicy;
import lombok.Builder;

/**
 * ConversationRuntime 的运行时配置。
 *
 * <p>对应 claw-code Rust 实现：{@code conversation.rs} 中的配置参数 +
 * {@code config.rs/RuntimeFeatureConfig}
 */
@Builder
public record RuntimeConfig(
        String systemPrompt,
        int maxIterationsPerTurn,
        int maxTurnsPerSession,
        CompactionConfig compactionConfig,
        PermissionPolicy permissionPolicy,
        boolean autoCompactionEnabled,
        int autoCompactionTokenThreshold
) {

    public RuntimeConfig {
        if (systemPrompt == null || systemPrompt.isBlank()) {
            systemPrompt = "You are a helpful AI assistant with access to tools. " +
                    "Think step by step, then use tools if needed.";
        }
        maxIterationsPerTurn = Math.max(1, maxIterationsPerTurn);
        maxTurnsPerSession = Math.max(1, maxTurnsPerSession);
        compactionConfig = compactionConfig != null ? compactionConfig : CompactionConfig.defaults();
        permissionPolicy = permissionPolicy != null ? permissionPolicy : new PermissionPolicy(PermissionMode.Allow);
        autoCompactionEnabled = autoCompactionEnabled; // keep as-is
        autoCompactionTokenThreshold = Math.max(1000, autoCompactionTokenThreshold);
    }

    public static RuntimeConfig defaults() {
        return RuntimeConfig.builder()
                .systemPrompt(null)
                .maxIterationsPerTurn(10)
                .maxTurnsPerSession(100)
                .compactionConfig(CompactionConfig.defaults())
                .permissionPolicy(new PermissionPolicy(PermissionMode.Allow))
                .autoCompactionEnabled(true)
                .autoCompactionTokenThreshold(100_000)
                .build();
    }

    public static RuntimeConfig conservative() {
        return RuntimeConfig.builder()
                .systemPrompt(null)
                .maxIterationsPerTurn(5)
                .maxTurnsPerSession(50)
                .compactionConfig(CompactionConfig.conservative())
                .permissionPolicy(new PermissionPolicy(PermissionMode.Prompt))
                .autoCompactionEnabled(true)
                .autoCompactionTokenThreshold(50_000)
                .build();
    }
}
