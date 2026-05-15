package com.kuoge.agentstudy.production.runtime.permission;

import java.util.Objects;

/**
 * 单条权限规则。
 *
 * <p>对应 claw-code Rust 实现：{@code permissions.rs/PermissionRule}
 *
 * <p>规则格式（简化版）：
 * <pre>
 * "bash"                    → 匹配工具名 bash，任意输入
 * "bash(git:*)"            → 匹配 bash 工具，输入以 "git" 开头
 * "bash(rm -rf:*)"         → 匹配 bash 工具，输入包含 "rm -rf"
 * "FileEditTool(/etc/*)"   → 匹配 FileEditTool，输入路径以 /etc/ 开头
 * </pre>
 */
public record PermissionRule(
        String raw,
        String toolName,
        Matcher matcher
) {

    public PermissionRule {
        Objects.requireNonNull(toolName, "toolName cannot be null");
        matcher = matcher != null ? matcher : new AnyMatcher();
    }

    /**
     * 解析规则字符串。
     */
    public static PermissionRule parse(String raw) {
        String trimmed = raw.trim();
        int open = findFirstUnescaped(trimmed, '(');
        int close = findLastUnescaped(trimmed, ')');

        if (open >= 0 && close >= 0 && close == trimmed.length() - 1 && open < close) {
            String name = trimmed.substring(0, open).trim();
            String content = trimmed.substring(open + 1, close).trim();
            return new PermissionRule(trimmed, name, parseMatcher(content));
        }

        return new PermissionRule(trimmed, trimmed, new AnyMatcher());
    }

    /**
     * 判断规则是否匹配给定的工具调用。
     */
    public boolean matches(String candidateToolName, String input) {
        if (!toolName.equalsIgnoreCase(candidateToolName)) {
            return false;
        }
        return matcher.matches(input);
    }

    // ========== 匹配器 ==========

    public interface Matcher {
        boolean matches(String input);
    }

    public record AnyMatcher() implements Matcher {
        @Override
        public boolean matches(String input) {
            return true;
        }
    }

    public record ExactMatcher(String expected) implements Matcher {
        @Override
        public boolean matches(String input) {
            return expected.equalsIgnoreCase(extractSubject(input));
        }
    }

    public record PrefixMatcher(String prefix) implements Matcher {
        @Override
        public boolean matches(String input) {
            String subject = extractSubject(input);
            return subject != null && subject.toLowerCase().startsWith(prefix.toLowerCase());
        }
    }

    public record ContainsMatcher(String substring) implements Matcher {
        @Override
        public boolean matches(String input) {
            String subject = extractSubject(input);
            return subject != null && subject.toLowerCase().contains(substring.toLowerCase());
        }
    }

    // ========== 内部方法 ==========

    private static Matcher parseMatcher(String content) {
        String unescaped = unescape(content);
        if (unescaped.isEmpty() || unescaped.equals("*")) {
            return new AnyMatcher();
        }
        if (unescaped.endsWith(":*")) {
            return new PrefixMatcher(unescaped.substring(0, unescaped.length() - 2));
        }
        // 如果包含通配符，转为 contains 匹配
        if (unescaped.contains("*")) {
            return new ContainsMatcher(unescaped.replace("*", ""));
        }
        return new ExactMatcher(unescaped);
    }

    private static String unescape(String value) {
        return value.replace("\\(", "(").replace("\\)", ")").replace("\\\\", "\\");
    }

    private static int findFirstUnescaped(String value, char target) {
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') {
                escaped = !escaped;
            } else if (c == target && !escaped) {
                return i;
            } else {
                escaped = false;
            }
        }
        return -1;
    }

    private static int findLastUnescaped(String value, char target) {
        for (int i = value.length() - 1; i >= 0; i--) {
            char c = value.charAt(i);
            if (c == target) {
                // 检查前面是否有奇数个反斜杠
                int backslashes = 0;
                for (int j = i - 1; j >= 0 && value.charAt(j) == '\\'; j--) {
                    backslashes++;
                }
                if (backslashes % 2 == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * 从工具输入中提取权限校验主题（尝试解析 JSON 的常用字段）。
     */
    private static String extractSubject(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        // 简单启发式：如果输入是 JSON，尝试提取 command/path/file_path 等字段
        // 学习项目简化处理：返回原始输入的前 200 字符
        String trimmed = input.trim();
        if (trimmed.startsWith("{") && trimmed.contains("\"command\"")) {
            int start = trimmed.indexOf("\"command\"") + 10;
            int quoteStart = trimmed.indexOf('"', start);
            if (quoteStart >= 0) {
                int quoteEnd = trimmed.indexOf('"', quoteStart + 1);
                if (quoteEnd > quoteStart) {
                    return trimmed.substring(quoteStart + 1, quoteEnd);
                }
            }
        }
        return trimmed;
    }
}
