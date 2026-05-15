package com.kuoge.agentstudy.production.runtime.hook;

/**
 * 工具调用钩子接口。
 *
 * <p>对应 claw-code Rust 实现：{@code hooks.rs/HookRunner}
 *
 * <p>允许在工具执行的关键节点插入自定义逻辑：
 * <ul>
 *   <li>{@link #preToolUse} — 工具执行前（可修改输入、阻止执行）</li>
 *   <li>{@link #postToolUse} — 工具成功执行后（可修改输出、追加反馈）</li>
 *   <li>{@link #postToolUseFailure} — 工具执行失败后（错误处理、重试逻辑）</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>
 * // 日志钩子：记录所有工具调用
 * ToolHook auditHook = new ToolHook() {
 *     public HookResult preToolUse(String toolName, String input) {
 *         System.out.println("[AUDIT] About to call " + toolName + " with " + input);
 *         return HookResult.allow();
 *     }
 * };
 *
 * // 输入校验钩子：阻止危险命令
 * ToolHook safetyHook = new ToolHook() {
 *     public HookResult preToolUse(String toolName, String input) {
 *         if (input.contains("rm -rf /")) {
 *             return HookResult.deny("Dangerous command blocked by safety hook");
 *         }
 *         return HookResult.allow();
 *     }
 * };
 * </pre>
 */
public interface ToolHook {

    /**
     * 工具执行前钩子。
     *
     * @param toolName 工具名称
     * @param input    工具输入参数
     * @return 钩子结果（可 deny、修改 input、覆盖权限）
     */
    default HookResult preToolUse(String toolName, String input) {
        return HookResult.allow();
    }

    /**
     * 工具成功执行后钩子。
     *
     * @param toolName 工具名称
     * @param input    工具输入参数
     * @param output   工具输出结果
     * @return 钩子结果（可修改输出、追加反馈消息）
     */
    default HookResult postToolUse(String toolName, String input, String output) {
        return HookResult.allow();
    }

    /**
     * 工具执行失败后钩子。
     *
     * @param toolName  工具名称
     * @param input     工具输入参数
     * @param error     错误信息
     * @return 钩子结果（可修改错误信息、决定是否重试）
     */
    default HookResult postToolUseFailure(String toolName, String input, String error) {
        return HookResult.allow();
    }
}
