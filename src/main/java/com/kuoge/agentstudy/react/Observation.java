package com.kuoge.agentstudy.react;

/**
 * ReAct 中的 Observation（观察结果）。
 *
 * <p>表示工具执行后返回的结果，供 LLM 在下一步推理中使用。
 * 例如：
 * <pre>
 * Observation {
 *   content = "订单 12345 当前状态：已发货，预计 3 天送达",
 *   success = true
 * }
 * </pre>
 *
 * @param content 观察结果的文本内容
 * @param success 是否成功执行（false 表示工具调用失败）
 */
public record Observation(
        String content,
        boolean success
) {
    public static Observation ok(String content) {
        return new Observation(content, true);
    }

    public static Observation error(String message) {
        return new Observation("ERROR: " + message, false);
    }
}
