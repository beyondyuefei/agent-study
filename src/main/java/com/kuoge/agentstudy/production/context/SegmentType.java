package com.kuoge.agentstudy.production.context;

/**
 * 上下文段类型 —— 9段式结构化压缩。
 *
 * <p>设计原理（来自生产级 Context Engineering）：
 * 将上下文窗口划分为 9 个独立段，每段有独立的优先级、token 配额和压缩策略。
 * 当总 token 数超过预算时，按优先级从低到高依次压缩，确保高优先级段不受影响。
 *
 * <p>段优先级排序（从高到低）：
 * <ol>
 *   <li>SYSTEM_IDENTITY — 系统身份不可丢失</li>
 *   <li>USER_PREFERENCE — 用户偏好影响回答风格</li>
 *   <li>CURRENT_GOAL — 当前任务目标必须明确</li>
 *   <li>WORKING_MEMORY — 当前步骤的中间结果</li>
 *   <li>TOOL_DEFINITIONS — 工具定义（可 lazy load）</li>
 *   <li>RETRIEVED_CONTEXT — RAG 检索结果</li>
 *   <li>RECENT_HISTORY — 最近 N 轮原始对话</li>
 *   <li>SCRATCHPAD — 草稿（随时可清空）</li>
 *   <li>SUMMARIZED_HISTORY — 历史摘要（兜底保留）</li>
 * </ol>
 */
public enum SegmentType {

    /** 系统身份定义 —— 最高优先级，绝不压缩 */
    SYSTEM_IDENTITY(1, 500, CompressionStrategy.PRESERVE),

    /** 用户偏好 —— 高优先级，只截断不摘要 */
    USER_PREFERENCE(2, 800, CompressionStrategy.TRUNCATE),

    /** 当前任务目标 —— 必须保留完整 */
    CURRENT_GOAL(3, 300, CompressionStrategy.PRESERVE),

    /** 工作记忆 —— 当前步骤的中间结果，可摘要 */
    WORKING_MEMORY(4, 1000, CompressionStrategy.SUMMARIZE),

    /** 工具定义 —— 支持 lazy load，未使用的工具可剔除 */
    TOOL_DEFINITIONS(5, 1500, CompressionStrategy.LAZY_LOAD),

    /** 检索上下文 —— RAG 结果，按相关性截断 */
    RETRIEVED_CONTEXT(6, 2000, CompressionStrategy.RANKED_TRUNCATE),

    /** 近期历史 —— 最近 N 轮，超出的截断 */
    RECENT_HISTORY(7, 2000, CompressionStrategy.TRUNCATE),

    /** 草稿 —— 最低优先级，随时可清空 */
    SCRATCHPAD(8, 500, CompressionStrategy.EVICT),

    /** 历史摘要 —— 兜底保留，最后才压缩 */
    SUMMARIZED_HISTORY(9, 1000, CompressionStrategy.SUMMARIZE);

    private final int priority;           // 优先级（数字越小越重要）
    private final int defaultMaxTokens;   // 默认最大 token 配额
    private final CompressionStrategy defaultStrategy; // 默认压缩策略

    SegmentType(int priority, int defaultMaxTokens, CompressionStrategy defaultStrategy) {
        this.priority = priority;
        this.defaultMaxTokens = defaultMaxTokens;
        this.defaultStrategy = defaultStrategy;
    }

    public int priority() { return priority; }
    public int defaultMaxTokens() { return defaultMaxTokens; }
    public CompressionStrategy defaultStrategy() { return defaultStrategy; }
}
