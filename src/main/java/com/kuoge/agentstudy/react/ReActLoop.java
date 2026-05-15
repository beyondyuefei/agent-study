package com.kuoge.agentstudy.react;

import com.kuoge.agentstudy.react.tool.ToolExecutor;
import com.kuoge.agentstudy.react.tool.ToolRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * ReAct 外循环核心实现。
 *
 * <p>这是 Claude Code ReAct 的 Java 简化版，核心设计思想：
 * <ol>
 *   <li><b>外循环</b>：由应用层（Agent）驱动，每轮都是一次完整的 LLM 调用</li>
 *   <li><b>与 ToolCallAdvisor 的区别</b>：ToolCallAdvisor 是单次 {@code chatClient.call()} 内部的
 *       内循环（1-2 轮）；ReActLoop 是跨多次 {@code chatClient.call()} 的外循环（N 轮）</li>
 *   <li><b>每轮四步</b>：组装上下文 → LLM 推理（Thought + Action）→ 执行工具 → 记录 Observation</li>
 *   <li><b>终止条件</b>：LLM 不再请求工具（给出最终答案）/ 达到最大步数 / 发生不可恢复错误</li>
 * </ol>
 *
 * <h3>学习重点</h3>
 * 打开 {@link #run(String)} 方法，这就是 ReAct 论文中描述的完整循环。
 * 对比 Spring AI 的 {@code ToolCallAdvisor}：
 * <ul>
 *   <li>ToolCallAdvisor：在 {@code .call()} 内部自动循环，调用方无感知</li>
 *   <li>ReActLoop：显式控制每次 LLM 调用，调用方可插入自定义逻辑（如上下文压缩、用户确认）</li>
 * </ul>
 */
@Slf4j
public class ReActLoop {

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final ReActConfig config;

    @Getter
    private final List<ReActStep> steps = new ArrayList<>();

    public ReActLoop(LlmClient llmClient, ToolRegistry toolRegistry, ReActConfig config) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = new ToolExecutor(toolRegistry);
        this.config = config;
    }

    /**
     * 执行 ReAct 循环。
     *
     * @param userQuery 用户原始输入
     * @return 最终答案
     */
    public String run(String userQuery) {
        log.info("ReAct loop started: query='{}', maxSteps={}", userQuery, config.maxSteps());
        steps.clear();

        // 初始化上下文：system prompt + 工具定义 + 用户问题
        final StringBuilder context = new StringBuilder();
        context.append(config.systemPrompt()).append("\n\n");
        context.append(toolRegistry.toPromptText()).append("\n\n");
        context.append("User: ").append(userQuery).append("\n\n");

        for (int step = 0; step < config.maxSteps(); step++) {
            log.debug("ReAct step {} started", step);

            // ===== Step 1: LLM 推理（生成 Thought + Action）=====
            final LlmResponse llmResponse = llmClient.call(context.toString());
            final String thought = llmResponse.thought();
            final Action action = llmResponse.action();

            log.debug("Step {} thought: {}", step, thought);
            log.debug("Step {} action: {}", step, action);

            // ===== Step 2: 检查是否终止（LLM 没有请求工具，直接给出答案）=====
            if (action == null || action.toolName() == null || action.toolName().isBlank()) {
                // LLM 直接给出了最终答案
                final ReActStep finalStep = new ReActStep(
                        step, thought, null, null, Instant.now()
                );
                steps.add(finalStep);
                log.info("ReAct loop finished at step {}: final answer", step);
                return thought; // thought 中包含最终答案
            }

            // ===== Step 3: 执行工具 =====
            final Observation observation = toolExecutor.execute(action);

            // ===== Step 4: 记录步骤 =====
            final ReActStep reactStep = new ReActStep(
                    step, thought, action, observation, Instant.now()
            );
            steps.add(reactStep);

            // ===== Step 5: 更新上下文（将 Observation 注入，供下一轮 LLM 使用）=====
            context.append("Thought: ").append(thought).append("\n");
            context.append("Action: ").append(action).append("\n");
            context.append("Observation: ").append(observation.content()).append("\n\n");

            // ===== Step 6: 上下文压缩（可选，防止过长）=====
            if (config.enableContextCompression() && context.length() > config.contextMaxLength()) {
                context.setLength(config.contextMaxLength());
                context.append("\n...[context truncated]\n");
            }

            log.debug("Step {} completed, observation success={}", step, observation.success());
        }

        // 达到最大步数，返回最后一步的 thought 或提示信息
        log.warn("ReAct loop reached max steps ({}) without final answer", config.maxSteps());
        return "Reached maximum steps without finding a final answer. Last thought: "
                + (steps.isEmpty() ? "N/A" : steps.get(steps.size() - 1).thought());
    }

    /**
     * 打印完整的执行轨迹（用于 debug）。
     */
    public void printTrace() {
        System.out.println("\n========== ReAct Execution Trace ==========");
        for (ReActStep step : steps) {
            System.out.println(step);
        }
        System.out.println("===========================================\n");
    }

    // ========== 内部接口：LLM 客户端 ==========

    /**
     * LLM 客户端抽象接口。
     *
     * <p>在真实环境中，这是 {@code ChatClient} 的包装。
     * 在学习项目中，提供 Mock 实现便于 debug。
     */
    public interface LlmClient {
        /**
         * 调用 LLM，返回解析后的 Thought + Action。
         *
         * @param context 当前完整上下文（system prompt + history + user query）
         * @return LLM 响应（包含 thought 和 action）
         */
        LlmResponse call(String context);
    }

    /**
     * LLM 响应结构。
     */
    public record LlmResponse(String thought, Action action) {
        /**
         * 构造最终回答（无 Action）。
         */
        public static LlmResponse answer(String thought) {
            return new LlmResponse(thought, null);
        }

        /**
         * 构造需要调用工具的响应。
         */
        public static LlmResponse action(String thought, Action action) {
            return new LlmResponse(thought, action);
        }
    }

    // ========== 配置 ==========

    public record ReActConfig(
            String systemPrompt,
            int maxSteps,
            boolean enableContextCompression,
            int contextMaxLength
    ) {
        public static ReActConfig defaults() {
            return new ReActConfig(
                    "You are a helpful assistant. Solve the user's request step by step.",
                    10,
                    true,
                    8000
            );
        }
    }
}
