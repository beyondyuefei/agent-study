package com.kuoge.agentstudy.production.context;

import lombok.Getter;
import lombok.Setter;

/**
 * 上下文段 —— 9段式压缩的基本单元。
 *
 * <p>每段包含内容 + 元数据，支持独立的加载、压缩、淘汰。
 */
@Getter
public class ContextSegment {

    private final SegmentType type;
    private final int maxTokens;
    private final CompressionStrategy strategy;

    @Setter
    private String content;       // 当前内容
    private int currentTokens;    // 当前内容的估算 token 数
    private boolean loaded;       // 是否已加载（用于 LAZY_LOAD 策略）
    private boolean dirty;        // 内容是否已修改（是否需要重新压缩）

    public ContextSegment(SegmentType type) {
        this(type, type.defaultMaxTokens(), type.defaultStrategy());
    }

    public ContextSegment(SegmentType type, int maxTokens, CompressionStrategy strategy) {
        this.type = type;
        this.maxTokens = maxTokens;
        this.strategy = strategy;
        this.content = "";
        this.currentTokens = 0;
        this.loaded = strategy != CompressionStrategy.LAZY_LOAD;
        this.dirty = false;
    }

    /**
     * 设置内容，自动标记为 dirty 并估算 token 数。
     */
    public void setContent(String content) {
        this.content = content != null ? content : "";
        this.currentTokens = estimateTokens(this.content);
        this.dirty = true;
        this.loaded = true;
    }

    /**
     * 追加内容。
     */
    public void append(String text) {
        this.content = this.content + text;
        this.currentTokens = estimateTokens(this.content);
        this.dirty = true;
    }

    /**
     * 是否超出配额。
     */
    public boolean isOverQuota() {
        return currentTokens > maxTokens;
    }

    /**
     * 剩余可用 token 数。
     */
    public int remainingTokens() {
        return Math.max(0, maxTokens - currentTokens);
    }

    /**
     * 执行压缩（根据策略）。
     *
     * @return 压缩后节省的 token 数
     */
    public int compress() {
        if (!isOverQuota() || strategy == CompressionStrategy.PRESERVE) {
            return 0;
        }

        final int before = currentTokens;

        switch (strategy) {
            case TRUNCATE -> {
                // 简单截断：按比例截断到 maxTokens 的 80%
                int targetChars = (int) (content.length() * (maxTokens * 0.8) / Math.max(1, currentTokens));
                this.content = content.substring(0, Math.min(content.length(), targetChars))
                        + "\n...[truncated]";
            }
            case EVICT -> {
                this.content = "";
            }
            case LAZY_LOAD -> {
                // 未使用的工具定义可以卸载
                if (!dirty) {
                    this.content = "";
                    this.loaded = false;
                }
            }
            case SUMMARIZE -> {
                // 压缩标记：用 "...[summarized: N chars]" 表示
                // 生产级应调用 LLM 生成摘要
                this.content = "[Summary of " + type.name() + ": "
                        + content.length() + " chars → summarized]\n";
            }
            default -> {
                // 不压缩
            }
        }

        this.currentTokens = estimateTokens(this.content);
        this.dirty = false;
        return before - currentTokens;
    }

    @Override
    public String toString() {
        return "%s[priority=%d, tokens=%d/%d, strategy=%s, loaded=%s]".formatted(
                type.name(), type.priority(), currentTokens, maxTokens, strategy, loaded);
    }

    // ========== 工具方法 ==========

    /**
     * 简易 token 估算：中文 ≈ 1 token/字，英文 ≈ 0.25 token/char。
     * 生产级应使用 tiktoken 或模型专用的 tokenizer。
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cnCount = 0, enCount = 0;
        for (char c : text.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') cnCount++;
            else enCount++;
        }
        return cnCount + (int) (enCount * 0.25) + 1;
    }
}
