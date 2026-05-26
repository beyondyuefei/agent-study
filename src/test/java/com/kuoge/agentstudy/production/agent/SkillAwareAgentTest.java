package com.kuoge.agentstudy.production.agent;

import com.kuoge.agentstudy.production.runtime.client.LlmClient;
import com.kuoge.agentstudy.production.runtime.client.LlmResponse;
import com.kuoge.agentstudy.production.runtime.session.ContentBlock;
import com.kuoge.agentstudy.production.runtime.usage.TokenUsage;
import com.kuoge.agentstudy.production.skill.Skill;
import com.kuoge.agentstudy.production.skill.governance.SkillRegistry;
import com.kuoge.agentstudy.production.skill.sop.SkillSop;
import com.kuoge.agentstudy.production.skill.sop.SkillSopStore;
import com.kuoge.agentstudy.production.tool.Tool;
import com.kuoge.agentstudy.production.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SkillAwareAgent 端到端测试 —— Agent-centric 入口验证。
 *
 * <p>覆盖以下集成路径：
 * <ol>
 *   <li>Agent 持有多个 Skill，按用户输入路由到正确的 Skill</li>
 *   <li>路由到的 Skill 的 SOP 与工具子集进入本次 system prompt</li>
 *   <li>其他 Skill 的工具不会泄漏到本次执行</li>
 *   <li>多次 handle 共享同一 AgentSession</li>
 *   <li>无匹配 Skill → SkillNotFoundException</li>
 *   <li>LLM 失败 → AgentTurnException（保留 skill 上下文）</li>
 *   <li>Skill 声明的工具未注册到全局 ToolRegistry → IllegalStateException</li>
 * </ol>
 */
class SkillAwareAgentTest {

    // ========== 测试夹具 ==========

    private static Skill skill(String id, String name, String domain, String... tools) {
        Skill s = Skill.builder()
                .skillId(id)
                .name(name)
                .domain(domain)
                .description(name + " skill in " + domain)
                .build();
        s.activate();
        for (String t : tools) s.addTool(t);
        return s;
    }

    private static SkillSop sop(String skillId, String dim) {
        return SkillSop.builder()
                .sopId("sop-" + skillId)
                .skillId(skillId)
                .documentPath("docs/" + skillId + ".md")
                .scoringDimensions(List.of(dim))
                .iterationRoadmap(List.of("v1: " + dim))
                .boundaryCases(List.of())
                .updatedAt(Instant.now())
                .build();
    }

