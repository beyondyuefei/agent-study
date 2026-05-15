package com.kuoge.agentstudy.production.runtime.core;

import com.kuoge.agentstudy.production.runtime.client.LlmClient;
import com.kuoge.agentstudy.production.runtime.client.LlmResponse;
import com.kuoge.agentstudy.production.runtime.compact.CompactionResult;
import com.kuoge.agentstudy.production.runtime.compact.SessionCompactor;
import com.kuoge.agentstudy.production.runtime.hook.HookEvent;
import com.kuoge.agentstudy.production.runtime.hook.HookResult;
import com.kuoge.agentstudy.production.runtime.hook.ToolHook;
import com.kuoge.agentstudy.production.runtime.permission.PermissionOutcome;
import com.kuoge.agentstudy.production.runtime.session.AgentSession;
import com.kuoge.agentstudy.production.runtime.session.ContentBlock;
import com.kuoge.agentstudy.production.runtime.session.ConversationMessage;
import com.kuoge.agentstudy.production.runtime.session.MessageRole;
import com.kuoge.agentstudy.production.runtime.usage.TokenUsage;
import com.kuoge.agentstudy.production.runtime.usage.UsageTracker;
import com.kuoge.agentstudy.production.tool.Tool;
import com.kuoge.agentstudy.production.tool.ToolRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 生产级 ReAct 运行时 —— 核心对话循环。
 *
 * <p>对应 claw-code Rust 实现：{@code conversation.rs/ConversationRuntime}
 *
 * <h3>与基础版 ReActLoop 的核心区别</h3>
 * <table border="1">
 *   <tr><th>维度</th><th>ReActLoop（基础版）</th><th>ConversationRuntime（生产级）</th></tr>
 *   <tr><td>消息模型</td><td>StringBuilder 拼接</td><td>ConversationMessage + ContentBlock 结构化</td></tr>
 *   <tr><td>Turn 内迭代</td><td>1 轮 LLM = 1 步</td><td>1 Turn 可包含 N 轮 LLM（工具调用链）</td></tr>
 *   <tr><td>工具调用</td><td>单工具串行</td><td>支持多工具并行（LLM 一次返回多个 ToolUse）</td></tr>
 *   <tr><td>权限控制</td><td>无</td><td>PermissionPolicy + 规则引擎</td></tr>
 *   <tr><td>钩子系统</td><td>无</td><td>Pre/Post/Failure 工具钩子</td></tr>
 *   <tr><td>自动压缩</td><td>手动截断字符串</td><td>基于 token 阈值自动压缩 + 边界保护</td></tr>
 *   <tr><td>用量追踪</td><td>无</td><td>TokenUsage + UsageTracker（精确到每次调用）</td></tr>
 *   <tr><td>会话管理</td><td>无状态</td><td>AgentSession（消息历史 + Fork + 持久化接口）</td></tr>
 *   <tr><td>健康检查</td><td>无</td><td>Session health probe（压缩后验证）</td></tr>
 * </table>
 *
 * <h3>执行流程（runTurn）</h3>
 * <pre>
 * User Input
 *    │
 *    ▼
 * ┌─────────────────────────────────────────┐
 * │  Session Health Probe (if compacted)    │
 * └─────────────────────────────────────────┘
 *    │
 *    ▼
 * ┌─────────────────────────────────────────┐
 * │  Push user message to session           │
 * └─────────────────────────────────────────┘
 *    │
 *    ▼
 * ┌─────────────────────────────────────────┐
 * │  LOOP (iterations)                      │
 * │  ├─ Build ApiRequest (system + messages)│
 * │  ├─ LLM call → LlmResponse              │
 * │  ├─ Parse blocks (Text/Thinking/ToolUse)│
 * │  ├─ Push assistant message to session   │
 * │  ├─ Extract pending tool uses           │
 * │  ├─ IF no tool uses → BREAK             │
 * │  ├─ FOR each tool use:                  │
 * │  │   ├─ Run PreToolUse hook             │
 * │  │   ├─ Permission check                │
 * │  │   ├─ Execute tool                    │
 * │  │   ├─ Run PostToolUse hook            │
 * │  │   └─ Push ToolResult to session      │
 * │  └─ END FOR                             │
 * └─────────────────────────────────────────┘
 *    │
 *    ▼
 * ┌─────────────────────────────────────────┐
 * │  Auto-compaction (if threshold exceeded)│
 * └─────────────────────────────────────────┘
 *    │
 *    ▼
 * TurnSummary
 * </pre>
 */
