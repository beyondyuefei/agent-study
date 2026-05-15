package com.kuoge.agentstudy.tutorial.skill;

/**
 * Skill 生命周期状态。
 *
 * <p>参考 claw-code 中 TaskStatus 的设计（Created → Running → Completed/Failed/Stopped）。
 */
public enum SkillStatus {
    /** 草稿态：正在开发中，不可被外部调用 */
    DRAFT,
    /** 活跃态：已发布，可被 Agent 调用 */
    ACTIVE,
    /** 废弃态：不再推荐使用，但已有调用仍可工作 */
    DEPRECATED,
    /** 归档态：完全下线，不可调用 */
    ARCHIVED
}
