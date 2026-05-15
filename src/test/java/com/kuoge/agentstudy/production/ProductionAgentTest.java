package com.kuoge.agentstudy.production;

import com.kuoge.agentstudy.production.agent.ProductionReActAgent;
import com.kuoge.agentstudy.production.context.*;
import com.kuoge.agentstudy.production.cost.*;
import com.kuoge.agentstudy.production.memory.*;
import com.kuoge.agentstudy.react.Action;
import com.kuoge.agentstudy.react.ReActLoop;
import com.kuoge.agentstudy.react.tool.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 生产级 ReAct Agent 单元测试。
 *
 * <p>覆盖三个核心模块：
 * <ol>
 *   <li>PreferenceMemory（偏好记忆）</li>
 *   <li>ContextCompressor（9段式上下文压缩）</li>
 *   <li>CostControl（成本追踪 + Prompt 缓存 + 懒加载）</li>
 * </ol>
 */
class ProductionAgentTest {

    // ========== 测试工具 ==========

    static class WeatherTool implements Tool {
        public String name() { return "getWeather"; }
        public String description() { return "查询天气"; }
        public String execute(Map<String, Object> args) {
            return "晴，25°C";
        }
    }

    // ========== 测试 1：PreferenceMemory ==========

    @Test
    @DisplayName("偏好记忆：观察 → 确认 → 提升置信度")
    void testPreferenceMemoryConfidence() {
        PreferenceStore store = new InMemoryPreferenceStore();
        PreferenceMemory memory = new PreferenceMemory(store);
        String userId = "user-001";

        // 第一次观察：置信度 0.5
        memory.observe(userId, "tech_stack", "test_framework", "JUnit 5", "msg-1");
        UserPreference p1 = store.find(userId, "tech_stack", "test_framework").orElseThrow();
        assertEquals(0.5, p1.confidence(), 0.01);
        assertEquals(1, p1.confirmationCount());

        // 第二次确认：置信度提升
        memory.observe(userId, "tech_stack", "test_framework", "JUnit 5", "msg-2");
        UserPreference p2 = store.find(userId, "tech_stack", "test_framework").orElseThrow();
        assertTrue(p2.confidence() > p1.confidence(), "确认后置信度应提升");
        assertEquals(2, p2.confirmationCount());

        // 第三次确认：达到高置信度
        memory.observe(userId, "tech_stack", "test_framework", "JUnit 5", "msg-3");
        UserPreference p3 = store.find(userId, "tech_stack", "test_framework").orElseThrow();
        assertTrue(p3.isHighConfidence(), "三次确认后应达到高置信度");

        // 生成核心偏好提示词
        String prompt = memory.buildCorePreferencePrompt(userId);
        assertTrue(prompt.contains("JUnit 5"), "核心偏好应包含 JUnit 5");
        assertTrue(prompt.contains("user_preferences"), "应使用 XML 标签包裹");
    }

    @Test
    @DisplayName("偏好记忆：冲突时更新偏好值")
    void testPreferenceMemoryConflict() {
        PreferenceStore store = new InMemoryPreferenceStore();
        PreferenceMemory memory = new PreferenceMemory(store);
        String userId = "user-001";

        memory.observe(userId, "tech_stack", "test_framework", "JUnit 5", "msg-1");
        memory.observe(userId, "tech_stack", "test_framework", "TestNG", "msg-2");

        UserPreference pref = store.find(userId, "tech_stack", "test_framework").orElseThrow();
        assertEquals("TestNG", pref.value(), "应更新为新值");
        assertEquals(1, pref.confirmationCount(), "新值从 1 开始");
        assertEquals(0.6, pref.confidence(), 0.01, "新值置信度从 0.6 开始");
    }

    // ========== 测试 2：ContextCompressor（9段式压缩）==========

    @Test
    @DisplayName("9段式压缩：未超预算时全部保留")
    void testContextCompressorWithinBudget() {
        ContextCompressor compressor = new ContextCompressor(10000);

        compressor.segment(SegmentType.SYSTEM_IDENTITY).setContent("You are a helpful assistant.\n");
        compressor.segment(SegmentType.CURRENT_GOAL).setContent("Current task: 查天气\n");
        compressor.segment(SegmentType.RECENT_HISTORY).setContent("User: 今天天气如何？\n");

        String result = compressor.build();

        assertTrue(result.contains("helpful assistant"), "应保留系统身份");
        assertTrue(result.contains("查天气"), "应保留当前目标");
        assertTrue(result.contains("今天天气如何"), "应保留近期历史");
        assertTrue(compressor.getCurrentTotalTokens() < 10000, "应在预算内");
    }

