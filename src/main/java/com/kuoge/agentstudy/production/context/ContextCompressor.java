package com.kuoge.agentstudy.production.context;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 9段式结构化上下文压缩器 —— 生产级实现。
 *
 * <h3>设计原理</h3>
 * <p>参考 Claude 的 Context Engineering 实践 + MemGPT 的记忆管理思想：
 * <ol>
 *   <li><b>分段管理</b>：将上下文拆分为 9 个独立段，每段有独立的优先级和配额</li>
 *   <li><b>分层压缩</b>：超出预算时，从低优先级段开始压缩，高优先级段优先保留</li>
 *   <li><b>差异压缩</b>：只压缩 dirty 的段，避免重复计算</li>
 *   <li><b>懒加载</b>：TOOL_DEFINITIONS 等段按需加载，未使用的工具不占 token</li>
 * </ol>
 *
 * <h3>9 段结构</h3>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │ 1. SYSTEM_IDENTITY    [PRESERVE]     系统身份（不可压缩）   │
 * │ 2. USER_PREFERENCE    [TRUNCATE]     用户偏好              │
 * │ 3. CURRENT_GOAL       [PRESERVE]     当前目标              │
 * │ 4. WORKING_MEMORY     [SUMMARIZE]    工作记忆              │
 * │ 5. TOOL_DEFINITIONS   [LAZY_LOAD]    工具定义（按需加载）  │
 * │ 6. RETRIEVED_CONTEXT  [RANKED_TRUNC] 检索上下文            │
 * │ 7. RECENT_HISTORY     [TRUNCATE]     近期历史              │
 * │ 8. SCRATCHPAD         [EVICT]        草稿（随时清空）      │
 * │ 9. SUMMARIZED_HISTORY [SUMMARIZE]    历史摘要              │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>压缩流程</h3>
 * <ol>
 *   <li>计算总 token 数</li>
 *   <li>如果未超预算，直接拼接输出</li>
 *   <li>如果超预算，按优先级从低到高依次压缩段</li>
 *   <li>每压缩一段，重新计算总 token</li>
 *   <li>如果全部压缩后仍超预算，触发 emergency 截断（强制截断所有非保留段）</li>
 * </ol>
 */
@Slf4j
public class ContextCompressor {

    /** 总 token 预算（对应模型的上下文窗口） */
    @Getter
    private final int totalBudget;
    /** 安全余量：保留 10% 给模型输出 */
    private static final double SAFETY_MARGIN = 0.9;

    private final Map<SegmentType, ContextSegment> segments = new LinkedHashMap<>();

    public ContextCompressor(int totalBudget) {
        this.totalBudget = totalBudget;
        // 初始化 9 段
        for (SegmentType type : SegmentType.values()) {
            segments.put(type, new ContextSegment(type));
        }
    }

    /**
     * 获取指定段（如果不存在则创建）。
     */
    public ContextSegment segment(SegmentType type) {
        return segments.computeIfAbsent(type, ContextSegment::new);
    }

    /**
     * 构建最终上下文文本（自动压缩）。
     *
     * @return 压缩后的上下文
     */
    public String build() {
        // 第一步：加载所有 LAZY_LOAD 段（只加载实际需要的）
        for (ContextSegment seg : segments.values()) {
            if (seg.getStrategy() == CompressionStrategy.LAZY_LOAD && !seg.isLoaded()) {
                lazyLoad(seg);
            }
        }

        // 第二步：计算当前总 token
        int totalTokens = getCurrentTotalTokens();
        int effectiveBudget = (int) (totalBudget * SAFETY_MARGIN);

        log.debug("Context build: totalTokens={}, effectiveBudget={}", totalTokens, effectiveBudget);

        // 第三步：如果未超预算，直接输出
        if (totalTokens <= effectiveBudget) {
            return concatenateSegments();
        }

        // 第四步：按优先级从低到高压缩
        int deficit = totalTokens - effectiveBudget;
        log.debug("Context over budget by {} tokens, starting compression", deficit);

        // 按优先级排序（数字大的 = 优先级低 = 先压缩）
        List<ContextSegment> sortedByPriority = segments.values().stream()
                .sorted(Comparator.comparingInt(s -> s.getType().priority()))
                .toList();

        for (ContextSegment seg : sortedByPriority) {
            if (deficit <= 0) break;

            int saved = seg.compress();
            deficit -= saved;
            log.debug("Compressed {}: saved {} tokens", seg.getType(), saved);
        }

        // 第五步：Emergency 截断（如果还超预算）
        totalTokens = getCurrentTotalTokens();
        if (totalTokens > effectiveBudget) {
            log.warn("Emergency truncation needed: {} > {}", totalTokens, effectiveBudget);
            emergencyTruncate(effectiveBudget);
        }

        return concatenateSegments();
    }

    /**
     * 获取当前总 token 数。
     */
    public int getCurrentTotalTokens() {
        return segments.values().stream()
                .mapToInt(ContextSegment::getCurrentTokens)
                .sum();
    }

    /**
     * 打印各段状态（用于 debug）。
     */
    public void printStatus() {
        System.out.println("\n========== Context Compressor Status ==========");
        System.out.println("Total Budget: " + totalBudget + " | Effective: " + (int)(totalBudget * SAFETY_MARGIN));
        System.out.println("Current Total: " + getCurrentTotalTokens());
        for (ContextSegment seg : segments.values()) {
            System.out.println("  " + seg);
        }
        System.out.println("================================================\n");
    }

    // ========== 内部方法 ==========

    private String concatenateSegments() {
        final StringBuilder sb = new StringBuilder();
        for (ContextSegment seg : segments.values()) {
            if (seg.getContent() != null && !seg.getContent().isBlank()) {
                sb.append(seg.getContent());
                if (!seg.getContent().endsWith("\n")) {
                    sb.append("\n");
                }
            }
        }
        return sb.toString();
    }

    private void lazyLoad(ContextSegment seg) {
        // 生产级：根据当前任务分析需要哪些工具，只加载相关工具定义
        // 学习项目：简化处理，标记为已加载但内容为空
        seg.setContent("");
        log.debug("Lazy loaded segment: {}", seg.getType());
    }

    private void emergencyTruncate(int targetBudget) {
        // 极端情况：按 token 比例强制截断所有非 PRESERVE 段
        int nonPreserveTokens = segments.values().stream()
                .filter(s -> s.getStrategy() != CompressionStrategy.PRESERVE)
                .mapToInt(ContextSegment::getCurrentTokens)
                .sum();

        if (nonPreserveTokens == 0) return;

        double ratio = (double) (targetBudget - getPreserveTokens()) / nonPreserveTokens;
        ratio = Math.max(0.1, ratio); // 至少保留 10%

        for (ContextSegment seg : segments.values()) {
            if (seg.getStrategy() != CompressionStrategy.PRESERVE && seg.getCurrentTokens() > 0) {
                int targetChars = (int) (seg.getContent().length() * ratio);
                seg.setContent(seg.getContent().substring(0, Math.min(seg.getContent().length(), targetChars))
                        + "\n...[emergency truncated]");
            }
        }
    }

    private int getPreserveTokens() {
        return segments.values().stream()
                .filter(s -> s.getStrategy() == CompressionStrategy.PRESERVE)
                .mapToInt(ContextSegment::getCurrentTokens)
                .sum();
    }
}
