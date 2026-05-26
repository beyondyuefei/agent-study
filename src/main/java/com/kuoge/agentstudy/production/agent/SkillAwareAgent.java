package com.kuoge.agentstudy.production.agent;

import com.kuoge.agentstudy.production.runtime.client.LlmClient;
import com.kuoge.agentstudy.production.runtime.core.ConversationRuntime;
import com.kuoge.agentstudy.production.runtime.core.RuntimeConfig;
import com.kuoge.agentstudy.production.runtime.core.TurnSummary;
import com.kuoge.agentstudy.production.runtime.session.AgentSession;
import com.kuoge.agentstudy.production.skill.Skill;
import com.kuoge.agentstudy.production.skill.SkillStatus;
import com.kuoge.agentstudy.production.skill.governance.SkillRegistry;
import com.kuoge.agentstudy.production.skill.runtime.SkillBinding;
import com.kuoge.agentstudy.production.skill.sop.SkillSop;
import com.kuoge.agentstudy.production.skill.sop.SkillSopStore;
import com.kuoge.agentstudy.production.tool.Tool;
import com.kuoge.agentstudy.production.tool.ToolRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Skill-Aware Agent —— Agent-centric 入口。
 *
 * <p>Agent 实例持有该业务场景下所有 Skill，按用户输入动态路由到某一个 Skill，
 * 加载对应 SOP 与工具子集，再由 ConversationRuntime 执行。
 *
 * <h3>多 Agent 部署</h3>
 * <p>一个系统可以同时运行多个 Agent 实例 —— 不同业务场景各一个，例如：
 * <ul>
 *   <li>"选品上架 Agent" 实例：持有竞品分析 / 标题优化 / 图片合规 等 Skill</li>
 *   <li>"AI 客服 Agent" 实例：持有订单查询 / 退款审批 / 情绪安抚 等 Skill</li>
 * </ul>
 * 每个实例独享 SkillRegistry / ToolRegistry / SkillSopStore / config / session，
 * 互不干扰；通常共享 LlmClient（HTTP 连接池层面）。
 *
 * <p>这与 claude code (CLI) 单进程单 Agent 实例的形态不同：在多业务、多用户的
 * 服务端场景下，应按业务域拆分 Agent 实例，由上层 AgentFactory / 路由层选择实例。
 *
 * <h3>层次关系（单个 Agent 实例内部）</h3>
 * <pre>
 * SkillAwareAgent  ← 业务域内唯一入口
 *   ├─ SkillRegistry         (该域内的 Skill 候选)
 *   ├─ SkillSopStore         (该域 SOP 查询)
 *   ├─ ToolRegistry          (该域工具池)
 *   ├─ SkillRouter           (路由策略)
 *   ├─ AgentSession          (会话上下文，跨 turn 共享)
 *   ├─ LlmClient             (LLM 调用，可跨 Agent 共享)
 *   └─ SkillAwareAgentConfig (该 Agent 的人设与运行参数)
 * </pre>
 *
 * <h3>handle 流程</h3>
 * <pre>
 * userInput
 *    │
 *    ▼ 1. 路由
 * SkillRouter.route(input, activeSkills)  → Skill
 *    │
 *    ▼ 2. 加载 SOP + 收集本次开放的工具
 * SkillSopStore.findBySkillId(skill.id)   → SkillSop
 * skill.toolNames → 从 ToolRegistry 抽出工具子集
 *    │
 *    ▼ 3. 准入 + 拼 prompt
 * SkillBinding.bind(skill, sop, tools, basePrompt)
 *    │
 *    ▼ 4. 执行
 * ConversationRuntime.runTurn(input)
 *    │
 *    ▼
 * AgentTurnResult { skill, summary, latencyMs }
 * </pre>
 */
@Slf4j
public class SkillAwareAgent {

    @Getter private final String agentId;
    @Getter private final SkillRegistry skillRegistry;
    @Getter private final SkillSopStore sopStore;
    @Getter private final ToolRegistry toolRegistry;
    @Getter private final LlmClient llmClient;
    @Getter private final SkillRouter router;
    @Getter private final SkillAwareAgentConfig config;
    @Getter private final AgentSession session;

    private int totalTurns = 0;

