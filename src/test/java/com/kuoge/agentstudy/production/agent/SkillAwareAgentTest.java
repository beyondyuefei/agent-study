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

    // ========== 6. 多 Agent 实例并存（业务域隔离）==========

    @Test
    void multipleAgents_perBusinessDomain_areIsolated() {
        // 业务域 1：选品上架 Agent —— 独享 SkillRegistry / ToolRegistry / SopStore
        SkillRegistry listingSkills = new SkillRegistry();
        SkillSopStore listingSops = new SkillSopStore.InMemory();
        ToolRegistry listingTools = new ToolRegistry();
        listingSkills.register(skill("s-listing", "listing", "ecommerce", "fetch_competitor_price"));
        listingSops.save(sop("s-listing", "compliance"));
        listingTools.register(tool("fetch_competitor_price", "$19.99"));

        // 业务域 2：AI 客服 Agent —— 完全独立
        SkillRegistry supportSkills = new SkillRegistry();
        SkillSopStore supportSops = new SkillSopStore.InMemory();
        ToolRegistry supportTools = new ToolRegistry();
        supportSkills.register(skill("s-support", "support", "service", "lookup_order"));
        supportSops.save(sop("s-support", "empathy"));
        supportTools.register(tool("lookup_order", "order#42 shipped"));

        // 共享 LlmClient（HTTP 连接池层面）
        AtomicReference<String> lastSystem = new AtomicReference<>();
        AtomicInteger callCount = new AtomicInteger();
        LlmClient sharedLlm = (system, messages) -> {
            lastSystem.set(system);
            int n = callCount.incrementAndGet();
            // 偶数次调用进入工具调用分支
            if (n % 2 == 1) {
                String toolName = system.contains("listing")
                        ? "fetch_competitor_price" : "lookup_order";
                return new LlmResponse(
                        List.of(new ContentBlock.ToolUseBlock("tu-" + n, toolName, "{}")),
                        new TokenUsage(10, 4));
            }
            return LlmResponse.text("done#" + n, new TokenUsage(15, 6));
        };

        SkillAwareAgent listingAgent = SkillAwareAgent.builder()
                .agentId("agent-listing")
                .skillRegistry(listingSkills).sopStore(listingSops).toolRegistry(listingTools)
                .llmClient(sharedLlm)
                .config(SkillAwareAgentConfig.builder()
                        .basePrompt("You are a strict product listing reviewer.")
                        .build())
                .build();

        SkillAwareAgent supportAgent = SkillAwareAgent.builder()
                .agentId("agent-support")
                .skillRegistry(supportSkills).sopStore(supportSops).toolRegistry(supportTools)
                .llmClient(sharedLlm)
                .config(SkillAwareAgentConfig.builder()
                        .basePrompt("You are a warm and empathetic customer support specialist.")
                        .build())
                .build();

        // 1) 各自能正常工作
        AgentTurnResult r1 = listingAgent.handle("listing 这款商品");
        assertEquals("s-listing", r1.skill().getSkillId());
        assertEquals("done#2", r1.finalAnswer());
        assertTrue(r1.binding().composedSystemPrompt().contains("strict product listing reviewer"));
        assertTrue(r1.binding().composedSystemPrompt().contains("compliance"));

        AgentTurnResult r2 = supportAgent.handle("support 一下我的订单");
        assertEquals("s-support", r2.skill().getSkillId());
        assertEquals("done#4", r2.finalAnswer());
        assertTrue(r2.binding().composedSystemPrompt().contains("empathetic customer support"));
        assertTrue(r2.binding().composedSystemPrompt().contains("empathy"));

        // 2) 关键隔离断言：listing Agent 拿不到 support 域的工具/SOP，反之亦然
        assertFalse(r1.binding().composedSystemPrompt().contains("lookup_order"));
        assertFalse(r1.binding().composedSystemPrompt().contains("empathy"));
        assertFalse(r2.binding().composedSystemPrompt().contains("fetch_competitor_price"));
        assertFalse(r2.binding().composedSystemPrompt().contains("compliance"));

        // 3) Session 不共享：两个 Agent 各自计数
        assertEquals(1, listingAgent.getTotalTurns());
        assertEquals(1, supportAgent.getTotalTurns());

        // 4) 跨 Agent 路由失败：listing Agent 不识别"order"这种纯客服词
        assertThrows(SkillNotFoundException.class,
                () -> listingAgent.handle("帮我退款"));
    }

    // ========== 7. KeywordSkillRouter 单测 ==========

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
