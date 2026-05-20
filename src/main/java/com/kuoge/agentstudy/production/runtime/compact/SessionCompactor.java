package com.kuoge.agentstudy.production.runtime.compact;

import com.kuoge.agentstudy.production.runtime.session.AgentSession;
import com.kuoge.agentstudy.production.runtime.session.ContentBlock;
import com.kuoge.agentstudy.production.runtime.session.ConversationMessage;
import com.kuoge.agentstudy.production.runtime.session.MessageRole;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话自动压缩器 —— 将过长的对话历史总结为摘要，保留最近消息。
 *
 * <p>对应 claw-code Rust 实现：{@code compact.rs}
 *
 * <h3>核心算法</h3>
 * <ol>
 *   <li>检查可压缩部分（排除已存在的压缩摘要和最近保留消息）的 token 数</li>
 *   <li>如果超过阈值，则对旧消息生成结构化摘要</li>
 *   <li>保留最近 N 条消息原文</li>
 *   <li><b>边界保护</b>：确保不拆分 ToolUse/ToolResult 对（防止 API 400 错误）</li>
 *   <li>将摘要作为 System 消息插入会话头部</li>
 * </ol>
 *
 * <h3>摘要结构</h3>
 * <pre>
 * Conversation summary:
 * - Scope: X earlier messages compacted (user=A, assistant=B, tool=C).
 * - Tools mentioned: toolA, toolB.
 * - Recent user requests: ...
 * - Pending work: ...
 * - Key files referenced: ...
 * - Current work: ...
 * - Key timeline:
 *   - user: ...
 *   - assistant: ...
 *   - tool: ...
 * </pre>
 */
@Slf4j
public class SessionCompactor {

    private static final String COMPACT_PREAMBLE =
            "This session is being continued from a previous conversation that ran out of context. " +
            "The summary below covers the earlier portion of the conversation.\n\n";

    private static final String RECENT_MESSAGES_NOTE = "Recent messages are preserved verbatim.";
    private static final String DIRECT_RESUME_INSTRUCTION =
            "Continue the conversation from where it left off without asking the user any further questions. " +
            "Resume directly — do not acknowledge the summary.";

    /**
     * 判断会话是否需要压缩。
     */
    public boolean shouldCompact(AgentSession session, CompactionConfig config) {
        List<ConversationMessage> messages = session.getMessages();
        int compactableStart = compactableStartIndex(messages);
        List<ConversationMessage> compactable = messages.subList(compactableStart, messages.size());

        if (compactable.size() <= config.preserveRecentMessages()) {
            return false;
        }

        int compactableTokens = compactable.stream()
                .mapToInt(ConversationMessage::estimateTokens)
                .sum();
        return compactableTokens >= config.maxEstimatedTokens();
    }

    /**
     * 执行会话压缩。
     */
    public CompactionResult compact(AgentSession session, CompactionConfig config) {
        if (!shouldCompact(session, config)) {
            return CompactionResult.empty(session);
        }

        List<ConversationMessage> messages = new ArrayList<>(session.getMessages());
        int compactableStart = compactableStartIndex(messages);
        int rawKeepFrom = Math.max(compactableStart, messages.size() - config.preserveRecentMessages());

        // 边界保护：不拆分 ToolUse/ToolResult 对
        int keepFrom = adjustBoundary(messages, rawKeepFrom, compactableStart);

        List<ConversationMessage> removed = new ArrayList<>(messages.subList(compactableStart, keepFrom));
        List<ConversationMessage> preserved = new ArrayList<>(messages.subList(keepFrom, messages.size()));

        // 生成摘要
        String summary = summarizeMessages(removed);
        String formattedSummary = formatCompactSummary(summary);
        String continuation = buildContinuationMessage(formattedSummary, !preserved.isEmpty());

        // 构建压缩后的会话
        AgentSession compacted = new AgentSession(session.getSessionId());
        compacted.withWorkspaceRoot(session.getWorkspaceRoot());
        compacted.pushMessage(ConversationMessage.systemText(continuation));
        for (ConversationMessage msg : preserved) {
            compacted.pushMessage(msg);
        }
        compacted.recordCompaction(removed.size(), formattedSummary);

        log.info("Session compacted: removed {} messages, preserved {}, summary={} chars",
                removed.size(), preserved.size(), formattedSummary.length());

        return new CompactionResult(summary, formattedSummary, compacted, removed.size());
    }

    // ========== 内部方法 ==========

    /**
     * 确定可压缩部分的起始索引（跳过已有的压缩摘要 system 消息）。
     */
    private int compactableStartIndex(List<ConversationMessage> messages) {
        if (messages.isEmpty()) return 0;
        // 如果第一条是 system 消息且包含压缩摘要标记，跳过它
        ConversationMessage first = messages.get(0);
        if (first.role() == MessageRole.SYSTEM && first.hasToolUse() == false) {
            String text = first.extractText();
            if (text.contains(COMPACT_PREAMBLE.trim())) {
                return 1;
            }
        }
        return 0;
    }

