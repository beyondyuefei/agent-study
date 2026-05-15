package com.kuoge.agentstudy.production.runtime.session;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Agent 会话状态管理器。
 *
 * <p>对应 claw-code Rust 实现：{@code session.rs/Session}
 *
 * <h3>核心职责</h3>
 * <ol>
 *   <li><b>消息存储</b>：按顺序保存对话历史（System → User → Assistant → Tool...）</li>
 *   <li><b>会话元数据</b>：sessionId、创建时间、压缩历史记录</li>
 *   <li><b>完整性保护</b>：确保 ToolUse 后必须有对应的 ToolResult（防止 400 错误）</li>
 *   <li><b>Token 估算</b>：实时估算当前会话的 token 占用</li>
 * </ol>
 *
 * <h3>与教学版的区别</h3>
 * <table>
 *   <tr><th></th><th>教学版 ReActLoop</th><th>AgentSession</th></tr>
 *   <tr><td>上下文存储</td><td>StringBuilder 拼接</td><td>List&lt;ConversationMessage&gt; 结构化</td></tr>
 *   <tr><td>工具调用跟踪</td><td>字符串解析 Action</td><td>ContentBlock.ToolUseBlock 精确提取</td></tr>
 *   <tr><td>会话持久化</td><td>无</td><td>支持快照/恢复（接口预留）</td></tr>
 *   <tr><td>Fork 支持</td><td>无</td><td>支持分支（克隆会话）</td></tr>
 * </table>
 */
@Slf4j
public class AgentSession {

    @Getter
    private final String sessionId;
    @Getter
    private final Instant createdAt;
    @Getter
    private Instant updatedAt;

    /** 会话消息列表（有序，不可外部修改） */
    private final List<ConversationMessage> messages = new ArrayList<>();

    /** 压缩历史：记录每次压缩移除的消息数和摘要 */
    @Getter
    private final List<CompactionRecord> compactionHistory = new ArrayList<>();

    /** 用户 prompt 历史（用于审计） */
    @Getter
    private final List<PromptEntry> promptHistory = new ArrayList<>();

    /** 工作空间根目录（路径安全绑定） */
    @Getter
    private String workspaceRoot;

    public AgentSession() {
        this.sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public AgentSession(String sessionId) {
        this.sessionId = sessionId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * 绑定工作空间根目录（用于路径安全校验）。
     */
    public AgentSession withWorkspaceRoot(String root) {
        this.workspaceRoot = root;
        return this;
    }

    /**
     * 添加消息到会话。
     *
     * @throws IllegalStateException 如果消息会破坏会话完整性（如 orphaned ToolResult）
     */
    public void pushMessage(ConversationMessage message) {
        validateMessageOrder(message);
        messages.add(message);
        updatedAt = Instant.now();
    }

    /**
     * 快速添加用户文本消息。
     */
    public void pushUserText(String text) {
        pushMessage(ConversationMessage.userText(text));
        promptHistory.add(new PromptEntry(Instant.now(), text));
    }

    /**
     * 快速添加系统消息。
     */
    public void pushSystemText(String text) {
        // 系统消息通常只出现在开头，如果已有非系统消息则添加警告
        if (!messages.isEmpty() && messages.get(messages.size() - 1).role() != MessageRole.SYSTEM) {
            log.warn("Adding system message after non-system messages may confuse the model");
        }
        pushMessage(ConversationMessage.systemText(text));
    }

    /**
     * 获取当前所有消息（不可修改视图）。
     */
    public List<ConversationMessage> getMessages() {
        return Collections.unmodifiableList(new ArrayList<>(messages));
    }

    /**
     * 获取消息数量。
     */
    public int messageCount() {
        return messages.size();
    }

    /**
     * 估算当前会话的总 token 数。
     */
    public int estimateTotalTokens() {
        return messages.stream().mapToInt(ConversationMessage::estimateTokens).sum();
    }

    /**
     * 记录一次压缩操作。
     */
    public void recordCompaction(int removedCount, String summary) {
        compactionHistory.add(new CompactionRecord(
                compactionHistory.size() + 1,
                removedCount,
                summary,
                Instant.now()
        ));
        log.info("Session compacted: removed {} messages, summary='{}'", removedCount, summary);
    }

    /**
     * Fork（分支）当前会话：复制所有消息到新会话。
     *
     * <p>用于：并行探索不同解决路径、A/B 测试 Agent 行为。
     */
    public AgentSession fork(String branchName) {
        AgentSession forked = new AgentSession();
        forked.workspaceRoot = this.workspaceRoot;
        forked.messages.addAll(this.messages);
        forked.compactionHistory.addAll(this.compactionHistory);
        log.debug("Session forked: {} -> {} (branch={})", this.sessionId, forked.sessionId, branchName);
        return forked;
    }

    /**
     * 重置会话（清空消息，保留 sessionId 和元数据）。
     */
    public void clear() {
        messages.clear();
        compactionHistory.clear();
        promptHistory.clear();
        updatedAt = Instant.now();
    }

    /**
     * 替换全部消息（用于压缩后恢复会话）。
     */
    public void replaceMessages(List<ConversationMessage> newMessages) {
        messages.clear();
        messages.addAll(newMessages);
        updatedAt = Instant.now();
    }

    // ========== 内部校验 ==========

    private void validateMessageOrder(ConversationMessage message) {
        if (message.role() == MessageRole.TOOL) {
            if (messages.isEmpty()) {
                throw new IllegalStateException("ToolResult cannot be the first message");
            }
            // 提取新消息中的 ToolResult block
            List<ContentBlock.ToolResultBlock> newToolResults = message.blocks().stream()
                    .filter(b -> b instanceof ContentBlock.ToolResultBlock)
                    .map(b -> (ContentBlock.ToolResultBlock) b)
                    .toList();
            if (newToolResults.isEmpty()) return;

            // 检查每条 ToolResult 前面是否有对应的 Assistant(ToolUse)
            for (ContentBlock.ToolResultBlock tr : newToolResults) {
                boolean hasMatchingAssistant = messages.stream().anyMatch(m ->
                        m.role() == MessageRole.ASSISTANT && m.toolUses().stream()
                                .anyMatch(tu -> tu.toolUseId().equals(tr.toolUseId()))
                );
                if (!hasMatchingAssistant) {
                    throw new IllegalStateException(
                            "ToolResult(id=" + tr.toolUseId() + ") has no matching Assistant(ToolUse)"
                    );
                }
            }
        }
    }

    // ========== 内部记录类 ==========

    public record CompactionRecord(int count, int removedMessageCount, String summary, Instant timestamp) {}

    public record PromptEntry(Instant timestamp, String text) {}
}
