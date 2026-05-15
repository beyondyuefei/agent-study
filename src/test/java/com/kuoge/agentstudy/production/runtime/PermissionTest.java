package com.kuoge.agentstudy.production.runtime;

import com.kuoge.agentstudy.production.runtime.permission.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 权限策略引擎测试。
 */
class PermissionTest {

    @Test
    void permissionMode_satisfies_hierarchy() {
        assertTrue(PermissionMode.Allow.satisfies(PermissionMode.ReadOnly));
        assertTrue(PermissionMode.Allow.satisfies(PermissionMode.DangerFullAccess));
        assertTrue(PermissionMode.WorkspaceWrite.satisfies(PermissionMode.ReadOnly));
        assertFalse(PermissionMode.ReadOnly.satisfies(PermissionMode.WorkspaceWrite));
        assertFalse(PermissionMode.WorkspaceWrite.satisfies(PermissionMode.DangerFullAccess));
    }

    @Test
    void permissionPolicy_allowsWhenModeMeetsRequirement() {
        PermissionPolicy policy = new PermissionPolicy(PermissionMode.WorkspaceWrite)
                .withToolRequirement("read_file", PermissionMode.ReadOnly)
                .withToolRequirement("write_file", PermissionMode.WorkspaceWrite);

        assertTrue(policy.authorize("read_file", "{}").isAllowed());
        assertTrue(policy.authorize("write_file", "{}").isAllowed());
    }

    @Test
    void permissionPolicy_deniesReadOnlyEscalation() {
        PermissionPolicy policy = new PermissionPolicy(PermissionMode.ReadOnly)
                .withToolRequirement("bash", PermissionMode.DangerFullAccess);

        PermissionOutcome outcome = policy.authorize("bash", "{\"command\":\"ls\"}");
        assertTrue(outcome.isDenied());
        assertTrue(outcome.toString().contains("danger-full-access"));
    }

    @Test
    void permissionPolicy_promptsForWorkspaceToDangerEscalation() {
        PermissionPolicy policy = new PermissionPolicy(PermissionMode.WorkspaceWrite)
                .withToolRequirement("bash", PermissionMode.DangerFullAccess);

        PermissionOutcome outcome = policy.authorize("bash", "{\"command\":\"echo hi\"}");
        assertTrue(outcome instanceof PermissionOutcome.Ask);
        assertTrue(outcome.toString().contains("escalate"));
    }

    @Test
    void permissionPolicy_denyRuleShortCircuits() {
        PermissionPolicy policy = new PermissionPolicy(PermissionMode.Allow)
                .withDenyRule("bash(rm -rf:*)")
                .withAllowRule("bash(git:*)")
                .withToolRequirement("bash", PermissionMode.DangerFullAccess);

        // deny 规则优先
        PermissionOutcome rmOutcome = policy.authorize("bash", "{\"command\":\"rm -rf /tmp\"}");
        assertTrue(rmOutcome.isDenied());
        assertTrue(rmOutcome.toString().contains("denied by rule"));

        // allow 规则放行
        PermissionOutcome gitOutcome = policy.authorize("bash", "{\"command\":\"git status\"}");
        assertTrue(gitOutcome.isAllowed());
    }

    @Test
    void permissionPolicy_askRuleForcesPromptEvenWhenModeAllows() {
        PermissionPolicy policy = new PermissionPolicy(PermissionMode.Allow)
                .withAskRule("bash(curl:*)")
                .withToolRequirement("bash", PermissionMode.DangerFullAccess);

        PermissionOutcome outcome = policy.authorize("bash", "{\"command\":\"curl https://example.com\"}");
        assertTrue(outcome instanceof PermissionOutcome.Ask);
        assertTrue(outcome.toString().contains("ask rule"));
    }

    @Test
    void permissionRule_parse_exactMatcher() {
        PermissionRule rule = PermissionRule.parse("bash(ls)");
        assertTrue(rule.matches("bash", "{\"command\":\"ls\"}"));
        assertFalse(rule.matches("bash", "{\"command\":\"rm\"}"));
    }

    @Test
    void permissionRule_parse_prefixMatcher() {
        PermissionRule rule = PermissionRule.parse("bash(git:*)");
        assertTrue(rule.matches("bash", "{\"command\":\"git status\"}"));
        assertTrue(rule.matches("bash", "{\"command\":\"git log\"}"));
        assertFalse(rule.matches("bash", "{\"command\":\"ls\"}"));
    }

    @Test
    void permissionRule_parse_anyMatcher() {
        PermissionRule rule = PermissionRule.parse("bash");
        assertTrue(rule.matches("bash", "{\"command\":\"anything\"}"));
        assertFalse(rule.matches("file_read", "{}"));
    }

    @Test
    void permissionPolicy_batchRules() {
        PermissionPolicy policy = new PermissionPolicy(PermissionMode.WorkspaceWrite)
                .withDenyRules(List.of("bash(rm -rf:*)", "bash(dd:*)"))
                .withAllowRules(List.of("bash(git:*)", "bash(ls:*)"));

        assertTrue(policy.authorize("bash", "{\"command\":\"rm -rf /\"}").isDenied());
        assertTrue(policy.authorize("bash", "{\"command\":\"git status\"}").isAllowed());
        assertTrue(policy.authorize("bash", "{\"command\":\"ls -la\"}").isAllowed());
    }

    @Test
    void permissionOutcome_isAllowedAndIsDenied() {
        assertTrue(new PermissionOutcome.Allow().isAllowed());
        assertFalse(new PermissionOutcome.Allow().isDenied());
        assertTrue(new PermissionOutcome.Deny("reason").isDenied());
        assertFalse(new PermissionOutcome.Deny("reason").isAllowed());
    }
}
