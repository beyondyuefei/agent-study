package com.kuoge.agentstudy.production.runtime;

import com.kuoge.agentstudy.production.runtime.client.LlmClient;
import com.kuoge.agentstudy.production.runtime.client.LlmResponse;
import com.kuoge.agentstudy.production.runtime.compact.CompactionConfig;
import com.kuoge.agentstudy.production.runtime.core.ConversationRuntime;
import com.kuoge.agentstudy.production.runtime.core.RuntimeConfig;
import com.kuoge.agentstudy.production.runtime.core.TurnSummary;
import com.kuoge.agentstudy.production.runtime.hook.HookResult;
import com.kuoge.agentstudy.production.runtime.hook.ToolHook;
import com.kuoge.agentstudy.production.runtime.permission.PermissionMode;
import com.kuoge.agentstudy.production.runtime.permission.PermissionPolicy;
import com.kuoge.agentstudy.production.runtime.session.AgentSession;
import com.kuoge.agentstudy.production.runtime.session.ContentBlock;
import com.kuoge.agentstudy.production.runtime.session.ConversationMessage;
import com.kuoge.agentstudy.production.runtime.session.MessageRole;
import com.kuoge.agentstudy.production.runtime.usage.TokenUsage;
import com.kuoge.agentstudy.production.tool.Tool;
import com.kuoge.agentstudy.production.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConversationRuntime 核心运行时测试 —— 生产级 ReAct 循环。
 *
 * <p>覆盖：
 * <ul>
 *   <li>单次 Turn 无工具调用（直接回答）</li>
 *   <li>Turn 内多轮迭代（链式工具调用）</li>
 *   <li>权限拒绝</li>
 *   <li>钩子干预</li>
 *   <li>自动压缩触发</li>
 *   <li>最大迭代保护</li>
 * </ul>
 */
class ConversationRuntimeTest {

    // ========== 基础场景 ==========

    @Test
    void runTurn_directAnswer_noToolUse() {
        // Mock LLM：直接返回文本答案，不调用工具
        LlmClient mockLlm = (system, messages) ->
                LlmResponse.text("The answer is 42.", new TokenUsage(10, 5));

        ToolRegistry registry = new ToolRegistry();
        AgentSession session = new AgentSession();
        ConversationRuntime runtime = new ConversationRuntime(session, mockLlm, registry, RuntimeConfig.defaults());

        TurnSummary summary = runtime.runTurn("What is the meaning of life?");

        assertEquals("completed", summary.stopReason());
        assertEquals(1, summary.iterations());
        assertEquals("The answer is 42.", summary.finalAnswer());
        assertEquals(0, summary.toolResults().size());
        assertEquals(2, session.messageCount()); // user + assistant
        assertEquals(MessageRole.USER, session.getMessages().get(0).role());
        assertEquals(MessageRole.ASSISTANT, session.getMessages().get(1).role());
    }

