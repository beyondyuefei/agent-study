package com.kuoge.agentstudy.production.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 偏好记忆系统 —— 生产级实现。
 *
 * <h3>设计来源与原理</h3>
 * <p>参考 Claude 的 Memory 功能设计 + MemGPT 的记忆分层思想：
 * <ul>
 *   <li><b>Core Memory（核心偏好）</b>：高置信度、稳定的偏好，始终携带在上下文中
 *       （如"用户是中文母语者"、"用户喜欢用 AssertJ"）</li>
 *   <li><b>Archival Memory（归档偏好）</b>：低置信度或旧的偏好，按需检索
 *       （如"用户三个月前提过想学前端"）</li>
 * </ul>
 *
 * <h3>为什么只记偏好，不记事实？</h3>
 * <p>来自生产级教训：
 * <pre>
 * ❌ 错误：记住 "订单服务在第 3 行用了 RestTemplate"
 *    → 两周后代码重构，第 3 行变成了 WebClient → 记忆错误 → 误导 LLM
 *
 * ✅ 正确：记住 "用户偏好使用 WebClient 而非 RestTemplate"
 *    → 这是稳定的偏好，不受代码变动影响
 * </pre>
 *
 * <h3>偏好提取策略</h3>
 * <ol>
 *   <li><b>显式声明</b>：用户直接说"我喜欢用 JUnit 5" → 高置信度</li>
 *   <li><b>行为推断</b>：用户连续 3 次写的测试都用 AssertJ → 中等置信度</li>
 *   <li><b>否定修正</b>：用户说"不，我不喜欢 Mockito" → 更新/删除偏好</li>
 * </ol>
 */
@Slf4j
@RequiredArgsConstructor
public class PreferenceMemory {

    private final PreferenceStore store;

    /** 核心偏好阈值：≥ 此值的偏好始终注入上下文 */
    private static final double CORE_THRESHOLD = 0.8;
    /** 最低有效阈值：＜ 此值的偏好视为噪声，不存储 */
    private static final double MIN_CONFIDENCE = 0.3;
    /** 最大核心偏好数：防止上下文膨胀 */
    private static final int MAX_CORE_PREFERENCES = 20;

    /**
     * 记录一个观察到的偏好。
     *
     * @param userId    用户 ID
     * @param category  偏好分类
     * @param key       偏好键
     * @param value     偏好值
     * @param sourceMsg 来源消息 ID（溯源）
     */
    public void observe(String userId, String category, String key, String value, String sourceMsg) {
        final Optional<UserPreference> existing = store.find(userId, category, key);

        if (existing.isPresent()) {
            final UserPreference old = existing.get();
            if (old.value().equals(value)) {
                // 偏好一致：提升置信度
                final UserPreference updated = new UserPreference(
                        old.id(), userId, category, key, value,
                        Math.min(1.0, old.confidence() + 0.15),
                        old.firstObservedAt(), Instant.now(),
                        old.confirmationCount() + 1,
                        concat(old.sourceMessageIds(), sourceMsg)
                );
                store.save(updated);
                log.debug("Preference reinforced: {}={} (confidence: {})", key, value, updated.confidence());
            } else {
                // 偏好冲突：用户改变了主意
                final UserPreference updated = new UserPreference(
                        old.id(), userId, category, key, value,
                        0.6,  // 新值从 0.6 开始，不算完全确认
                        old.firstObservedAt(), Instant.now(),
                        1,
                        Set.of(sourceMsg)
                );
                store.save(updated);
                log.info("Preference changed: {}: {} → {} (was {})", key, old.value(), value, old.confidence());
            }
        } else {
            // 新偏好
            final UserPreference pref = new UserPreference(
                    UUID.randomUUID().toString(),
                    userId, category, key, value,
                    0.5,  // 首次观察，置信度不高
                    Instant.now(), Instant.now(),
                    1,
                    Set.of(sourceMsg)
            );
            store.save(pref);
            log.debug("New preference observed: {}={}", key, value);
        }
    }

    /**
     * 获取应注入当前上下文的"核心偏好"。
     *
     * <p>策略：
     * <ol>
     *   <li>筛选高置信度偏好</li>
     *   <li>按新鲜度排序（近期确认的优先）</li>
     *   <li>限制数量（防止上下文膨胀）</li>
     * </ol>
     */
    public String buildCorePreferencePrompt(String userId) {
        final Instant now = Instant.now();

        final List<UserPreference> corePrefs = store.findHighConfidence(userId, CORE_THRESHOLD)
                .stream()
                .filter(p -> p.confidence() >= MIN_CONFIDENCE)
                .sorted((a, b) -> {
                    // 排序：高置信度 × 新鲜度
                    double scoreA = a.confidence() * a.freshnessScore(now);
                    double scoreB = b.confidence() * b.freshnessScore(now);
                    return Double.compare(scoreB, scoreA);
                })
                .limit(MAX_CORE_PREFERENCES)
                .toList();

        if (corePrefs.isEmpty()) {
            return "";
        }

        final StringBuilder sb = new StringBuilder();
        sb.append("\n<user_preferences>\n");
        sb.append("Remember these user preferences when responding:\n");
        for (UserPreference p : corePrefs) {
            sb.append("  ").append(p.toPromptText()).append("\n");
        }
        sb.append("</user_preferences>\n");
        return sb.toString();
    }

    /**
     * 检索归档偏好（低置信度或旧的偏好）。
     *
     * <p>用于：用户提到某个话题时，检索是否有相关历史偏好。
     */
    public List<UserPreference> searchArchival(String userId, String query) {
        return store.findByUser(userId)
                .stream()
                .filter(p -> p.confidence() < CORE_THRESHOLD)
                .filter(p -> p.key().toLowerCase().contains(query.toLowerCase())
                        || p.value().toLowerCase().contains(query.toLowerCase()))
                .sorted(Comparator.comparingDouble(UserPreference::confidence).reversed())
                .collect(Collectors.toList());
    }

    private Set<String> concat(Set<String> existing, String newItem) {
        final Set<String> result = new HashSet<>(existing);
        result.add(newItem);
        return Set.copyOf(result);
    }
}
