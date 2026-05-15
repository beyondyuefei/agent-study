package com.kuoge.agentstudy.production.runtime.permission;

import lombok.Getter;

import java.util.*;

/**
 * 权限策略引擎 —— 评估工具调用是否被授权。
 *
 * <p>对应 claw-code Rust 实现：{@code permissions.rs/PermissionPolicy}
 *
 * <h3>评估流程</h3>
 * <ol>
 *   <li>检查 deny 规则 → 匹配则拒绝</li>
 *   <li>检查 ask 规则 → 匹配则需要确认</li>
 *   <li>检查当前模式是否满足工具所需模式 → 不满足则拒绝或提示</li>
 *   <li>检查 allow 规则 → 匹配则允许</li>
 *   <li>默认行为：根据当前模式决定</li>
 * </ol>
 *
 * <h3>使用示例</h3>
 * <pre>
 * PermissionPolicy policy = new PermissionPolicy(PermissionMode.WorkspaceWrite)
 *     .withToolRequirement("BashTool", PermissionMode.DangerFullAccess)
 *     .withDenyRule("BashTool(rm -rf:*)")
 *     .withAllowRule("BashTool(git:*)")
 *     .withAskRule("BashTool(curl:*)")
 *     .withAskRule("FileWriteTool(/etc/*)");
 *
 * PermissionOutcome outcome = policy.authorize("BashTool", "{\"command\":\"git status\"}");
 * // → Allow（匹配 allow 规则）
 *
 * PermissionOutcome outcome2 = policy.authorize("BashTool", "{\"command\":\"rm -rf /\"}");
 * // → Deny（匹配 deny 规则）
 * </pre>
 */
public class PermissionPolicy {

    @Getter
    private PermissionMode activeMode;
    private final Map<String, PermissionMode> toolRequirements = new HashMap<>();
    private final List<PermissionRule> allowRules = new ArrayList<>();
    private final List<PermissionRule> denyRules = new ArrayList<>();
    private final List<PermissionRule> askRules = new ArrayList<>();

    public PermissionPolicy(PermissionMode activeMode) {
        this.activeMode = Objects.requireNonNull(activeMode);
    }

    /**
     * 设置某工具的最低权限要求。
     */
    public PermissionPolicy withToolRequirement(String toolName, PermissionMode requiredMode) {
        toolRequirements.put(toolName, requiredMode);
        return this;
    }

    public PermissionPolicy withAllowRule(String rule) {
        allowRules.add(PermissionRule.parse(rule));
        return this;
    }

    public PermissionPolicy withDenyRule(String rule) {
        denyRules.add(PermissionRule.parse(rule));
        return this;
    }

    public PermissionPolicy withAskRule(String rule) {
        askRules.add(PermissionRule.parse(rule));
        return this;
    }

    /**
     * 评估工具调用的权限。
     *
     * @param toolName 工具名称
     * @param input    工具输入（JSON 或原始字符串）
     * @return 权限结果
     */
    public PermissionOutcome authorize(String toolName, String input) {
        // 1. deny 规则优先
        for (PermissionRule rule : denyRules) {
            if (rule.matches(toolName, input)) {
                return new PermissionOutcome.Deny(
                        "Permission to use " + toolName + " has been denied by rule '" + rule.raw() + "'"
                );
            }
        }

        // 2. 确定所需权限模式
        PermissionMode requiredMode = toolRequirements.getOrDefault(toolName, PermissionMode.DangerFullAccess);

        // 3. ask 规则强制提示
        for (PermissionRule rule : askRules) {
            if (rule.matches(toolName, input)) {
                return new PermissionOutcome.Ask(
                        "Tool '" + toolName + "' requires approval due to ask rule '" + rule.raw() + "'"
                );
            }
        }

        // 4. allow 规则直接放行
        for (PermissionRule rule : allowRules) {
            if (rule.matches(toolName, input)) {
                return new PermissionOutcome.Allow();
            }
        }

        // 5. 模式层级判断
        if (activeMode == PermissionMode.Allow || activeMode.satisfies(requiredMode)) {
            return new PermissionOutcome.Allow();
        }

        // 6. Prompt 模式或需要升级时
        if (activeMode == PermissionMode.Prompt
                || (activeMode == PermissionMode.WorkspaceWrite && requiredMode == PermissionMode.DangerFullAccess)) {
            return new PermissionOutcome.Ask(
                    "Tool '" + toolName + "' requires approval to escalate from "
                            + activeMode.displayName() + " to " + requiredMode.displayName()
            );
        }

        // 7. 默认拒绝
        return new PermissionOutcome.Deny(
                "Tool '" + toolName + "' requires " + requiredMode.displayName()
                        + " permission; current mode is " + activeMode.displayName()
        );
    }

    /**
     * 批量设置 deny 规则。
     */
    public PermissionPolicy withDenyRules(List<String> rules) {
        for (String rule : rules) {
            denyRules.add(PermissionRule.parse(rule));
        }
        return this;
    }

    /**
     * 批量设置 allow 规则。
     */
    public PermissionPolicy withAllowRules(List<String> rules) {
        for (String rule : rules) {
            allowRules.add(PermissionRule.parse(rule));
        }
        return this;
    }

    /**
     * 批量设置 ask 规则。
     */
    public PermissionPolicy withAskRules(List<String> rules) {
        for (String rule : rules) {
            askRules.add(PermissionRule.parse(rule));
        }
        return this;
    }
}
