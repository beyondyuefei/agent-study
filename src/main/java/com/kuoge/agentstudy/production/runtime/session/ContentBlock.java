package com.kuoge.agentstudy.production.runtime.session;

/**
 * 消息内容块的抽象接口。
 *
 * <p>对应 claw-code Rust 实现中 {@code session.rs/ContentBlock} 枚举：
 * <ul>
 *   <li>{@link TextBlock} — 普通文本</li>
 *   <li>{@link ThinkingBlock} — AI 思考过程（Claude 的 extended thinking）</li>
 *   <li>{@link ToolUseBlock} — 工具调用请求</li>
 *   <li>{@link ToolResultBlock} — 工具执行结果</li>
 * </ul>
 *
 * <p>使用结构化内容块而非纯字符串，是生产级 Agent 与教学级 Agent 的核心区别：
 * 可以精确识别消息中的工具调用、思考过程、执行结果，从而进行精确的循环控制。
 */
public sealed interface ContentBlock {

    /**
     * 估算该内容块占用的 token 数（粗略估算：1 token ≈ 4 字符）。
     */
    int estimateTokens();

    /**
     * 普通文本块。
     */
    record TextBlock(String text) implements ContentBlock {
        @Override
        public int estimateTokens() {
            return text.length() / 4 + 1;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /**
     * AI 思考过程块（如 Claude 的 thinking 标签内容）。
     */
    record ThinkingBlock(String thinking, String signature) implements ContentBlock {
        @Override
        public int estimateTokens() {
            int tokens = thinking.length() / 4 + 1;
            if (signature != null) {
                tokens += signature.length() / 4 + 1;
            }
            return tokens;
        }

        @Override
        public String toString() {
            return "[thinking: " + thinking.substring(0, Math.min(thinking.length(), 80)) + "...]";
        }
    }

    /**
     * 工具调用请求块。
     *
     * @param toolUseId 工具调用唯一 ID（用于匹配 ToolResult）
     * @param toolName  工具名称
     * @param input     工具输入参数（JSON）
     */
    record ToolUseBlock(String toolUseId, String toolName, String input) implements ContentBlock {
        @Override
        public int estimateTokens() {
            return (toolName.length() + input.length()) / 4 + 1;
        }

        @Override
        public String toString() {
            return "ToolUse{" + toolName + "}(" + input + ")";
        }
    }

    /**
     * 工具执行结果块。
     *
     * @param toolUseId 对应的工具调用 ID
     * @param toolName  工具名称
     * @param output    工具输出内容
     * @param isError   是否执行出错
     */
    record ToolResultBlock(String toolUseId, String toolName, String output, boolean isError)
            implements ContentBlock {
        @Override
        public int estimateTokens() {
            return (toolName.length() + output.length()) / 4 + 1;
        }

        @Override
        public String toString() {
            String prefix = isError ? "ToolResult[ERROR]" : "ToolResult[OK]";
            return prefix + "{" + toolName + "}: " + output.substring(0, Math.min(output.length(), 80));
        }
    }
}
