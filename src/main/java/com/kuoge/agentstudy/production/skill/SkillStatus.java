package com.kuoge.agentstudy.production.skill;

/**
 * Skill 生命周期状态 —— 生产级。
 *
 * <p>参考 claw-code TaskStatus 设计，增加 GRAYSCALE（灰度中）状态，
 * 支持 Governance 层的灰度发布流程。
 */
public enum SkillStatus {
    /** 草稿态：开发中 */
    DRAFT,
    /** 灰度态：小流量验证中 */
    GRAYSCALE,
    /** 活跃态：全量发布 */
    ACTIVE,
    /** 废弃态：不再推荐 */
    DEPRECATED,
    /** 归档态：完全下线 */
    ARCHIVED
}
