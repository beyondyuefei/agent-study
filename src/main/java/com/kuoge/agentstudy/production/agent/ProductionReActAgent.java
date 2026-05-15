package com.kuoge.agentstudy.production.agent;

import com.kuoge.agentstudy.production.context.*;
import com.kuoge.agentstudy.production.cost.*;
import com.kuoge.agentstudy.production.memory.*;
import com.kuoge.agentstudy.react.*;
import com.kuoge.agentstudy.react.tool.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;

/**
 * 生产级 ReAct Agent —— 整合记忆、上下文压缩、成本控制的完整实现。
 *
 * <h3>相比教学版 ReActLoop 的增强</h3>
 * <table border="1">
 *   <tr><th>能力</th><th>教学版 ReActLoop</th><th>ProductionReActAgent</th></tr>
 *   <tr><td>记忆</td><td>无</td><td>PreferenceMemory（核心偏好 + 归档偏好）</td></tr>
 *   <tr><td>上下文管理</td><td>StringBuilder 拼接</td><td>9段式结构化压缩</td></tr>
 *   <tr><td>成本控制</td><td>无</td><td>TokenBudget + PromptCache + CostTracker</td></tr>
 *   <tr><td>懒加载</td><td>无</td><td>工具定义按需加载</td></tr>
 *   <tr><td>可观测性</td><td>简单日志</td><td>完整的执行报告</td></tr>
 * </table>
 *
 * <h3>使用示例</h3>
 * <pre>
 * ProductionReActAgent agent = ProductionReActAgent.builder()
 *     .withLlmClient(chatClientAdapter)
 *     .withPreferenceMemory(preferenceMemory)
 *     .withContextBudget(8000)        // 8k token 上下文预算
 *     .withTool(weatherTool)
 *     .withTool(orderTool)
 *     .build();
 *
 * AgentResult result = agent.execute("user-001", "查一下我的订单");
 * System.out.println(result.answer());
 * System.out.println(result.costReport());
 * </pre>
 */
@Slf4j
@RequiredArgsConstructor
public class ProductionReActAgent {

    private final ReActLoop.LlmClient llmClient;
    private final PreferenceMemory preferenceMemory;
    private final ToolRegistry toolRegistry;
    private final int contextBudget;

    // 生产级组件
    private final PromptTemplateCache promptCache = new PromptTemplateCache();
    private final CostTracker costTracker = new CostTracker();

