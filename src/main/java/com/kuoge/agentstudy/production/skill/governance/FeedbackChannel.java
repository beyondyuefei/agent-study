package com.kuoge.agentstudy.production.skill.governance;

/**
 * 反馈通道。
 */
public enum FeedbackChannel {
    /** 👍/👎 按钮 */
    THUMB,
    /** 运营/测试人员标注 */
    BAD_CASE,
    /** 自动指标（成功率、延迟等） */
    AUTO_METRIC,
    /** 用户评论 */
    COMMENT
}
