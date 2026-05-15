package com.kuoge.agentstudy.production.runtime.permission;

/**
 * 权限评估的最终结果。
 *
 * <p>对应 claw-code Rust 实现：{@code permissions.rs/PermissionOutcome}
 */
public sealed interface PermissionOutcome {

    /**
     * 允许执行。
     */
    record Allow() implements PermissionOutcome {
        @Override
        public String toString() {
            return "Allow{}";
        }
    }

    /**
     * 拒绝执行（附带原因）。
     */
    record Deny(String reason) implements PermissionOutcome {
        @Override
        public String toString() {
            return "Deny{reason='" + reason + "'}";
        }
    }

    /**
     * 需要用户确认（附带原因）。
     */
    record Ask(String reason) implements PermissionOutcome {
        @Override
        public String toString() {
            return "Ask{reason='" + reason + "'}";
        }
    }

    /**
     * 快捷判断是否为允许。
     */
    default boolean isAllowed() {
        return this instanceof Allow;
    }

    /**
     * 快捷判断是否为拒绝。
     */
    default boolean isDenied() {
        return this instanceof Deny;
    }
}
