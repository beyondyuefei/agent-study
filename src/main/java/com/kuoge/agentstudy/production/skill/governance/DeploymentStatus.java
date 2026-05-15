package com.kuoge.agentstudy.production.skill.governance;

/**
 * 部署状态 —— 参考 claw-code 中 TaskStatus 的设计。
 */
public enum DeploymentStatus {
    INACTIVE,   // 已注册但未生效
    GRAYSCALE,  // 灰度中
    ACTIVE,     // 全量生效
    DEPRECATED  // 已废弃
}
