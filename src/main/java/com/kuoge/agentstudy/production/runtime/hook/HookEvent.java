package com.kuoge.agentstudy.production.runtime.hook;

/**
 * 钩子事件类型。
 *
 * <p>对应 claw-code Rust 实现：{@code hooks.rs/HookEvent}
 */
public enum HookEvent {
    PreToolUse,
    PostToolUse,
    PostToolUseFailure;

    public String displayName() {
        return switch (this) {
            case PreToolUse -> "PreToolUse";
            case PostToolUse -> "PostToolUse";
            case PostToolUseFailure -> "PostToolUseFailure";
        };
    }
}
