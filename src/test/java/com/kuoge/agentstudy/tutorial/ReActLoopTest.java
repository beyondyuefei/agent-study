package com.kuoge.agentstudy.tutorial;

import com.kuoge.agentstudy.tutorial.tool.Tool;
import com.kuoge.agentstudy.tutorial.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReAct 循环核心单元测试。
 *
 * <p>学习重点：通过 Mock LLM 客户端，精确控制每一步的 Thought + Action，
 * 从而可预测地验证 ReAct 循环的行为，无需依赖真实的 LLM 服务。
 *
 * <h3>测试场景覆盖</h3>
 * <ol>
 *   <li><b>单次工具调用</b>：LLM 一轮就给出最终答案（无工具调用）</li>
 *   <li><b>多步工具调用</b>：LLM 需要调用工具获取信息后才能回答</li>
 *   <li><b>工具执行失败</b>：工具报错后，ReAct 应继续循环，让 LLM 决定下一步</li>
 *   <li><b>达到最大步数</b>：循环应有上限，防止无限循环</li>
 *   <li><b>上下文累积</b>：验证每一步的 Observation 是否被正确注入上下文</li>
 * </ol>
 */
class ReActLoopTest {

    // ========== 测试工具定义 ==========

    /** 模拟天气查询工具 */
    static class WeatherTool implements Tool {
        @Override public String name() { return "getWeather"; }
        @Override public String description() { return "查询指定城市的天气"; }
        @Override public String execute(Map<String, Object> args) {
            return "北京今天晴，25°C，微风"; // 简化实现
        }
    }

    /** 模拟订单查询工具 */
    static class OrderTool implements Tool {
        @Override public String name() { return "queryOrder"; }
        @Override public String description() { return "查询订单状态，参数：orderId"; }
        @Override public String execute(Map<String, Object> args) {
            String orderId = String.valueOf(args.getOrDefault("orderId", "unknown"));
            return "订单 " + orderId + "：已发货，预计明天送达";
        }
    }

    /** 模拟计算器工具 */
    static class CalculatorTool implements Tool {
        @Override public String name() { return "calculator"; }
        @Override public String description() { return "计算表达式，参数：expression"; }
        @Override public String execute(Map<String, Object> args) {
            String expr = String.valueOf(args.getOrDefault("expression", "0"));
            // 简化：直接返回模拟结果
            if (expr.contains("25") && expr.contains("*") && expr.contains("4")) {
                return "100";
            }
            return "42";
        }
    }

    /** 总是会失败的工具 */
    static class FailingTool implements Tool {
        @Override public String name() { return "failingTool"; }
        @Override public String description() { return "一个总是失败的工具"; }
        @Override public String execute(Map<String, Object> args) {
            throw new RuntimeException("Network timeout");
        }
    }

    // ========== 测试 1：LLM 直接回答，无需工具调用 ==========

    @Test
    @DisplayName("场景1：LLM 一轮直接给出最终答案（无工具调用）")
    void testDirectAnswerWithoutTool() {
        // 模拟 LLM：无论输入什么，都直接给出答案
        ReActLoop.LlmClient mockLlm = context ->
                ReActLoop.LlmResponse.answer("你好！我是 ReAct Agent，很高兴为你服务。");

        ReActAgent agent = ReActAgent.builder()
                .withLlmClient(mockLlm)
                .withTool(new WeatherTool())
                .build();

        String result = agent.execute("你好");

        // 验证
        assertEquals("你好！我是 ReAct Agent，很高兴为你服务。", result);
        assertEquals(1, agent.getSteps().size(), "应该只有 1 步（直接回答）");
        assertTrue(agent.getSteps().get(0).isFinalAnswer(), "第 0 步应该是最终回答");
    }

    // ========== 测试 2：多步工具调用 ==========