@Slf4j
public class ConversationRuntime {

    @Getter
    private final AgentSession session;
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final RuntimeConfig config;
    private final UsageTracker usageTracker;
    private final SessionCompactor compactor;
    private final List<ToolHook> hooks = new ArrayList<>();

    @Getter
    private int turnCount = 0;

    public ConversationRuntime(
            AgentSession session,
            LlmClient llmClient,
            ToolRegistry toolRegistry,
            RuntimeConfig config
    ) {
        this.session = session;
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.config = config;
        this.usageTracker = new UsageTracker();
        this.compactor = new SessionCompactor();
    }

    /**
     * 注册工具钩子。
     */
    public ConversationRuntime addHook(ToolHook hook) {
        this.hooks.add(hook);
        return this;
    }

    /**
     * 执行一次 Turn（用户输入 → AI 响应 → 工具执行 → 最终答案）。
     *
     * <p>一次 Turn 内可能包含多轮 LLM 调用（当 AI 需要链式调用多个工具时）。
     */
    public TurnSummary runTurn(String userInput) {
        log.info("[Turn {}] Started: input='{}'", turnCount + 1, truncate(userInput, 100));

        // 1. Session Health Probe（如果会话被压缩过）
        if (!session.getCompactionHistory().isEmpty()) {
            runHealthProbe();
        }

        // 2. 检查会话 turn 限制
        if (turnCount >= config.maxTurnsPerSession()) {
            log.warn("[Turn {}] Max turns reached", turnCount);
            return buildSummary(userInput, List.of(), List.of(), 0, TokenUsage.empty(),
                    "max_turns_reached", null);
        }

        // 3. 推送用户消息
        session.pushUserText(userInput);

        List<ConversationMessage> assistantMessages = new ArrayList<>();
        List<ConversationMessage> toolResults = new ArrayList<>();
        int iterations = 0;
        TokenUsage turnUsage = TokenUsage.empty();

        // 4. 核心迭代循环
        while (true) {
            iterations++;
            if (iterations > config.maxIterationsPerTurn()) {
                log.warn("[Turn {}] Max iterations reached ({})", turnCount + 1, iterations);
                return buildSummary(userInput, assistantMessages, toolResults, iterations,
                        turnUsage, "max_iterations_reached", null);
            }

            // 4.1 构建请求并调用 LLM
            List<ConversationMessage> requestMessages = session.getMessages();
            LlmResponse response = llmClient.call(config.systemPrompt(), requestMessages);

            if (response == null || response.isEmpty()) {
                log.error("[Turn {}] Empty LLM response at iteration {}", turnCount + 1, iterations);
                return buildSummary(userInput, assistantMessages, toolResults, iterations,
                        turnUsage, "empty_response", null);
            }

            // 4.2 记录用量
            TokenUsage usage = response.usage();
            usageTracker.record(usage);
            turnUsage = turnUsage.add(usage);

            // 4.3 构建 Assistant 消息并推入会话
            ConversationMessage assistantMsg = new ConversationMessage(
                    MessageRole.ASSISTANT,
                    response.blocks(),
                    usage
            );
            session.pushMessage(assistantMsg);
            assistantMessages.add(assistantMsg);

            log.debug("[Turn {}] Iteration {}: assistant_msg_blocks={}",
                    turnCount + 1, iterations, response.blocks().size());

            // 4.4 提取待执行的工具调用
            List<ContentBlock.ToolUseBlock> pendingTools = response.toolUses();
            if (pendingTools.isEmpty()) {
                // 没有工具调用，Turn 结束
                log.info("[Turn {}] Completed after {} iterations (no more tools)",
                        turnCount + 1, iterations);
                break;
            }

            log.info("[Turn {}] Iteration {}: {} pending tool use(s): {}",
                    turnCount + 1, iterations, pendingTools.size(),
                    pendingTools.stream().map(ContentBlock.ToolUseBlock::toolName).toList());

            // 4.5 执行每个工具调用
            for (ContentBlock.ToolUseBlock toolUse : pendingTools) {
                ConversationMessage result = executeToolUse(toolUse);
                session.pushMessage(result);
                toolResults.add(result);
            }
        }

        // 5. 自动压缩（如果启用且超过阈值）
        CompactionResult compaction = null;
        if (config.autoCompactionEnabled()
                && compactor.shouldCompact(session, config.compactionConfig())) {
            compaction = compactor.compact(session, config.compactionConfig());
            if (compaction.wasCompacted()) {
                // 替换会话为压缩后的版本
                // 注意：我们需要一种方式来替换 session，但 AgentSession 的 messages 是私有的
                // 通过 AgentSession.replaceMessages 替换消息列表
                session.replaceMessages(compaction.compactedSession().getMessages());
                log.info("[Turn {}] Auto-compacted: removed {} messages",
                        turnCount + 1, compaction.removedMessageCount());
            }
        }

        turnCount++;
        return buildSummary(userInput, assistantMessages, toolResults, iterations,
                turnUsage, "completed", compaction);
    }