    @Test
    @DisplayName("9段式压缩：超预算时低优先级段先被压缩")
    void testContextCompressorUnderPressure() {
        // 小预算：200 token（强制压缩）
        ContextCompressor compressor = new ContextCompressor(200);

        // 高优先级段（不可压缩）
        compressor.segment(SegmentType.SYSTEM_IDENTITY).setContent("SYSTEM: You are AI.\n");
        compressor.segment(SegmentType.CURRENT_GOAL).setContent("GOAL: test\n");

        // 低优先级段（大量内容，会被压缩）
        StringBuilder longHistory = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longHistory.append("User: question ").append(i).append(" and more text. ");
            longHistory.append("Assistant: answer ").append(i).append(" with details.\n");
        }
        compressor.segment(SegmentType.RECENT_HISTORY).setContent(longHistory.toString());

        // SCRATCHPAD（最低优先级，会被淘汰）
        compressor.segment(SegmentType.SCRATCHPAD).setContent("Temporary scratchpad notes that should be evicted.\n");

        String result = compressor.build();

        // 验证高优先级段保留
        assertTrue(result.contains("SYSTEM:"), "系统身份应保留");
        assertTrue(result.contains("GOAL:"), "当前目标应保留");

        // 验证低优先级段被压缩或淘汰
        assertTrue(!result.contains("Temporary scratchpad") || result.contains("[evicted]") || result.contains("[truncated]"),
                "低优先级内容应被压缩或淘汰");

