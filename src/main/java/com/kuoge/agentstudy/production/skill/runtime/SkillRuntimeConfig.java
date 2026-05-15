package com.kuoge.agentstudy.production.skill.runtime;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * Skill Runtime 配置 —— 生产级。
 *
 * <p>对应 claw-code 中 RuntimeFeatureConfig + TaskPacket 的配置思想：
 * 将 Skill 的运行时参数外置到配置对象中，支持版本管理和动态切换。
 */
@Getter
@Builder
public class SkillRuntimeConfig {

    private String configId;
    private String skillId;

    /** System Prompt */
    @Builder.Default
    private String systemPrompt = "You are a helpful AI assistant with access to tools.";

    /** 每 Turn 最大迭代数 */
    @Builder.Default
    private int maxIterationsPerTurn = 10;

    /** 每 Session 最大 Turn 数 */
    @Builder.Default
    private int maxTurnsPerSession = 100;

    /** 上下文预算（token） */
    @Builder.Default
    private int contextBudget = 8000;

    /** 是否启用自动压缩 */
    @Builder.Default
    private boolean autoCompactionEnabled = true;

    /** 自动压缩 token 阈值 */
    @Builder.Default
    private int autoCompactionTokenThreshold = 100_000;

    /** 注册的 Tool Bean 名称列表 */
    @Builder.Default
    private List<String> toolBeanNames = List.of();

    /** 扩展参数（如 temperature、model 等） */
    @Builder.Default
    private Map<String, Object> parameters = Map.of();

    public static SkillRuntimeConfig defaults() {
        return SkillRuntimeConfig.builder().build();
    }

    public static SkillRuntimeConfig conservative() {
        return SkillRuntimeConfig.builder()
                .maxIterationsPerTurn(5)
                .maxTurnsPerSession(50)
                .contextBudget(4000)
                .autoCompactionTokenThreshold(50_000)
                .build();
    }
}