    @Test
    @DisplayName("场景2：多步 ReAct — 查天气 → 基于天气给建议")
    void testMultiStepToolUse() {
        // 模拟 LLM：
        // 第 0 轮：需要查天气 → Action: getWeather
        // 第 1 轮：基于天气结果给出建议 → 最终答案
        ReActLoop.LlmClient mockLlm = new ReActLoop.LlmClient() {
            private int callCount = 0;

            @Override
            public ReActLoop.LlmResponse call(String context) {
                callCount++;
                if (callCount == 1) {
                    // 第一轮：请求工具
                    return ReActLoop.LlmResponse.action(
                            "用户想知道今天是否适合户外活动，我需要先查天气。",
                            Action.of("getWeather", Map.of("city", "北京"))
                    );
                } else {
                    // 第二轮：给出最终答案
                    return ReActLoop.LlmResponse.answer(
                            "今天北京天气晴，25°C，非常适合户外活动！建议穿轻便衣服。"
                    );
                }
            }
        };

        ReActAgent agent = ReActAgent.builder()
                .withLlmClient(mockLlm)
                .withTool(new WeatherTool())
                .build();

        String result = agent.execute("今天适合户外活动吗");

        // 验证结果
        assertEquals("今天北京天气晴，25°C，非常适合户外活动！建议穿轻便衣服。", result);

        // 验证步骤
        List<ReActStep> steps = agent.getSteps();
        assertEquals(2, steps.size(), "应该有 2 步");

        // 第 0 步：调用工具
        ReActStep step0 = steps.get(0);
        assertEquals(0, step0.stepNumber());
        assertEquals("用户想知道今天是否适合户外活动，我需要先查天气。", step0.thought());
        assertEquals("getWeather", step0.action().toolName());
        assertEquals("北京", step0.action().arguments().get("city"));
        assertEquals("北京今天晴，25°C，微风", step0.observation().content());
        assertTrue(step0.isActionSuccess(), "工具调用应成功");
        assertFalse(step0.isFinalAnswer(), "第 0 步不是最终回答");

        // 第 1 步：最终回答
        ReActStep step1 = steps.get(1);
        assertEquals(1, step1.stepNumber());
        assertTrue(step1.isFinalAnswer(), "第 1 步应该是最终回答");
    }

    // ========== 测试 3：工具执行失败后的容错 ==========

    @Test
    @DisplayName("场景3：工具执行失败 → ReAct 继续循环，让 LLM 决定下一步")
    void testToolFailureRecovery() {
        ReActLoop.LlmClient mockLlm = new ReActLoop.LlmClient() {
            private int callCount = 0;

            @Override
            public ReActLoop.LlmResponse call(String context) {
                callCount++;
                if (callCount == 1) {
                    // 第一轮：调用会失败的工具
                    return ReActLoop.LlmResponse.action(
                            "我需要调用 failingTool 获取数据。",
                            Action.of("failingTool", Map.of())
                    );
                } else {
                    // 第二轮：工具失败后，LLM 决定放弃并给出降级回答
                    return ReActLoop.LlmResponse.answer(
                            "抱歉，数据服务暂时不可用，请稍后重试。"
                    );
                }
            }
        };

        ReActAgent agent = ReActAgent.builder()
                .withLlmClient(mockLlm)
                .withTool(new FailingTool())
                .build();

        String result = agent.execute("查询数据");

        assertEquals("抱歉，数据服务暂时不可用，请稍后重试。", result);

        List<ReActStep> steps = agent.getSteps();
        assertEquals(2, steps.size());

        // 验证失败被正确记录
        ReActStep step0 = steps.get(0);
        assertFalse(step0.isActionSuccess(), "第 0 步的工具调用应该失败");
        assertTrue(step0.observation().content().contains("ERROR"), "Observation 应包含错误信息");

        // 验证 ReAct 没有崩溃，而是继续到下一步
        assertTrue(steps.get(1).isFinalAnswer(), "第 1 步应该给出最终降级回答");
    }

    // ========== 测试 4：达到最大步数上限 ==========

    @Test
    @DisplayName("场景4：达到最大步数限制，循环安全终止")
    void testMaxStepsLimit() {
        // 模拟 LLM：永远请求工具，永远不会给出最终答案（恶意/错误行为）
        ReActLoop.LlmClient infiniteToolLlm = context ->
                ReActLoop.LlmResponse.action(
                        "我还需要更多信息...",
                        Action.of("getWeather", Map.of("city", "北京"))
                );

        ReActLoop.ReActConfig config = new ReActLoop.ReActConfig(
                "You are a helpful assistant.",
                3,   // 最大 3 步
                false,
                8000
        );

        ReActAgent agent = ReActAgent.builder()
                .withLlmClient(infiniteToolLlm)
                .withConfig(config)
                .withTool(new WeatherTool())
                .build();

        String result = agent.execute("无限循环测试");

        // 验证达到最大步数后安全终止
        assertTrue(result.contains("Reached maximum steps"), "应提示达到最大步数");
        assertEquals(3, agent.getSteps().size(), "应该正好执行 maxSteps 步");
    }

    // ========== 测试 5：上下文累积验证 ==========