    private static Tool tool(String name, String result) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "tool " + name; }
            @Override public String execute(Map<String, Object> arguments) { return result; }
        };
    }

    private static SkillAwareAgent.Builder agentBuilder(LlmClient llm,
                                                          SkillRegistry skills,
                                                          SkillSopStore sops,
                                                          ToolRegistry tools) {
        return SkillAwareAgent.builder()
                .agentId("test-agent")
                .skillRegistry(skills)
                .sopStore(sops)
                .toolRegistry(tools)
                .llmClient(llm);
    }

    // ========== 1. 路由 + 工具子集隔离 ==========

    @Test
    void handle_routesToMarketingSkill_andExposesOnlyItsTools() {
        SkillRegistry skillRegistry = new SkillRegistry();
        SkillSopStore sops = new SkillSopStore.InMemory();
        ToolRegistry globalTools = new ToolRegistry();

        Skill marketing = skill("s-marketing", "marketing", "marketing", "lookup_competitor");
        Skill devops = skill("s-devops", "devops", "infra", "deploy");
        skillRegistry.register(marketing);
        skillRegistry.register(devops);
        sops.save(sop("s-marketing", "creativity"));
        sops.save(sop("s-devops", "stability"));
        globalTools.register(tool("lookup_competitor", "competitor data"));
        globalTools.register(tool("deploy", "deployed"));

        AtomicReference<String> capturedSystem = new AtomicReference<>();
        AtomicInteger callCount = new AtomicInteger();
        LlmClient llm = (system, messages) -> {
            capturedSystem.set(system);
            int n = callCount.incrementAndGet();
            if (n == 1) {
                return new LlmResponse(
                        List.of(new ContentBlock.ToolUseBlock(
                                "tu-1", "lookup_competitor", "{}")),
                        new TokenUsage(20, 5));
            }
            return LlmResponse.text("Plan ready.", new TokenUsage(30, 8));
        };

        SkillAwareAgent agent = agentBuilder(llm, skillRegistry, sops, globalTools).build();
        AgentTurnResult result = agent.handle("帮我策划一个 marketing 活动");

        // 1) 路由正确
        assertEquals("s-marketing", result.skill().getSkillId());
        // 2) SOP 内容进入 prompt
        assertTrue(capturedSystem.get().contains("creativity"));
        // 3) 只有该 Skill 的工具进入 prompt，devops 工具不能泄漏
        assertTrue(capturedSystem.get().contains("lookup_competitor"));
        assertFalse(capturedSystem.get().contains("deploy"),
                "devops 工具不应出现在 marketing skill 的 system prompt 中");
        // 4) ConversationRuntime 完整执行：工具被调用 + 最终回答
        assertEquals("completed", result.summary().stopReason());
        assertEquals(2, result.summary().iterations());
        assertEquals("Plan ready.", result.finalAnswer());
        // 5) binding 的 scopedTools 只含 1 个
        assertEquals(1, result.binding().scopedTools().size());
    }

    @Test
    void handle_routesToDevopsSkill_whenInputMentionsInfra() {
        SkillRegistry skillRegistry = new SkillRegistry();
        SkillSopStore sops = new SkillSopStore.InMemory();
        ToolRegistry globalTools = new ToolRegistry();

        skillRegistry.register(skill("s-marketing", "marketing", "marketing", "lookup_competitor"));
        skillRegistry.register(skill("s-devops", "devops", "infra", "deploy"));
        sops.save(sop("s-marketing", "creativity"));
        sops.save(sop("s-devops", "stability"));
        globalTools.register(tool("lookup_competitor", ""));
        globalTools.register(tool("deploy", ""));

        LlmClient llm = (s, m) -> LlmResponse.text("done", new TokenUsage(5, 2));
        SkillAwareAgent agent = agentBuilder(llm, skillRegistry, sops, globalTools).build();

        AgentTurnResult r = agent.handle("帮我处理 infra 报警");
        assertEquals("s-devops", r.skill().getSkillId());
    }

    // ========== 2. 多 Turn 共享 Session ==========

    @Test
    void handle_multipleTurns_shareSession() {
        SkillRegistry skills = new SkillRegistry();
        SkillSopStore sops = new SkillSopStore.InMemory();
        ToolRegistry tools = new ToolRegistry();
        skills.register(skill("s-1", "marketing", "marketing"));

        LlmClient llm = (system, messages) ->
                LlmResponse.text("turn-msgs=" + messages.size(), new TokenUsage(5, 2));

        SkillAwareAgent agent = agentBuilder(llm, skills, sops, tools).build();
        AgentTurnResult r1 = agent.handle("hello marketing");
        AgentTurnResult r2 = agent.handle("again marketing");

        assertEquals(2, agent.getTotalTurns());
        // 第二个 turn 看到的 messages 数应大于第一个（session 累积）
        assertNotEquals(r1.finalAnswer(), r2.finalAnswer());
    }

    // ========== 3. 路由失败 ==========

    @Test
    void handle_noMatchingSkill_throwsSkillNotFound() {
        SkillRegistry skills = new SkillRegistry();
        SkillSopStore sops = new SkillSopStore.InMemory();
        ToolRegistry tools = new ToolRegistry();
        // 注册的 skill 跟用户输入完全无关
        skills.register(skill("s-marketing", "marketing", "marketing"));

        LlmClient llm = (s, m) -> LlmResponse.text("unused", TokenUsage.empty());
        SkillAwareAgent agent = agentBuilder(llm, skills, sops, tools).build();

        assertThrows(SkillNotFoundException.class,
                () -> agent.handle("帮我写一首唐诗"));
    }

    // ========== 4. LLM 失败 ==========

    @Test
    void handle_llmThrows_wrapsAsAgentTurnException() {
        SkillRegistry skills = new SkillRegistry();
        SkillSopStore sops = new SkillSopStore.InMemory();
        ToolRegistry tools = new ToolRegistry();
        skills.register(skill("s-x", "marketing", "marketing"));

        LlmClient failing = (s, m) -> { throw new RuntimeException("provider down"); };
        SkillAwareAgent agent = agentBuilder(failing, skills, sops, tools).build();

        AgentTurnException ex = assertThrows(AgentTurnException.class,
                () -> agent.handle("marketing now"));
        assertEquals("s-x", ex.getSkill().getSkillId());
        assertTrue(ex.getMessage().contains("provider down"));
    }

    // ========== 5. 工具未注册到全局 ==========

    @Test
    void handle_skillDeclaresUnregisteredTool_throwsIllegalState() {
        SkillRegistry skills = new SkillRegistry();
        SkillSopStore sops = new SkillSopStore.InMemory();
        ToolRegistry tools = new ToolRegistry();
        // Skill 声明了 calc，但全局 ToolRegistry 没注册
        skills.register(skill("s-y", "marketing", "marketing", "calc"));

        LlmClient llm = (s, m) -> LlmResponse.text("unused", TokenUsage.empty());
        SkillAwareAgent agent = agentBuilder(llm, skills, sops, tools).build();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> agent.handle("marketing query"));
        assertTrue(ex.getMessage().contains("calc"));
    }

    // ========== 6. KeywordSkillRouter 单测 ==========

    @Test
    void keywordRouter_returnsEmpty_whenNoCandidates() {
        KeywordSkillRouter r = new KeywordSkillRouter();
        assertTrue(r.route("anything", List.of()).isEmpty());
    }

    @Test
    void keywordRouter_matchesByDomain() {
        KeywordSkillRouter r = new KeywordSkillRouter();
        Skill s = skill("s-1", "Planner", "marketing");
        assertEquals("s-1", r.route("the marketing brief", List.of(s)).get().getSkillId());
    }

    @Test
    void keywordRouter_matchesByDescriptionKeyword() {
        KeywordSkillRouter r = new KeywordSkillRouter();
        Skill s = Skill.builder()
                .skillId("s-2")
                .name("Generic Helper")
                .domain("misc")
                .description("Plan and orchestrate campaigns")
                .build();
        s.activate();
        assertEquals("s-2",
                r.route("help me orchestrate something", List.of(s)).get().getSkillId());
    }
}
