package com.kuoge.agentstudy.production.runtime.permission;

/**
 * 权限模式层级 —— 从最安全到最开放。
 *
 * <p>对应 claw-code Rust 实现：{@code permissions.rs/PermissionMode}
 *
 * <p>层级关系（可比较）：
 * <pre>
 * ReadOnly < WorkspaceWrite < DangerFullAccess < Prompt < Allow
 * </pre>
 *
 * <p>模式说明：
 * <ul>
 *   <li>{@code ReadOnly} — 只允许读取操作（FileRead、GlobSearch）</li>
 *   <li>{@code WorkspaceWrite} — 允许工作空间内的写操作（FileEdit、GitCommit）</li>
 *   <li>{@code DangerFullAccess} — 允许危险操作（Bash、Shell 执行）</li>
 *   <li>{@code Prompt} — 每次操作前询问用户确认</li>
 *   <li>{@code Allow} — 允许所有操作（不推荐用于生产）</li>
 * </ul>
 */
public enum PermissionMode {
    ReadOnly,
    WorkspaceWrite,
    DangerFullAccess,
    Prompt,
    Allow;

    /**
     * 当前模式是否满足所需模式。
     *
     * <p>例：WorkspaceWrite 满足 ReadOnly 的要求，但不满足 DangerFullAccess 的要求。
     */
    public boolean satisfies(PermissionMode required) {
        return this.ordinal() >= required.ordinal();
    }

    public String displayName() {
        return switch (this) {
            case ReadOnly -> "read-only";
            case WorkspaceWrite -> "workspace-write";
            case DangerFullAccess -> "danger-full-access";
            case Prompt -> "prompt";
            case Allow -> "allow";
        };
    }
}