    /**
     * 边界保护：调整 keepFrom，确保压缩边界不落在 ToolUse/ToolResult 对的中间。
     *
     * <h3>问题根因</h3>
     * <p>Anthropic / OpenAI 兼容层的消息序列有一条硬约束：
     * <b>ToolResult 消息（role=tool）前面必须有包含 ToolUse 的 Assistant 消息</b>。
     * 违反此约束会导致 API 返回 400："tool message must follow assistant with tool_calls"。
     *
     * <p>压缩算法在截取"保留最近 N 条"时，如果截断点恰好落在 assistant(ToolUse) 和
     * tool(ToolResult) 之间，就会产生一条"孤儿 ToolResult"——它被保留了，但它前面的
     * assistant(ToolUse) 被压缩掉了。这种状态发送给 API 直接 400。
     *
     * <h3>复现场景（来自 Rust 实现的真实 Bug 记录）</h3>
     * <pre>
     * 会话消息：
     *   [0] user:      "Search files"
     *   [1] assistant: ToolUse("search", "{}")        ← 被压缩掉
     *   [2] tool:      ToolResult("search", "found")  ← 成为保留区第一条，孤儿！
     *   [3] assistant: "Done."
     *
     * preserveRecentMessages=2 时：rawKeepFrom=2，保留区首条是 ToolResult → 400
     * </pre>
     *
     * <h3>修复思路（walk-back 算法）</h3>
     * <ol>
     *   <li>如果保留区首条不是 ToolResult → 安全，不用调整</li>
     *   <li>如果是 ToolResult，检查它前面那条消息是否含 ToolUse</li>
     *   <li>如果前面有 ToolUse → 配对完整，把 keepFrom 再向前移一位，将 Assistant 也纳入保留区</li>
     *   <li>如果前面没有 ToolUse → 配对已经损坏（罕见），继续向前走，直到找到安全点</li>
     * </ol>
     *
     * <p>对应 claw-code Rust 实现：{@code compact.rs} 的 keep_from 边界修正逻辑，
     * 修复于 2026-04-09（Rust 注释中记为 "gaebal-gajae repro"）。
     *
     * @param messages     完整消息列表
     * @param rawKeepFrom  原始保留起始索引（未经保护调整）
     * @param minKeepFrom  不能回退超过此位置（已有摘要的起始位置）
     * @return 调整后的安全 keepFrom 索引
     */
    private int adjustBoundary(List<ConversationMessage> messages, int rawKeepFrom, int minKeepFrom) {
        int k = rawKeepFrom;
        while (k > minKeepFrom) {
            ConversationMessage firstPreserved = messages.get(k);

            // 保留区首条不是 ToolResult → 边界安全，无需调整
            if (firstPreserved.role() != MessageRole.TOOL) {
                break;
            }

            // 首条是 ToolResult，检查前一条是否含 ToolUse（配对是否完整）
            ConversationMessage preceding = messages.get(k - 1);
            boolean precedingHasToolUse = preceding.role() == MessageRole.ASSISTANT && preceding.hasToolUse();

            if (precedingHasToolUse) {
                // 配对完整：将 keepFrom 前移一位，把 assistant(ToolUse) 也纳入保留区
                // 此时 [assistant(ToolUse), tool(ToolResult), ...] 成为保留区头部，满足 API 约束
                k--;
                break;
            }

            // 前面那条没有 ToolUse（异常状态）：继续向前走，寻找更安全的截断点
            k--;
        }
        return k;
    }

    /**
     * 生成消息摘要。
     */
    private String summarizeMessages(List<ConversationMessage> messages) {
        long userCount = messages.stream().filter(m -> m.role() == MessageRole.USER).count();
        long assistantCount = messages.stream().filter(m -> m.role() == MessageRole.ASSISTANT).count();
        long toolCount = messages.stream().filter(m -> m.role() == MessageRole.TOOL).count();

        List<String> toolNames = messages.stream()
                .flatMap(m -> m.blocks().stream())
                .filter(b -> b instanceof ContentBlock.ToolUseBlock || b instanceof ContentBlock.ToolResultBlock)
                .map(b -> {
                    if (b instanceof ContentBlock.ToolUseBlock tu) return tu.toolName();
                    if (b instanceof ContentBlock.ToolResultBlock tr) return tr.toolName();
                    return "";
                })
                .filter(name -> !name.isEmpty())
                .distinct()
                .sorted()
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("<summary>\n");
        sb.append("Conversation summary:\n");
        sb.append(String.format("- Scope: %d earlier messages compacted (user=%d, assistant=%d, tool=%d).\n",
                messages.size(), userCount, assistantCount, toolCount));

        if (!toolNames.isEmpty()) {
            sb.append("- Tools mentioned: ").append(String.join(", ", toolNames)).append(".\n");
        }

        // 提取最近的用户请求
        List<String> recentRequests = extractRecentUserRequests(messages, 3);
        if (!recentRequests.isEmpty()) {
            sb.append("- Recent user requests:\n");
            for (String req : recentRequests) {
                sb.append("  - ").append(req).append("\n");
            }
        }

        // 提取待办工作
        List<String> pendingWork = extractPendingWork(messages);
        if (!pendingWork.isEmpty()) {
            sb.append("- Pending work:\n");
            for (String work : pendingWork) {
                sb.append("  - ").append(work).append("\n");
            }
        }

        // 提取关键文件
        List<String> keyFiles = extractKeyFiles(messages);
        if (!keyFiles.isEmpty()) {
            sb.append("- Key files referenced: ").append(String.join(", ", keyFiles)).append(".\n");
        }

        // Key timeline
        sb.append("- Key timeline:\n");
        for (ConversationMessage msg : messages) {
            String role = msg.role().name().toLowerCase();
            String content = summarizeMessage(msg);
            sb.append(String.format("  - %s: %s\n", role, content));
        }
        sb.append("</summary>");

        return sb.toString();
    }