    /**
     * 执行单次工具调用（含钩子 + 权限 + 执行）。
     */
    private ConversationMessage executeToolUse(ContentBlock.ToolUseBlock toolUse) {
        String toolName = toolUse.toolName();
        String input = toolUse.input();
        String toolUseId = toolUse.toolUseId();

        // 1. PreToolUse 钩子
        HookResult preHook = runHooks(HookEvent.PreToolUse, toolName, input, null, false);
        if (preHook.cancelled()) {
            return ConversationMessage.toolResult(toolUseId, toolName,
                    "Tool execution cancelled by pre-hook: " + preHook.messages(), true);
        }
        if (preHook.denied()) {
            return ConversationMessage.toolResult(toolUseId, toolName,
                    "Tool execution denied by pre-hook: " + preHook.messages(), true);
        }

        // 应用钩子可能修改的输入
        String effectiveInput = preHook.updatedInput() != null ? preHook.updatedInput() : input;

        // 2. 权限检查
        PermissionOutcome permission = config.permissionPolicy().authorize(toolName, effectiveInput);
        if (permission instanceof PermissionOutcome.Deny deny) {
            return ConversationMessage.toolResult(toolUseId, toolName,
                    "Permission denied: " + deny.reason(), true);
        }
        if (permission instanceof PermissionOutcome.Ask ask) {
            // 当前实现：Ask 视为 Deny（待接入交互 UI 后可改为真正的 Ask 流程）
            return ConversationMessage.toolResult(toolUseId, toolName,
                    "Permission requires approval: " + ask.reason(), true);
        }

        // 3. 执行工具
        String output;
        boolean isError;
        try {
            Tool tool = toolRegistry.find(toolName).orElse(null);
            if (tool == null) {
                output = "Unknown tool: " + toolName;
                isError = true;
            } else {
                Map<String, Object> args = parseToolInput(effectiveInput);
                output = tool.execute(args);
                isError = false;
            }
        } catch (Exception e) {
            output = "Tool execution error: " + e.getMessage();
            isError = true;
        }

        // 合并 pre-hook 的反馈消息到输出
        output = mergeHookFeedback(preHook.messages(), output, false);

        // 4. PostToolUse / PostToolUseFailure 钩子
        HookEvent postEvent = isError ? HookEvent.PostToolUseFailure : HookEvent.PostToolUse;
        HookResult postHook = runHooks(postEvent, toolName, effectiveInput, output, isError);
        if (postHook.denied() || postHook.failed() || postHook.cancelled()) {
            isError = true;
        }
        output = mergeHookFeedback(postHook.messages(), output,
                postHook.denied() || postHook.failed() || postHook.cancelled());

        return ConversationMessage.toolResult(toolUseId, toolName, output, isError);
    }