    @Test
    @DisplayName("场景5：验证上下文随步骤累积")
    void testContextAccumulation() {
        // 使用一个特殊的 LLM 客户端来捕获每次调用的上下文
        final List<String> capturedContexts = new java.util.ArrayList<>();

        ReActLoop.LlmClient capturingLlm = context -> {
            capturedContexts.add(context);
            // 第一轮调用工具，第二轮回答
            if (capturedContexts.size() == 1) {
                return ReActLoop.LlmResponse.action(
                        "需要查天气",
                        Action.of("getWeather", Map.of("city", "上海"))
                );
            }
            return ReActLoop.LlmResponse.answer("上海今天天气很好");
        };

        ReActAgent agent = ReActAgent.builder()
                .withLlmClient(capturingLlm)
                .withTool(new WeatherTool())
                .build();

        agent.execute("上海天气");

        // 验证有 2 次 LLM 调用
        assertEquals(2, capturedContexts.size(), "应该有 2 次 LLM 调用");

        // 第一次调用的上下文：只有 system prompt + tools + user query
        String context1 = capturedContexts.get(0);
        assertTrue(context1.contains("User: 上海天气"), "第一次上下文应包含用户问题");
        assertTrue(context1.contains("getWeather"), "第一次上下文应包含工具定义");

        // 第二次调用的上下文：应包含第一次的 Observation
        String context2 = capturedContexts.get(1);
        assertTrue(context2.contains("Observation:"), "第二次上下文应包含 Observation");
        assertTrue(context2.contains("北京今天晴"), "第二次上下文应包含天气结果（WeatherTool 返回固定值）");
    }

    // ========== 测试 6：多工具选择 ==========

    @Test
    @DisplayName("场景6：多个可用工具时，LLM 选择合适的工具")
    void testToolSelection() {
        ReActLoop.LlmClient mockLlm = new ReActLoop.LlmClient() {
            private int callCount = 0;

            @Override
            public ReActLoop.LlmResponse call(String context) {
                callCount++;
                if (callCount == 1) {
                    // 用户问订单，LLM 选择 queryOrder 工具
                    return ReActLoop.LlmResponse.action(
                            "用户查询订单状态，我应该用 queryOrder 工具。",
                            Action.of("queryOrder", Map.of("orderId", "12345"))
                    );
                }
                return ReActLoop.LlmResponse.answer("订单已发货");
            }
        };

        ReActAgent agent = ReActAgent.builder()
                .withLlmClient(mockLlm)
                .withTool(new WeatherTool())   // 可用但未使用
                .withTool(new OrderTool())     // 被选择使用
                .build();

        String result = agent.execute("查一下订单 12345");

        List<ReActStep> steps = agent.getSteps();
        assertEquals("queryOrder", steps.get(0).action().toolName(),
                "LLM 应该选择 queryOrder 工具而不是 getWeather");
        assertEquals("12345", steps.get(0).action().arguments().get("orderId"));
    }

    // ========== 测试 7：对比 ToolCallAdvisor 的测试 ==========

    @Test
    @DisplayName("场景7：ReAct 外循环 vs ToolCallAdvisor 内循环的区别")
    void testReActVsToolCallAdvisor() {
        // 这个测试的目的不是验证功能，而是展示概念差异：
        // ReActLoop：每次 LLM 调用后，应用层有机会介入（如打印日志、检查状态）
        // ToolCallAdvisor：全部在 .call() 内部完成，应用层无感知

        ReActLoop.LlmClient mockLlm = new ReActLoop.LlmClient() {
            private int callCount = 0;

            @Override
            public ReActLoop.LlmResponse call(String context) {
                callCount++;
                System.out.println("[DEBUG] LLM call #" + callCount + " with context length: " + context.length());

                if (callCount == 1) {
                    return ReActLoop.LlmResponse.action(
                            "需要计算",
                            Action.of("calculator", Map.of("expression", "25 * 4"))
                    );
                }
                return ReActLoop.LlmResponse.answer("25 * 4 = 100");
            }
        };

        ReActAgent agent = ReActAgent.builder()
                .withLlmClient(mockLlm)
                .withTool(new CalculatorTool())
                .build();

        String result = agent.execute("25乘4是多少");

        // 关键验证点：ReAct 有 2 次独立的 LLM 调用（外循环）
        // 而 ToolCallAdvisor 只有 1 次 .call()，工具调用在内部完成（内循环）
        assertEquals("25 * 4 = 100", result);
        assertEquals(2, agent.getSteps().size(),
                "ReAct 外循环产生了 2 次 LLM 调用，ToolCallAdvisor 只会产生 1 次");
    }
}