        // 验证总 token 在预算内
        assertTrue(compressor.getCurrentTotalTokens() <= 200,
                "总 token 应在预算内，实际=" + compressor.getCurrentTotalTokens());
    }

    @Test
    @DisplayName("9段式压缩：SCRATCHPAD 优先被淘汰")
    void testScratchpadEvictedFirst() {
        // 设置极小的预算，强制触发压缩
        ContextCompressor compressor = new ContextCompressor(15);

        // 系统身份（PRESERVE，不可压缩）
        compressor.segment(SegmentType.SYSTEM_IDENTITY).setContent("System.\n");
        // SCRATCHPAD（EVICT，最低优先级）
        compressor.segment(SegmentType.SCRATCHPAD).setContent("Scratchpad content.\n");
        // 大量历史内容，迫使压缩
        compressor.segment(SegmentType.RECENT_HISTORY).setContent(
                "History line one. History line two. History line three. History line four.\n");

        String result = compressor.build();

        // SCRATCHPAD 是最低优先级，在预算压力下应被清空
        assertFalse(result.contains("Scratchpad content"), "SCRATCHPAD 应被 EVICT");
        // 系统身份应保留
        assertTrue(result.contains("System."), "系统身份应保留");
    }

    // ========== 测试 3：成本优化 ==========

    @Test
    @DisplayName("PromptTemplateCache：缓存命中避免重复渲染")
    void testPromptCache() {
        PromptTemplateCache cache = new PromptTemplateCache();

        // 第一次：放入缓存
        cache.put("system:v1", "You are AI.", 4);
        assertTrue(cache.has("system:v1"));

        // 第二次：命中缓存
        PromptTemplateCache.CacheEntry entry = cache.get("system:v1");
        assertNotNull(entry);
        assertEquals("You are AI.", entry.content());
        assertEquals(4, entry.tokens());

        // 失效后不再命中
        cache.invalidate("system:v1");
        assertFalse(cache.has("system:v1"));
    }

    @Test
    @DisplayName("TokenBudget：预算控制和预警")
    void testTokenBudget() {
        TokenBudget budget = TokenBudget.standard(10000); // 70% 输入 = 7000

        assertEquals(7000, budget.getMaxInputTokens());
        assertEquals(3000, budget.getMaxOutputTokens());

        // 记录消耗
        budget.recordInput(6000);
        budget.recordOutput(1000);

        assertFalse(budget.isInputOverBudget(), "6000 < 7000，未超预算");
        assertFalse(budget.isNearLimit(), "7000/10000 = 70% < 80%");

        // 接近上限
        budget.recordInput(1500);
        assertTrue(budget.isNearLimit(), "7500/10000 > 80%，应预警");

        // 超出输入预算
        budget.recordInput(1000);
        assertTrue(budget.isInputOverBudget(), "8500 > 7000，应超预算");
    }

    @Test
    @DisplayName("CostTracker：追踪 LLM 和工具调用成本")
    void testCostTracker() {
        CostTracker tracker = new CostTracker();

        tracker.recordLlmCall(1000, 500, 1200, "gpt-4");
        tracker.recordToolCall("getWeather", 300);
        tracker.recordLlmCall(800, 400, 900, "gpt-4");

        assertEquals(2, tracker.getRecords().stream().filter(r -> r.type() == CostTracker.CallType.LLM).count());
        assertEquals(1, tracker.getTotalToolCalls());
        assertEquals(1800, tracker.getTotalInputTokens());
        assertEquals(900, tracker.getTotalOutputTokens());

        // 估算成本（假设 $0.01/1k input, $0.03/1k output）
        double cost = tracker.estimateCost(0.01, 0.03);
        assertTrue(cost > 0, "成本应大于 0");
    }

    @Test
    @DisplayName("LazyLoader：按需加载工具定义")
    void testLazyLoader() {
        LazyLoader<String> loader = new LazyLoader<>();

        int[] loadCount = {0};
        loader.register("weather_tool", () -> {
            loadCount[0]++;
            return "Weather tool definition...";
        }, Map.of("keywords", "weather forecast"));

        loader.register("order_tool", () -> {
            loadCount[0]++;
            return "Order tool definition...";
        }, Map.of("keywords", "order status"));

        // 初始未加载
        assertEquals(0, loader.loadedCount());

        // 按需加载 weather 相关工具
        Map<String, String> relevant = loader.loadRelevant("weather");
        assertEquals(1, relevant.size());
        assertTrue(relevant.containsKey("weather_tool"));
        assertEquals(1, loader.loadedCount());
        assertEquals(1, loadCount[0], "只应加载 weather_tool");

        // 再次加载已缓存的，不应重复加载
        loader.load("weather_tool");
        assertEquals(1, loadCount[0], "不应重复加载");
    }

    // ========== 测试 4：整合测试 ==========

    @Test
    @DisplayName("整合：ProductionReActAgent 完整执行流程")
    void testProductionAgentEndToEnd() {
        // 构建 Mock LLM：第一轮调用工具，第二轮给出答案
        ReActLoop.LlmClient mockLlm = new ReActLoop.LlmClient() {
            private int count = 0;
            @Override
            public ReActLoop.LlmResponse call(String context) {
                count++;
                if (count == 1) {
                    // 验证上下文包含用户偏好（production agent 使用 XML 标签包裹）
                    assertTrue(context.contains("JUnit"),
                            "上下文应注入用户偏好，实际上下文=" + context.substring(0, Math.min(200, context.length())));
                    return ReActLoop.LlmResponse.action(
                            "需要查询天气",
                            Action.of("getWeather", Map.of("city", "北京"))
                    );
                }
                return ReActLoop.LlmResponse.answer("北京今天晴，25°C");
            }
        };

        // 构建偏好记忆
        PreferenceStore store = new InMemoryPreferenceStore();
        PreferenceMemory memory = new PreferenceMemory(store);
        memory.observe("user-001", "tech_stack", "test_framework", "JUnit 5", "msg-1");
        memory.observe("user-001", "tech_stack", "test_framework", "JUnit 5", "msg-2");
        memory.observe("user-001", "tech_stack", "test_framework", "JUnit 5", "msg-3");

        // 构建 Agent
        ProductionReActAgent agent = ProductionReActAgent.builder()
                .withLlmClient(mockLlm)
                .withPreferenceMemory(memory)
                .withContextBudget(8000)
                .withTool(new WeatherTool())
                .build();

        // 执行
        ProductionReActAgent.AgentResult result = agent.execute("user-001", "北京天气");

        // 验证结果
        assertEquals("北京今天晴，25°C", result.answer());
        assertEquals(2, result.steps().size(), "应有 2 步");
        assertNotNull(result.costReport());
        assertNotNull(result.budgetReport());
        assertTrue(result.totalLatencyMs() >= 0);

        System.out.println("=== Production Agent Report ===");
        System.out.println("Answer: " + result.answer());
        System.out.println("Cost: " + result.costReport());
        System.out.println("Budget: " + result.budgetReport());
        System.out.println("Latency: " + result.totalLatencyMs() + "ms");
    }

    @Test
    @DisplayName("整合：对比教学版 vs 生产版的上下文差异")
    void testEducationalVsProductionContext() {
        // 教学版：StringBuilder 简单拼接
        StringBuilder eduContext = new StringBuilder();
        eduContext.append("System: You are AI\n");
        eduContext.append("User: hello\n");
        String eduResult = eduContext.toString();

        // 生产版：9段式结构化
        ContextCompressor compressor = new ContextCompressor(10000);
        compressor.segment(SegmentType.SYSTEM_IDENTITY).setContent("You are AI\n");
        compressor.segment(SegmentType.CURRENT_GOAL).setContent("Respond to user\n");
        compressor.segment(SegmentType.RECENT_HISTORY).setContent("User: hello\n");
        String prodResult = compressor.build();

        // 两者都包含基本内容
        assertTrue(eduResult.contains("You are AI"));
        assertTrue(prodResult.contains("You are AI"));

        // 但生产版有结构化优势
        assertTrue(prodResult.contains("Respond to user"), "生产版有明确的目标段");
        assertTrue(compressor.getCurrentTotalTokens() > 0, "生产版可精确计算 token");

        // 打印对比
        System.out.println("\n--- Educational Context ---");
        System.out.println(eduResult);
        System.out.println("--- Production Context ---");
        System.out.println(prodResult);
        compressor.printStatus();
    }
}
