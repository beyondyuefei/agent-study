package com.kuoge.agentstudy.production.runtime.hook;

import lombok.Builder;

import java.util.Collections;
import java.util.List;

/**
 * 钩子执行结果。
 *
 * <p>对应 claw-code Rust 实现：{@code hooks.rs/HookRunResult}
 *
 * <p>钩子可以在工具执行前后插入自定义逻辑：
 * <ul>
 *   <li><b>修改输入</b>：通过 {@code updatedInput} 改变工具参数</li>
 *   <li><b>权限覆盖</b>：通过 {@code permissionOverride} 改变权限决策</li>
 *   <li><b>附加消息</b>：通过 {@code messages} 向 LLM 提供额外上下文</li>
 *   <li><b>阻止执行</b>：设置 {@code denied=true} 阻止工具执行</li>
 * </ul>
 */
@Builder
public record HookResult(
        boolean denied,
        boolean failed,
        boolean cancelled,
        List<String> messages,
        PermissionOverride permissionOverride,
        String permissionReason,
        String updatedInput
) {

    public HookResult {
        messages = messages != null ? List.copyOf(messages) : List.of();
    }

    public static HookResult allow() {
        return new HookResult(false, false, false, List.of(), null, null, null);
    }

    public static HookResult deny(String reason) {
        return new HookResult(true, false, false, List.of(reason), null, null, null);
    }

    public static HookResult failed(String reason) {
        return new HookResult(false, true, false, List.of(reason), null, null, null);
    }

    public static HookResult cancelled(String reason) {
        return new HookResult(false, false, true, List.of(reason), null, null, null);
    }

    public static HookResult withUpdatedInput(String updatedInput) {
        return new HookResult(false, false, false, List.of(), null, null, updatedInput);
    }

    public static HookResult withPermissionOverride(PermissionOverride override, String reason) {
        return new HookResult(false, false, false, List.of(), override, reason, null);
    }

    /**
     * 权限覆盖决策。
     */
    public enum PermissionOverride {
        Allow,
        Deny,
        Ask
    }

    @Override
    public String toString() {
        if (cancelled) return "HookResult{cancelled}";
        if (failed) return "HookResult{failed}";
        if (denied) return "HookResult{denied, messages=" + messages + "}";
        return "HookResult{allow, messages=" + messages.size()
                + ", override=" + permissionOverride
                + ", updatedInput=" + (updatedInput != null ? "yes" : "no") + "}";
    }
}