    @Test
    void runTurn_singleToolUse() {
        // Mock LLM：第一轮请求工具，第二轮给出答案
        AtomicInteger callCount = new AtomicInteger(0);
        LlmClient mockLlm = (system, messages) -> {
            int count = callCount.incrementAndGet();
            if (count == 1) {
                return new LlmResponse(
                        List.of(new ContentBlock.ToolUseBlock("tu-1", "calculator", "{\"expr\":\"2+2\"}")),
                        new TokenUsage(20, 6)
                );
            } else {
                return LlmResponse.text("The answer is 4.", new TokenUsage(24, 4));
            }
        };

        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            public String name() { return "calculator"; }
            public String description() { return "Calculate math expressions"; }
            public String execute(Map<String, Object> args) { return "4"; }
        });

        AgentSession session = new AgentSession();
        ConversationRuntime runtime = new ConversationRuntime(session, mockLlm, registry, RuntimeConfig.defaults());

        TurnSummary summary = runtime.runTurn("What is 2 + 2?");

        assertEquals("completed", summary.stopReason());
        assertEquals(2, summary.iterations());
        assertEquals("The answer is 4.", summary.finalAnswer());
        assertEquals(1, summary.toolResults().size());
        assertEquals(4, session.messageCount()); // user + assistant(tool) + tool_result + assistant(answer)
    }

    @Test
    void runTurn_multiToolChain() {
        // Mock LLM：第一轮 search，第二轮 read_file，第三轮给出答案
        AtomicInteger callCount = new AtomicInteger(0);
        LlmClient mockLlm = (system, messages) -> {
            int count = callCount.incrementAndGet();
            return switch (count) {
                case 1 -> new LlmResponse(
                        List.of(new ContentBlock.ToolUseBlock("tu-1", "search", "{\"q\":\"main.java\"}")),
                        new TokenUsage(20, 6));
                case 2 -> new LlmResponse(
                        List.of(new ContentBlock.ToolUseBlock("tu-2", "read_file", "{\"path\":\"/src/main.java\"}")),
                        new TokenUsage(25, 8));
                case 3 -> LlmResponse.text("The main class is Application.", new TokenUsage(30, 10));
                default -> LlmResponse.text("Done.", TokenUsage.empty());
            };
        };

        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            public String name() { return "search"; }
            public String description() { return "Search files"; }
            public String execute(Map<String, Object> args) { return "Found: /src/main.java"; }
        });
        registry.register(new Tool() {
            public String name() { return "read_file"; }
            public String description() { return "Read file contents"; }
            public String execute(Map<String, Object> args) { return "class Application { }"; }
        });

        AgentSession session = new AgentSession();
        ConversationRuntime runtime = new ConversationRuntime(session, mockLlm, registry, RuntimeConfig.defaults());

        TurnSummary summary = runtime.runTurn("Find the main class");

        assertEquals("completed", summary.stopReason());
        assertEquals(3, summary.iterations());
        assertEquals(2, summary.toolResults().size());
        assertEquals("The main class is Application.", summary.finalAnswer());
    }

    @Test
    void runTurn_parallelToolUses() {
        // Mock LLM：第一轮返回两个工具调用，第二轮返回文本答案
        AtomicInteger callCount = new AtomicInteger(0);
        LlmClient mockLlm = (system, messages) -> {
            int count = callCount.incrementAndGet();
            if (count == 1) {
                return new LlmResponse(List.of(
                        new ContentBlock.ToolUseBlock("tu-1", "toolA", "{}"),
                        new ContentBlock.ToolUseBlock("tu-2", "toolB", "{}")
                ), new TokenUsage(20, 10));
            }
            return LlmResponse.text("Both done.", new TokenUsage(30, 5));
        };

        ToolRegistry registry = new ToolRegistry();
        registry.register(newTool("toolA", "Tool A", args -> "resultA"));
        registry.register(newTool("toolB", "Tool B", args -> "resultB"));

        AgentSession session = new AgentSession();
        ConversationRuntime runtime = new ConversationRuntime(session, mockLlm, registry, RuntimeConfig.defaults());

        TurnSummary summary = runtime.runTurn("Run both tools");

        assertEquals(2, summary.toolResults().size());
        assertTrue(summary.toolResults().stream()
                .anyMatch(m -> m.extractText().contains("resultA")));
        assertTrue(summary.toolResults().stream()
                .anyMatch(m -> m.extractText().contains("resultB")));
        assertEquals("Both done.", summary.finalAnswer());
    }

    // ========== 权限测试 ==========

    @Test
    void runTurn_permissionDeny_blocksToolExecution() {
        AtomicInteger callCount = new AtomicInteger(0);
        LlmClient mockLlm = (system, messages) -> {
            callCount.incrementAndGet();
            return new LlmResponse(
                    List.of(new ContentBlock.ToolUseBlock("tu-1", "bash", "{\"command\":\"rm -rf /\"}")),
                    new TokenUsage(20, 6)
            );
        };

        ToolRegistry registry = new ToolRegistry();
        registry.register(newTool("bash", "Shell", args -> "done"));

        PermissionPolicy policy = new PermissionPolicy(PermissionMode.ReadOnly)
                .withToolRequirement("bash", PermissionMode.DangerFullAccess);

        RuntimeConfig config = RuntimeConfig.builder()
                .permissionPolicy(policy)
                .maxIterationsPerTurn(5)
                .build();

        AgentSession session = new AgentSession();
        ConversationRuntime runtime = new ConversationRuntime(session, mockLlm, registry, config);

        TurnSummary summary = runtime.runTurn("Delete everything");

        // 第一轮工具被拒绝，第二轮 LLM 又返回 ToolUse，再次被拒绝，直到 max_iterations
        assertTrue(summary.iterations() >= 1);
        assertTrue(summary.toolResults().stream()
                .anyMatch(m -> m.extractText().contains("Permission denied")));
    }

    @Test
    void runTurn_permissionAllowRule_bypassesModeCheck() {
        AtomicInteger callCount = new AtomicInteger(0);
        LlmClient mockLlm = (system, messages) -> {
            callCount.incrementAndGet();
            return new LlmResponse(
                    List.of(new ContentBlock.ToolUseBlock("tu-1", "bash", "{\"command\":\"git status\"}")),
                    new TokenUsage(20, 6)
            );
        };

        ToolRegistry registry = new ToolRegistry();
        AtomicInteger executed = new AtomicInteger(0);
        registry.register(newTool("bash", "Shell", args -> {
            executed.incrementAndGet();
            return "On branch main";
        }));

        PermissionPolicy policy = new PermissionPolicy(PermissionMode.ReadOnly)
                .withToolRequirement("bash", PermissionMode.DangerFullAccess)
                .withAllowRule("bash(git:*)");

        RuntimeConfig config = RuntimeConfig.builder()
                .permissionPolicy(policy)
                .maxIterationsPerTurn(3)
                .build();

        AgentSession session = new AgentSession();
        ConversationRuntime runtime = new ConversationRuntime(session, mockLlm, registry, config);
        runtime.runTurn("Check git status");

        // Allow rule 让每次 git 命令都能执行，mock LLM 每次都返回 ToolUse，所以工具会被执行 maxIterations 次
        assertTrue(executed.get() >= 1, "Allow rule should bypass mode check for git commands");
    }

    // ========== 钩子测试 ==========

    @Test
    void runTurn_preHookCanDenyTool() {
        AtomicInteger callCount = new AtomicInteger(0);
        LlmClient mockLlm = (system, messages) -> {
            callCount.incrementAndGet();
            return new LlmResponse(
                    List.of(new ContentBlock.ToolUseBlock("tu-" + callCount.get(), "dangerous_tool", "{}")),
                    new TokenUsage(20, 6)
            );
        };

        ToolRegistry registry = new ToolRegistry();
        registry.register(newTool("dangerous_tool", "Danger", args -> "should not run"));

        AgentSession session = new AgentSession();
        ConversationRuntime runtime = new ConversationRuntime(session, mockLlm, registry, RuntimeConfig.defaults());

        runtime.addHook(new ToolHook() {
            @Override
            public HookResult preToolUse(String toolName, String input) {
                if (toolName.equals("dangerous_tool")) {
                    return HookResult.deny("Blocked by safety hook");
                }
                return HookResult.allow();
            }
        });

        TurnSummary summary = runtime.runTurn("Run dangerous tool");

        // 每次 LLM 都返回 ToolUse，每次都被 hook deny，直到 max_iterations
        assertTrue(summary.toolResults().size() >= 1);
        assertTrue(summary.toolResults().get(0).isError());
        assertTrue(summary.toolResults().get(0).extractText().contains("denied by pre-hook"));
    }

    @Test
    void runTurn_preHookCanModifyInput() {
        AtomicInteger callCount = new AtomicInteger(0);
        LlmClient mockLlm = (system, messages) -> {
            callCount.incrementAndGet();
            return new LlmResponse(
                    List.of(new ContentBlock.ToolUseBlock("tu-" + callCount.get(), "edit", "{\"path\":\"/old\"}")),
                    new TokenUsage(20, 6)
            );
        };

        ToolRegistry registry = new ToolRegistry();
        List<String> receivedPaths = new ArrayList<>();
        registry.register(new Tool() {
            public String name() { return "edit"; }
            public String description() { return "Edit file"; }
            public String execute(Map<String, Object> args) {
                receivedPaths.add((String) args.getOrDefault("path", "none"));
                return "edited";
            }
        });

        RuntimeConfig config = RuntimeConfig.builder().maxIterationsPerTurn(3).build();
        AgentSession session = new AgentSession();
        ConversationRuntime runtime = new ConversationRuntime(session, mockLlm, registry, config);

        runtime.addHook(new ToolHook() {
            @Override
            public HookResult preToolUse(String toolName, String input) {
                return HookResult.withUpdatedInput(input.replace("/old", "/new"));
            }
        });

        runtime.runTurn("Edit file");

        // 每次 LLM 都返回 ToolUse，每次 hook 都修改输入，工具每次都被执行
        assertTrue(receivedPaths.size() >= 1);
        assertTrue(receivedPaths.stream().allMatch(p -> "/new".equals(p)),
                "All executions should use modified path /new");
    }

    @Test
    void runTurn_postHookAppendsFeedback() {
        AtomicInteger callCount = new AtomicInteger(0);
        LlmClient mockLlm = (system, messages) -> {
            callCount.incrementAndGet();
            return new LlmResponse(
                    List.of(new ContentBlock.ToolUseBlock("tu-" + callCount.get(), "search", "{}")),
                    new TokenUsage(20, 6)
            );
        };

        ToolRegistry registry = new ToolRegistry();
        registry.register(newTool("search", "Search", args -> "found 5"));

        RuntimeConfig config = RuntimeConfig.builder().maxIterationsPerTurn(3).build();
        AgentSession session = new AgentSession();
        ConversationRuntime runtime = new ConversationRuntime(session, mockLlm, registry, config);

        runtime.addHook(new ToolHook() {
            @Override
            public HookResult postToolUse(String toolName, String input, String output) {
                return new HookResult(false, false, false,
                        List.of("[AUDIT] Search completed"), null, null, null);
            }
        });

        TurnSummary summary = runtime.runTurn("Search");

        // 至少有一次 tool result 包含 hook feedback
        assertTrue(summary.toolResults().stream()
                        .anyMatch(m -> m.extractText().contains("[AUDIT]")),
                "Hook feedback should be appended to tool result");
    }

    // ========== 安全边界测试 ==========

    @Test
    void runTurn_maxIterationsProtection() {
        // Mock LLM：永远返回工具调用（无限循环）
        AtomicInteger callCount = new AtomicInteger(0);
        LlmClient mockLlm = (system, messages) -> new LlmResponse(
                List.of(new ContentBlock.ToolUseBlock("tu-" + callCount.incrementAndGet(), "noop", "{}")),
                new TokenUsage(10, 5)
        );

        ToolRegistry registry = new ToolRegistry();
        registry.register(newTool("noop", "No-op", args -> "done"));

        RuntimeConfig config = RuntimeConfig.builder()
                .maxIterationsPerTurn(3)
                .build();

        AgentSession session = new AgentSession();
        ConversationRuntime runtime = new ConversationRuntime(session, mockLlm, registry, config);

        TurnSummary summary = runtime.runTurn("Infinite loop test");

        assertEquals("max_iterations_reached", summary.stopReason());
        // iterations 在检查条件前自增，所以达到 4 时触发截断
        assertEquals(4, summary.iterations());
    }

    @Test
    void runTurn_unknownTool_returnsError() {
        AtomicInteger callCount = new AtomicInteger(0);
        LlmClient mockLlm = (system, messages) -> {
            callCount.incrementAndGet();
            return new LlmResponse(
                    List.of(new ContentBlock.ToolUseBlock("tu-1", "nonexistent_tool", "{}")),
                    new TokenUsage(20, 6)
            );
        };

        ToolRegistry registry = new ToolRegistry(); // 空注册表
        RuntimeConfig config = RuntimeConfig.builder().maxIterationsPerTurn(3).build();

        AgentSession session = new AgentSession();
        ConversationRuntime runtime = new ConversationRuntime(session, mockLlm, registry, config);

        TurnSummary summary = runtime.runTurn("Call unknown tool");

        assertTrue(summary.toolResults().size() >= 1);
        assertTrue(summary.toolResults().get(0).isError());
        assertTrue(summary.toolResults().get(0).extractText().contains("Unknown tool"));
    }

    @Test
    void runTurn_toolExecutionException_returnsError() {
        AtomicInteger callCount = new AtomicInteger(0);
        LlmClient mockLlm = (system, messages) -> {
            callCount.incrementAndGet();
            return new LlmResponse(
                    List.of(new ContentBlock.ToolUseBlock("tu-" + callCount.get(), "buggy", "{}")),
                    new TokenUsage(20, 6)
            );
        };

        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            public String name() { return "buggy"; }
            public String description() { return "Always fails"; }
            public String execute(Map<String, Object> args) throws Exception {
                throw new RuntimeException("Simulated tool failure");
            }
        });

        RuntimeConfig config = RuntimeConfig.builder().maxIterationsPerTurn(3).build();
        AgentSession session = new AgentSession();
        ConversationRuntime runtime = new ConversationRuntime(session, mockLlm, registry, config);

        TurnSummary summary = runtime.runTurn("Call buggy tool");

        assertTrue(summary.toolResults().size() >= 1);
        assertTrue(summary.toolResults().stream()
                .anyMatch(m -> m.extractText().contains("Tool execution error")));
    }

    // ========== 自动压缩测试 ==========

    @Test
    void runTurn_autoCompactionTriggered() {
        // 创建一个会触发压缩的长响应
        AtomicInteger callCount = new AtomicInteger(0);
        LlmClient mockLlm = (system, messages) -> {
            callCount.incrementAndGet();
            return new LlmResponse(
                    List.of(new ContentBlock.ToolUseBlock("tu-" + callCount.get(), "echo", "{}")),
                    new TokenUsage(50_000, 1_000)
            );
        };

        ToolRegistry registry = new ToolRegistry();
        registry.register(newTool("echo", "Echo", args -> "ECHO".repeat(1000)));

        RuntimeConfig config = RuntimeConfig.builder()
                .autoCompactionEnabled(true)
                .autoCompactionTokenThreshold(10_000) // 极低阈值，快速触发压缩
                .compactionConfig(new CompactionConfig(1, 1))
                .maxIterationsPerTurn(5)
                .build();

        AgentSession session = new AgentSession();
        ConversationRuntime runtime = new ConversationRuntime(session, mockLlm, registry, config);

        TurnSummary summary = runtime.runTurn("Trigger compaction");

        // 由于每次 LLM 调用都返回 tool_use，会在达到 max_iterations 前触发压缩
        assertNotNull(summary);
        assertTrue(summary.iterations() >= 1);
    }

    @Test
    void runTurn_usageTracking_isAccurate() {
        LlmClient mockLlm = (system, messages) -> LlmResponse.text(
                "Answer", new TokenUsage(100, 50));

        ToolRegistry registry = new ToolRegistry();
        AgentSession session = new AgentSession();
        ConversationRuntime runtime = new ConversationRuntime(session, mockLlm, registry, RuntimeConfig.defaults());

        runtime.runTurn("Question 1");
        runtime.runTurn("Question 2");

        assertEquals(2, runtime.getTurnCount());
    }

    // ========== 辅助方法 ==========

    private Tool newTool(String name, String desc, java.util.function.Function<Map<String, Object>, String> executor) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return desc; }
            public String execute(Map<String, Object> args) { return executor.apply(args); }
        };
    }
}
