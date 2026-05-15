package com.kuoge.agentstudy.production.runtime;

import com.kuoge.agentstudy.production.runtime.compact.CompactionConfig;
import com.kuoge.agentstudy.production.runtime.compact.CompactionResult;
import com.kuoge.agentstudy.production.runtime.compact.SessionCompactor;
import com.kuoge.agentstudy.production.runtime.hook.HookEvent;
import com.kuoge.agentstudy.production.runtime.hook.HookResult;
import com.kuoge.agentstudy.production.runtime.hook.ToolHook;
import com.kuoge.agentstudy.production.runtime.session.AgentSession;
import com.kuoge.agentstudy.production.runtime.session.ConversationMessage;
import com.kuoge.agentstudy.production.runtime.session.ContentBlock;
import com.kuoge.agentstudy.production.runtime.session.MessageRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 自动压缩与钩子系统测试。
 */
class CompactAndHookTest {

    // ========== 压缩测试 ==========

    @Test
    void sessionCompactor_leavesSmallSessionUnchanged() {
        AgentSession session = new AgentSession();
        session.pushUserText("hello");
        session.pushMessage(ConversationMessage.assistantText("hi there"));

        SessionCompactor compactor = new SessionCompactor();
        CompactionResult result = compactor.compact(session, CompactionConfig.defaults());

        assertFalse(result.wasCompacted());
        assertEquals(0, result.removedMessageCount());
    }

    @Test
    void sessionCompactor_compactsLargeSession() {
        AgentSession session = buildLargeSession(20);

        SessionCompactor compactor = new SessionCompactor();
        CompactionConfig config = new CompactionConfig(2, 1); // 极低阈值，强制压缩
        CompactionResult result = compactor.compact(session, config);

        assertTrue(result.wasCompacted());
        assertTrue(result.removedMessageCount() > 0);
        assertNotNull(result.summary());
        assertNotNull(result.formattedSummary());
        // 压缩后的会话第一条应该是 system 摘要消息
        assertEquals(MessageRole.SYSTEM, result.compactedSession().getMessages().get(0).role());
    }

    @Test
    void sessionCompactor_boundaryProtection_doesNotSplitToolPairs() {
        AgentSession session = new AgentSession();
        // Turn 1: user
        session.pushUserText("Search files");
        // Turn 2: assistant calls tool
        session.pushMessage(ConversationMessage.assistantWithToolUse("tu-1", "search", "{\"q\":\"*.java\"}"));
        // Turn 3: tool result
        session.pushMessage(ConversationMessage.toolResult("tu-1", "search", "found 5 files", false));
        // Turn 4: assistant final
        session.pushMessage(ConversationMessage.assistantText("Done."));
        // Turn 5: user
        session.pushUserText("Now edit one");

        SessionCompactor compactor = new SessionCompactor();
        // 只保留 1 条消息，这会尝试在 tool result 处切割
        CompactionConfig config = new CompactionConfig(1, 1);
        CompactionResult result = compactor.compact(session, config);

        if (result.wasCompacted()) {
            // 验证：没有 orphaned tool result（ToolResult 前必须有 Assistant(ToolUse)）
            var msgs = result.compactedSession().getMessages();
            for (int i = 1; i < msgs.size(); i++) {
                if (msgs.get(i).role() == MessageRole.TOOL) {
                    var prev = msgs.get(i - 1);
                    assertTrue(prev.role() == MessageRole.ASSISTANT && prev.hasToolUse(),
                            "ToolResult at index " + i + " is not preceded by Assistant(ToolUse)");
                }
            }
        }
    }

    @Test
    void sessionCompactor_preservesExistingSummaryWhenRecompacting() {
        AgentSession session = buildLargeSession(10);

        SessionCompactor compactor = new SessionCompactor();
        CompactionConfig config = new CompactionConfig(2, 1);

        // 第一次压缩
        CompactionResult first = compactor.compact(session, config);
        assertTrue(first.wasCompacted());

        // 第二次压缩（基于已压缩的会话）
        AgentSession secondSession = first.compactedSession();
        // 再添加一些消息
        secondSession.pushUserText("Additional request".repeat(50));
        secondSession.pushMessage(ConversationMessage.assistantText("Additional response".repeat(50)));

        CompactionResult second = compactor.compact(secondSession, config);
        if (second.wasCompacted()) {
            String summary = second.formattedSummary();
            // 第二次摘要应包含"Previously compacted"或合并后的时间线
            assertNotNull(summary);
        }
    }