    private String formatCompactSummary(String summary) {
        // 移除 <summary> 标签
        String cleaned = summary.replace("<summary>\n", "").replace("\n</summary>", "");
        return cleaned.trim();
    }

    private String buildContinuationMessage(String formattedSummary, boolean hasPreservedMessages) {
        StringBuilder sb = new StringBuilder();
        sb.append(COMPACT_PREAMBLE);
        sb.append(formattedSummary);
        if (hasPreservedMessages) {
            sb.append("\n\n").append(RECENT_MESSAGES_NOTE);
        }
        sb.append("\n").append(DIRECT_RESUME_INSTRUCTION);
        return sb.toString();
    }

    private String summarizeMessage(ConversationMessage msg) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : msg.blocks()) {
            switch (block) {
                case ContentBlock.TextBlock t -> sb.append(truncate(t.text(), 160));
                case ContentBlock.ThinkingBlock th ->
                        sb.append("thinking(").append(th.thinking().length()).append(" chars)");
                case ContentBlock.ToolUseBlock tu ->
                        sb.append("tool_use ").append(tu.toolName()).append("(").append(truncate(tu.input(), 80)).append(")");
                case ContentBlock.ToolResultBlock tr -> {
                    sb.append("tool_result ").append(tr.toolName()).append(": ");
                    if (tr.isError()) sb.append("error ");
                    sb.append(truncate(tr.output(), 120));
                }
            }
            sb.append(" | ");
        }
        String result = sb.toString();
        if (result.endsWith(" | ")) {
            result = result.substring(0, result.length() - 3);
        }
        return result;
    }

    private List<String> extractRecentUserRequests(List<ConversationMessage> messages, int limit) {
        List<String> requests = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0 && requests.size() < limit; i--) {
            ConversationMessage msg = messages.get(i);
            if (msg.role() == MessageRole.USER) {
                String text = extractFirstText(msg);
                if (text != null && !text.isBlank()) {
                    requests.add(0, truncate(text, 160));
                }
            }
        }
        return requests;
    }

    private List<String> extractPendingWork(List<ConversationMessage> messages) {
        List<String> pending = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0 && pending.size() < 3; i--) {
            String text = extractFirstText(messages.get(i));
            if (text != null) {
                String lower = text.toLowerCase();
                if (lower.contains("todo") || lower.contains("next") || lower.contains("pending")
                        || lower.contains("follow up") || lower.contains("remaining")) {
                    pending.add(0, truncate(text, 160));
                }
            }
        }
        return pending;
    }

    private List<String> extractKeyFiles(List<ConversationMessage> messages) {
        List<String> files = new ArrayList<>();
        for (ConversationMessage msg : messages) {
            for (ContentBlock block : msg.blocks()) {
                String content = switch (block) {
                    case ContentBlock.TextBlock t -> t.text();
                    case ContentBlock.ToolUseBlock tu -> tu.input();
                    case ContentBlock.ToolResultBlock tr -> tr.output();
                    case ContentBlock.ThinkingBlock th -> th.thinking();
                };
                files.addAll(extractFilePaths(content));
            }
        }
        return files.stream().distinct().sorted().limit(8).toList();
    }

    private String extractFirstText(ConversationMessage msg) {
        for (ContentBlock block : msg.blocks()) {
            if (block instanceof ContentBlock.TextBlock t && !t.text().trim().isEmpty()) {
                return t.text().trim();
            }
        }
        return null;
    }

    private List<String> extractFilePaths(String content) {
        List<String> files = new ArrayList<>();
        if (content == null) return files;
        // 简单启发式：查找包含 / 且有文件扩展名的 token
        for (String token : content.split("[\\s\\\"'()\\[\\]{},;:]")) {
            token = token.trim();
            if (token.contains("/") && hasInterestingExtension(token)) {
                files.add(token);
            }
        }
        return files;
    }

    private boolean hasInterestingExtension(String path) {
        String lower = path.toLowerCase();
        return lower.endsWith(".java") || lower.endsWith(".py") || lower.endsWith(".rs")
                || lower.endsWith(".ts") || lower.endsWith(".tsx") || lower.endsWith(".js")
                || lower.endsWith(".json") || lower.endsWith(".md") || lower.endsWith(".xml")
                || lower.endsWith(".yaml") || lower.endsWith(".yml");
    }

    private String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "…";
    }
}