    /**
     * 执行用户请求（生产级完整流程）。
     */
    public AgentResult execute(String userId, String userQuery) {
        final long startTime = System.currentTimeMillis();
        log.info("[ProductionAgent] Start: user={}, query='{}'", userId, userQuery);

        // ===== 1. 初始化 Token 预算 =====
        final TokenBudget budget = TokenBudget.standard(contextBudget);

        // ===== 2. 初始化 9段式上下文压缩器 =====
        final ContextCompressor compressor = new ContextCompressor(contextBudget);

        // ===== 3. 加载核心偏好（PreferenceMemory）=====
        final String preferenceText = preferenceMemory != null
                ? preferenceMemory.buildCorePreferencePrompt(userId)
                : "";
        compressor.segment(SegmentType.USER_PREFERENCE).setContent(preferenceText);

        // ===== 4. 设置系统身份（缓存，避免重复渲染）=====
        final String systemPrompt = loadSystemPrompt();
        compressor.segment(SegmentType.SYSTEM_IDENTITY).setContent(systemPrompt);

        // ===== 5. 设置当前目标 =====
        compressor.segment(SegmentType.CURRENT_GOAL).setContent(
                "Current task: " + userQuery + "\n");

        // ===== 6. 设置工具定义（LAZY_LOAD —— 只加载相关工具）=====
        // 生产级：分析用户意图，只加载可能用到的工具
        // 学习项目：简化，加载全部工具定义
        final String toolDefs = toolRegistry.toPromptText();
        compressor.segment(SegmentType.TOOL_DEFINITIONS).setContent(toolDefs);
        budget.recordInput(ContextSegment.estimateTokens(toolDefs));

        // ===== 7. ReAct 外循环 =====
        final List<ReActStep> steps = new ArrayList<>();
        StringBuilder workingMemory = new StringBuilder();

        for (int step = 0; step < 10; step++) { // 最大 10 步
            // 更新工作记忆
            compressor.segment(SegmentType.WORKING_MEMORY)
                    .setContent(workingMemory.toString());

            // 构建压缩后的上下文
            final String context = compressor.build();
            final int contextTokens = ContextSegment.estimateTokens(context);
            budget.recordInput(contextTokens);

            // 检查预算
            if (budget.isInputOverBudget()) {
                log.warn("Input budget exceeded at step {}", step);
                break;
            }

            // LLM 调用
            final long llmStart = System.currentTimeMillis();
            final ReActLoop.LlmResponse response = llmClient.call(context);
            final long llmLatency = System.currentTimeMillis() - llmStart;

            // 估算 token（简化）
            final int outputTokens = ContextSegment.estimateTokens(response.thought());
            costTracker.recordLlmCall(contextTokens, outputTokens, llmLatency, "default");
            budget.recordOutput(outputTokens);

            // 解析 Thought + Action
            final String thought = response.thought();
            final Action action = response.action();

            log.debug("[Step {}] Thought: {}", step, thought);

            // 检查是否终止
            if (action == null || action.toolName() == null || action.toolName().isBlank()) {
                // 最终答案
                steps.add(new ReActStep(step, thought, null, null, Instant.now()));
                log.info("[ProductionAgent] Finished at step {}: final answer", step);

                return new AgentResult(
                        thought,
                        steps,
                        costTracker.report(),
                        budget.report(),
                        System.currentTimeMillis() - startTime
                );
            }

            // 执行工具
            final long toolStart = System.currentTimeMillis();
            final ToolExecutor executor = new ToolExecutor(toolRegistry);
            final Observation observation = executor.execute(action);
            final long toolLatency = System.currentTimeMillis() - toolStart;
            costTracker.recordToolCall(action.toolName(), toolLatency);

            // 记录步骤
            steps.add(new ReActStep(step, thought, action, observation, Instant.now()));

            // 更新工作记忆
            workingMemory.append("Step ").append(step).append(": ")
                    .append(action).append(" → ")
                    .append(observation.content()).append("\n");

            // 更新上下文（追加 Observation）
            compressor.segment(SegmentType.RECENT_HISTORY).append(
                    "Thought: " + thought + "\n" +
                    "Action: " + action + "\n" +
                    "Observation: " + observation.content() + "\n\n");
        }

        // 达到最大步数
        log.warn("[ProductionAgent] Reached max steps");
        return new AgentResult(
                "Reached maximum steps. Partial results:\n" + workingMemory,
                steps,
                costTracker.report(),
                budget.report(),
                System.currentTimeMillis() - startTime
        );
    }

    // ========== 工具方法 ==========

    private String loadSystemPrompt() {
        final String cacheKey = "system_prompt:v1";
        if (promptCache.has(cacheKey)) {
            return promptCache.get(cacheKey).content();
        }
        final String prompt = """
                You are a helpful AI assistant with access to tools.
                Follow the ReAct pattern: think step by step, then use tools if needed.
                Always reason about your approach before taking action.
                """;
        final int tokens = ContextSegment.estimateTokens(prompt);
        promptCache.put(cacheKey, prompt, tokens);
        return prompt;
    }

    // ========== Builder ==========

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private ReActLoop.LlmClient llmClient;
        private PreferenceMemory preferenceMemory;
        private final ToolRegistry toolRegistry = new ToolRegistry();
        private int contextBudget = 8000;

        public Builder withLlmClient(ReActLoop.LlmClient client) {
            this.llmClient = client;
            return this;
        }

        public Builder withPreferenceMemory(PreferenceMemory memory) {
            this.preferenceMemory = memory;
            return this;
        }

        public Builder withContextBudget(int budget) {
            this.contextBudget = budget;
            return this;
        }

        public Builder withTool(Tool tool) {
            this.toolRegistry.register(tool);
            return this;
        }

        public ProductionReActAgent build() {
            if (llmClient == null) throw new IllegalStateException("LLM client required");
            return new ProductionReActAgent(llmClient, preferenceMemory, toolRegistry, contextBudget);
        }
    }

    // ========== 结果 ==========

    public record AgentResult(
            String answer,
            List<ReActStep> steps,
            String costReport,
            String budgetReport,
            long totalLatencyMs
    ) {}
}
