package com.kuoge.agentstudy.production.runtime;

import com.kuoge.agentstudy.production.runtime.session.*;
import com.kuoge.agentstudy.production.runtime.usage.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 结构化消息系统测试。
 *
 * <p>覆盖 {@link ConversationMessage}, {@link ContentBlock}, {@link AgentSession}。
 */
class SessionAndMessageTest {

    @Test
    void conversationMessage_builder_and_extractors() {
        ConversationMessage msg = ConversationMessage.builder()
                .role(MessageRole.ASSISTANT)
                .blocks(List.of(
                        new ContentBlock.TextBlock("Let me calculate."),
                        new ContentBlock.ToolUseBlock("tu-1", "calculator", "{\"expr\":\"2+2\"}")
                ))
                .usage(new TokenUsage(20, 6))
                .build();

        assertEquals(MessageRole.ASSISTANT, msg.role());
        assertEquals(2, msg.blocks().size());
        assertTrue(msg.hasToolUse());
        assertEquals(1, msg.toolUses().size());
        assertEquals("calculator", msg.toolUses().get(0).toolName());
        assertTrue(msg.extractText().contains("calculate"));
    }

    @Test
    void contentBlock_estimateTokens() {
        ContentBlock.TextBlock text = new ContentBlock.TextBlock("hello world");
        assertTrue(text.estimateTokens() > 0);

        ContentBlock.ToolUseBlock toolUse = new ContentBlock.ToolUseBlock("id", "search", "{\"q\":\"test\"}");
        assertTrue(toolUse.estimateTokens() > 0);

        ContentBlock.ToolResultBlock toolResult = new ContentBlock.ToolResultBlock("id", "search", "found 5", false);
        assertTrue(toolResult.estimateTokens() > 0);
    }

    @Test
    void agentSession_messageOrder_validation() {
        AgentSession session = new AgentSession();

        // 正常顺序：user → assistant(tool_use) → tool(result)
        session.pushUserText("search files");
        session.pushMessage(ConversationMessage.assistantWithToolUse("tu-1", "search", "{\"q\":\"*.java\"}"));
        session.pushMessage(ConversationMessage.toolResult("tu-1", "search", "found 3 files", false));

        assertEquals(3, session.messageCount());

        // 错误顺序：tool_result 前面不是 assistant(tool_use) → 应该抛出异常
        session.pushMessage(ConversationMessage.assistantText("Done."));
        assertThrows(IllegalStateException.class, () ->
                session.pushMessage(ConversationMessage.toolResult("tu-2", "search", "x", false))
        );
    }

    @Test
    void agentSession_fork_createsIndependentCopy() {
        AgentSession original = new AgentSession();
        original.pushUserText("hello");

        AgentSession forked = original.fork("test-branch");

        assertNotEquals(original.getSessionId(), forked.getSessionId());
        assertEquals(1, forked.messageCount());
        assertEquals(1, original.messageCount());

        // 修改 forked 不影响 original
        forked.pushUserText("world");
        assertEquals(2, forked.messageCount());
        assertEquals(1, original.messageCount());
    }

    @Test
    void agentSession_estimateTotalTokens() {
        AgentSession session = new AgentSession();
        session.pushSystemText("You are a helpful assistant.");
        session.pushUserText("What is 2+2?");
        session.pushMessage(ConversationMessage.assistantText("The answer is 4."));

        int tokens = session.estimateTotalTokens();
        assertTrue(tokens > 0, "Session should have positive token count");
    }

    @Test
    void agentSession_replaceMessages() {
        AgentSession session = new AgentSession();
        session.pushUserText("original");
        session.pushMessage(ConversationMessage.assistantText("response"));

        session.replaceMessages(List.of(
                ConversationMessage.systemText("Summary of previous context"),
                ConversationMessage.userText("new message")
        ));

        assertEquals(2, session.messageCount());
        assertEquals(MessageRole.SYSTEM, session.getMessages().get(0).role());
    }

    @Test
    void messageRole_values() {
        assertEquals(4, MessageRole.values().length);
        assertNotNull(MessageRole.SYSTEM);
        assertNotNull(MessageRole.USER);
        assertNotNull(MessageRole.ASSISTANT);
        assertNotNull(MessageRole.TOOL);
    }

    @Test
    void toolResultBlock_errorFlag() {
        ContentBlock.ToolResultBlock ok = new ContentBlock.ToolResultBlock("id", "bash", "output", false);
        ContentBlock.ToolResultBlock err = new ContentBlock.ToolResultBlock("id", "bash", "error", true);

        assertFalse(ok.isError());
        assertTrue(err.isError());
        assertTrue(err.toString().contains("ERROR"));
    }

    @Test
    void conversationMessage_isError_detectsErrorInBlocks() {
        ConversationMessage msg = ConversationMessage.builder()
                .role(MessageRole.TOOL)
                .blocks(List.of(new ContentBlock.ToolResultBlock("id", "bash", "fail", true)))
                .build();
        assertTrue(msg.isError());
    }
}
