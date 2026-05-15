package com.kuoge.agentstudy.production.runtime.session;

/**
 * 对话消息的角色类型。
 *
 * <p>对应 Claude API / OpenAI API 的消息角色体系：
 * <ul>
 *   <li>{@code SYSTEM} — 系统指令，控制 AI 行为</li>
 *   <li>{@code USER} — 用户输入</li>
 *   <li>{@code ASSISTANT} — AI 回复（可能包含文本、思考、工具调用）</li>
 *   <li>{@code TOOL} — 工具执行结果</li>
 * </ul>
 *
 * <p>参考 claw-code Rust 实现：{@code session.rs/MessageRole}
 */
public enum MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
}
