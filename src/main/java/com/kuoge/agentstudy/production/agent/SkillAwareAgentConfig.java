package com.kuoge.agentstudy.production.agent;

import com.kuoge.agentstudy.production.runtime.compact.CompactionConfig;
import com.kuoge.agentstudy.production.runtime.permission.PermissionMode;
import com.kuoge.agentstudy.production.runtime.permission.PermissionPolicy;
import lombok.Builder;

/**
 * SkillAwareAgent 配置 —— Agent 级运行参数，跨该 Agent 下所有 Skill 生效。
 *
 * <h3>设计原则</h3>
 * <p>所有字段都有「合理默认值」，调用方可以只显式覆盖关心的字段，其余自动回退到默认值。
 * 这样 {@code SkillAwareAgentConfig.builder().basePrompt("xxx").build()} 不会因为
 * 其他 int 字段未设置而退化成 1（之前的 {@code Math.max(1, 0)} 行为）。
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li><b>basePrompt</b>：所有 Skill 共享的人设基底，拼接到 SkillBinding.composedSystemPrompt
 *       的最前部。常用于注入「你是 XX 业务域专家、回答风格、合规约束」等跨 Skill 共性。
 *       默认为通用助手提示。</li>
 *   <li><b>maxIterationsPerTurn</b>：单个 Turn 内允许的 LLM ↔ Tool 迭代次数上限。
 *       一次工具调用 + 一次最终答案 = 2 轮，包含 N 个工具的串行调用 = N+1 轮。
 *       推荐范围 5–30。默认 10。</li>
 *   <li><b>maxTurnsPerSession</b>：单 Session 累积的 Turn 数上限，超过后拒绝新请求。
 *       推荐范围 50–500。默认 100。</li>
 *   <li><b>autoCompactionEnabled</b>：是否启用 SessionCompactor 自动压缩。
 *       默认 true（在长会话中关键）。</li>
 *   <li><b>autoCompactionTokenThreshold</b>：触发压缩的 token 阈值。
 *       推荐范围 50_000–200_000。默认 100_000。</li>
 *   <li><b>compactionConfig</b>：压缩细节（保留最近 N 条消息、边界保护等）。
 *       默认 {@link CompactionConfig#defaults()}。</li>
 *   <li><b>permissionPolicy</b>：跨 Skill 通用的工具权限策略。
 *       默认 {@link PermissionMode#Allow}（学习场景宽松）；生产环境建议显式配置。</li>
 * </ul>
 *
 * <h3>典型用法</h3>
 * <pre>
 * // 1. 全部默认
 * SkillAwareAgentConfig.defaults();
 *
 * // 2. 仅覆盖 basePrompt（其他字段使用默认值，不会被退化）
 * SkillAwareAgentConfig.builder()
 *         .basePrompt("You are a strict product listing reviewer.")
 *         .build();
 *
 * // 3. 高强度业务域：放大迭代上限并启用严格权限
 * SkillAwareAgentConfig.builder()
 *         .basePrompt("You are an autonomous research agent.")
 *         .maxIterationsPerTurn(30)
 *         .permissionPolicy(strictPolicy)
 *         .build();
 * </pre>
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

    /** 默认人设基底。 */
    public static final String DEFAULT_BASE_PROMPT =
            "You are a helpful AI assistant with access to a set of skills.";

    /** 默认 Turn 内迭代上限：足够覆盖 1–3 次工具调用 + 最终答案的常见组合。 */
    public static final int DEFAULT_MAX_ITERATIONS_PER_TURN = 10;

    /** 默认 Session 累积 Turn 数上限。 */
    public static final int DEFAULT_MAX_TURNS_PER_SESSION = 100;

    /** 默认自动压缩 token 阈值（约 ~70% Claude 200k 上下文窗口的健康水位线）。 */
    public static final int DEFAULT_AUTO_COMPACTION_TOKEN_THRESHOLD = 100_000;

    /** 默认启用自动压缩 —— 长会话保护。 */
    public static final boolean DEFAULT_AUTO_COMPACTION_ENABLED = true;

    public SkillAwareAgentConfig {
        if (basePrompt == null || basePrompt.isBlank()) {
            basePrompt = DEFAULT_BASE_PROMPT;
        }
        // 注意：使用 "<= 0 → 默认" 而非 Math.max(1, x)，避免 Builder 未设置字段（0）退化成 1。
        if (maxIterationsPerTurn <= 0) {
            maxIterationsPerTurn = DEFAULT_MAX_ITERATIONS_PER_TURN;
        }
        if (maxTurnsPerSession <= 0) {
            maxTurnsPerSession = DEFAULT_MAX_TURNS_PER_SESSION;
        }
        if (autoCompactionTokenThreshold <= 0) {
            autoCompactionTokenThreshold = DEFAULT_AUTO_COMPACTION_TOKEN_THRESHOLD;
        }
        if (compactionConfig == null) {
            compactionConfig = CompactionConfig.defaults();
        }
        if (permissionPolicy == null) {
            permissionPolicy = new PermissionPolicy(PermissionMode.Allow);
        }
    }

    /** 全默认配置。 */
    public static SkillAwareAgentConfig defaults() {
        return SkillAwareAgentConfig.builder().build();
    }

    /** 严格模式：禁用自动压缩 + Prompt 权限模式（每次工具调用前询问用户，用于审计敏感的业务）。 */
    public static SkillAwareAgentConfig strict() {
        return SkillAwareAgentConfig.builder()
                .autoCompactionEnabled(false)
                .permissionPolicy(new PermissionPolicy(PermissionMode.Prompt))
                .build();
    }
}