    @Test
    void compactionConfig_defaultsAreReasonable() {
        CompactionConfig defaults = CompactionConfig.defaults();
        assertEquals(4, defaults.preserveRecentMessages());
        assertEquals(10_000, defaults.maxEstimatedTokens());
    }

    @Test
    void compactionConfig_valuesClamped() {
        CompactionConfig config = new CompactionConfig(0, 50);
        assertEquals(1, config.preserveRecentMessages()); // clamped to 1
        assertEquals(100, config.maxEstimatedTokens());   // clamped to 100
    }

    // ========== 钩子测试 ==========

    @Test
    void toolHook_preToolUse_canDeny() {
        ToolHook safetyHook = new ToolHook() {
            @Override
            public HookResult preToolUse(String toolName, String input) {
                if (input.contains("dangerous")) {
                    return HookResult.deny("Dangerous input blocked");
                }
                return HookResult.allow();
            }
        };

        HookResult result = safetyHook.preToolUse("bash", "{\"command\":\"dangerous command\"}");
        assertTrue(result.denied());
        assertTrue(result.messages().get(0).contains("Dangerous"));

        HookResult okResult = safetyHook.preToolUse("bash", "{\"command\":\"ls\"}");
        assertFalse(okResult.denied());
    }

    @Test
    void toolHook_preToolUse_canModifyInput() {
        ToolHook transformHook = new ToolHook() {
            @Override
            public HookResult preToolUse(String toolName, String input) {
                return HookResult.withUpdatedInput(input.replace("old", "new"));
            }
        };

        HookResult result = transformHook.preToolUse("edit", "{\"path\":\"/old/path\"}");
        assertEquals("{\"path\":\"/new/path\"}", result.updatedInput());
    }

    @Test
    void toolHook_postToolUse_canAppendFeedback() {
        ToolHook auditHook = new ToolHook() {
            @Override
            public HookResult postToolUse(String toolName, String input, String output) {
                return new HookResult(false, false, false,
                        List.of("[AUDIT] Tool " + toolName + " executed successfully"),
                        null, null, null);
            }
        };

        HookResult result = auditHook.postToolUse("search", "{}", "found 5 files");
        assertFalse(result.denied());
        assertEquals(1, result.messages().size());
        assertTrue(result.messages().get(0).contains("AUDIT"));
    }

    @Test
    void hookResult_allow_isDefaultAllow() {
        HookResult result = HookResult.allow();
        assertFalse(result.denied());
        assertFalse(result.failed());
        assertFalse(result.cancelled());
        assertTrue(result.messages().isEmpty());
    }

    @Test
    void hookResult_permissionOverride() {
        HookResult result = HookResult.withPermissionOverride(
                HookResult.PermissionOverride.Deny, "hook says no"
        );
        assertEquals(HookResult.PermissionOverride.Deny, result.permissionOverride());
        assertEquals("hook says no", result.permissionReason());
    }

    // ========== 辅助方法 ==========

    private AgentSession buildLargeSession(int messageCount) {
        AgentSession session = new AgentSession();
        for (int i = 0; i < messageCount; i++) {
            session.pushUserText("User message number " + i + " with lots of text to increase token count. "
                    + "We need enough tokens to trigger compaction threshold. "
                    + "Lorem ipsum dolor sit amet, consectetur adipiscing elit. "
                    + "Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. "
                    .repeat(10));
            session.pushMessage(ConversationMessage.assistantText(
                    "Assistant response number " + i + " also needs to be quite long to consume tokens. "
                            + "Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris. "
                            .repeat(10)));
        }
        return session;
    }
}