    private SkillAwareAgent(Builder b) {
        this.agentId = Objects.requireNonNullElse(b.agentId, "agent-default");
        this.skillRegistry = Objects.requireNonNull(b.skillRegistry, "skillRegistry required");
        this.sopStore = Objects.requireNonNull(b.sopStore, "sopStore required");
        this.toolRegistry = Objects.requireNonNull(b.toolRegistry, "toolRegistry required");
        this.llmClient = Objects.requireNonNull(b.llmClient, "llmClient required");
        this.router = Objects.requireNonNullElseGet(b.router, KeywordSkillRouter::new);
        this.config = Objects.requireNonNullElseGet(b.config, SkillAwareAgentConfig::defaults);
        this.session = Objects.requireNonNullElseGet(b.session, AgentSession::new);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 处理一次用户输入：路由 → 绑定 → 执行。
     */
    public AgentTurnResult handle(String userInput) {
        Objects.requireNonNull(userInput, "userInput must not be null");

        List<Skill> candidates = activeSkills();
        Skill chosen = router.route(userInput, candidates)
                .orElseThrow(() -> new SkillNotFoundException(
                        "No skill matched user input: " + truncate(userInput, 80)
                                + " (active skills=" + candidates.size() + ")"));

        SkillSop sop = sopStore.findBySkillId(chosen.getSkillId()).orElse(null);
        List<Tool> scopedTools = collectScopedTools(chosen);

        SkillBinding binding = SkillBinding.bind(chosen, sop, scopedTools, config.basePrompt());

        long start = System.currentTimeMillis();
        TurnSummary summary;
        try {
            ConversationRuntime cr = buildConversationRuntime(binding);
            summary = cr.runTurn(userInput);
        } catch (RuntimeException e) {
            long latency = System.currentTimeMillis() - start;
            log.warn("[Agent {}] Turn failed: skill={} err={}", agentId, chosen.getSkillId(), e.getMessage());
            throw new AgentTurnException(chosen, latency, e);
        }
        long latency = System.currentTimeMillis() - start;
        totalTurns++;
        return new AgentTurnResult(chosen, binding, summary, latency);
    }

    public int getTotalTurns() {
        return totalTurns;
    }

    // ── 内部 ─────────────────────────────────────────────

    private List<Skill> activeSkills() {
        List<Skill> result = new ArrayList<>();
        result.addAll(skillRegistry.listByStatus(SkillStatus.ACTIVE));
        result.addAll(skillRegistry.listByStatus(SkillStatus.GRAYSCALE));
        return result;
    }

    private List<Tool> collectScopedTools(Skill skill) {
        List<Tool> result = new ArrayList<>();
        for (String toolName : skill.getToolNames()) {
            Tool tool = toolRegistry.find(toolName)
                    .orElseThrow(() -> new IllegalStateException(
                            "Skill '" + skill.getSkillId() + "' declares tool '"
                                    + toolName + "' but ToolRegistry has none"));
            result.add(tool);
        }
        return result;
    }

    private ConversationRuntime buildConversationRuntime(SkillBinding binding) {
        RuntimeConfig rc = RuntimeConfig.builder()
                .systemPrompt(binding.composedSystemPrompt())
                .maxIterationsPerTurn(config.maxIterationsPerTurn())
                .maxTurnsPerSession(config.maxTurnsPerSession())
                .autoCompactionEnabled(config.autoCompactionEnabled())
                .autoCompactionTokenThreshold(config.autoCompactionTokenThreshold())
                .compactionConfig(config.compactionConfig())
                .permissionPolicy(config.permissionPolicy())
                .build();
        return new ConversationRuntime(session, llmClient, binding.scopedToolRegistry(), rc);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ── Builder ──────────────────────────────────────────

    public static final class Builder {
        private String agentId;
        private SkillRegistry skillRegistry;
        private SkillSopStore sopStore;
        private ToolRegistry toolRegistry;
        private LlmClient llmClient;
        private SkillRouter router;
        private SkillAwareAgentConfig config;
        private AgentSession session;

        public Builder agentId(String id) { this.agentId = id; return this; }
        public Builder skillRegistry(SkillRegistry r) { this.skillRegistry = r; return this; }
        public Builder sopStore(SkillSopStore s) { this.sopStore = s; return this; }
        public Builder toolRegistry(ToolRegistry t) { this.toolRegistry = t; return this; }
        public Builder llmClient(LlmClient l) { this.llmClient = l; return this; }
        public Builder router(SkillRouter r) { this.router = r; return this; }
        public Builder config(SkillAwareAgentConfig c) { this.config = c; return this; }
        public Builder session(AgentSession s) { this.session = s; return this; }

        public SkillAwareAgent build() {
            return new SkillAwareAgent(this);
        }
    }
}
