package com.kuoge.agentstudy.production.context;

/**
 * 上下文段的压缩策略。
 */
public enum CompressionStrategy {

    /** 保留：不压缩，超出预算时优先压缩其他段 */
    PRESERVE,

    /** 截断：从尾部截断，保留开头 */
    TRUNCATE,

    /** 按相关性截断：保留高分内容，截断低分内容 */
    RANKED_TRUNCATE,

    /** 摘要：用 LLM 或规则生成摘要，替换原始内容 */
    SUMMARIZE,

    /** 延迟加载：按需加载，未使用的段初始为空 */
    LAZY_LOAD,

    /** 淘汰：直接清空，最激进的压缩 */
    EVICT
}