    /**
     * 运行所有已注册的钩子。
     */
    private HookResult runHooks(HookEvent event, String toolName, String input,
                                 String output, boolean isError) {
        HookResult merged = HookResult.allow();
        for (ToolHook hook : hooks) {
            HookResult result = switch (event) {
                case PreToolUse -> hook.preToolUse(toolName, input);
                case PostToolUse -> hook.postToolUse(toolName, input, output);
                case PostToolUseFailure -> hook.postToolUseFailure(toolName, input, output);
            };
            merged = mergeHookResults(merged, result);
            // 如果任一钩子拒绝/失败/取消，立即停止
            if (result.denied() || result.failed() || result.cancelled()) {
                break;
            }
        }
        return merged;
    }

    private HookResult mergeHookResults(HookResult a, HookResult b) {
        List<String> messages = new ArrayList<>(a.messages());
        messages.addAll(b.messages());
        return new HookResult(
                a.denied() || b.denied(),
                a.failed() || b.failed(),
                a.cancelled() || b.cancelled(),
                messages,
                b.permissionOverride() != null ? b.permissionOverride() : a.permissionOverride(),
                b.permissionReason() != null ? b.permissionReason() : a.permissionReason(),
                b.updatedInput() != null ? b.updatedInput() : a.updatedInput()
        );
    }

    private String mergeHookFeedback(List<String> messages, String output, boolean isError) {
        if (messages.isEmpty()) {
            return output;
        }
        StringBuilder sb = new StringBuilder();
        if (!output.isBlank()) {
            sb.append(output);
        }
        String label = isError ? "Hook feedback (error)" : "Hook feedback";
        sb.append("\n\n").append(label).append(":\n");
        sb.append(String.join("\n", messages));
        return sb.toString();
    }

    private void runHealthProbe() {
        // 基础健康检查：验证工具执行器是否可响应
        log.debug("Running session health probe");
        // 在实际实现中，这里会尝试执行一个无害的探针工具
    }

    private TurnSummary buildSummary(String userInput,
                                      List<ConversationMessage> assistantMessages,
                                      List<ConversationMessage> toolResults,
                                      int iterations,
                                      TokenUsage usage,
                                      String stopReason,
                                      CompactionResult compaction) {
        return TurnSummary.builder()
                .userInput(userInput)
                .assistantMessages(assistantMessages)
                .toolResults(toolResults)
                .iterations(iterations)
                .usage(usage)
                .stopReason(stopReason)
                .autoCompaction(compaction)
                .build();
    }

    private String truncate(String text, int maxLen) {
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    /**
     * 将工具输入字符串解析为参数 Map。
     * 支持 JSON 格式和简单键值对格式。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseToolInput(String input) {
        if (input == null || input.isBlank()) {
            return Map.of();
        }
        String trimmed = input.trim();
        // 尝试解析为 JSON
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                // 使用简单的 key-value 解析（不依赖外部 JSON 库）
                return parseSimpleJson(trimmed);
            } catch (Exception e) {
                // 解析失败，返回原始字符串作为 "raw" 参数
                return Map.of("raw", input);
            }
        }
        // 非 JSON，返回原始字符串
        return Map.of("raw", input);
    }

    private Map<String, Object> parseSimpleJson(String json) {
        Map<String, Object> result = new HashMap<>();
        // 去除首尾大括号
        String content = json.substring(1, json.length() - 1).trim();
        if (content.isEmpty()) return result;

        // 简单的分词解析（不支持嵌套对象）
        String[] pairs = content.split(",");
        for (String pair : pairs) {
            String[] kv = pair.split(":", 2);
            if (kv.length == 2) {
                String key = kv[0].trim().replace("\"", "").replace("'", "");
                String value = kv[1].trim();
                // 去除字符串引号
                if ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                result.put(key, value);
            }
        }
        return result;
    }
}
