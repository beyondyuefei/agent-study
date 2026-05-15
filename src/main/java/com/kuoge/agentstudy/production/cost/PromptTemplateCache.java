package com.kuoge.agentstudy.production.cost;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt 模板缓存 —— 减少重复渲染开销。
 *
 * <h3>设计原理（生产级成本优化）</h3>
 * <p>在 Agent 系统中，System Prompt 和工具定义通常是静态的，
 * 但每次请求都重新渲染会导致：
 * <ul>
 *   <li>不必要的 CPU 开销（字符串拼接）</li>
 *   <li>重复的 Token 计算</li>
 *   <li>无法利用 LLM 提供商的 Prompt Caching（如 Anthropic 的 cache_control）</li>
 * </ul>
 *
 * <p>缓存策略：
 * <ol>
 *   <li><b>模板级缓存</b>：System Prompt、工具定义等静态内容按模板 ID 缓存</li>
 *   <li><b>版本感知</b>：模板修改后自动失效旧缓存</li>
 *   <li><b>增量渲染</b>：只渲染动态部分（用户输入、工作记忆），静态部分从缓存取</li>
 * </ol>
 *
 * <h3>与 Claude Code 的对应</h3>
 * <p>Claude Code 的 system prompt 和工具定义在会话期间基本不变，
 * 其内部实现必然包含某种形式的 prompt 缓存机制，避免每次请求都重新编码静态内容。
 */
public class PromptTemplateCache {

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * 缓存一个渲染后的模板。
     *
     * @param templateId 模板唯一标识（如 "system_prompt:v1.2"）
     * @param rendered   渲染后的文本
     * @param tokens     预计算的 token 数
     */
    public void put(String templateId, String rendered, int tokens) {
        cache.put(templateId, new CacheEntry(rendered, tokens, System.currentTimeMillis()));
    }

    /**
     * 获取缓存的模板。
     *
     * @param templateId 模板 ID
     * @return 缓存内容（若不存在返回 null）
     */
    public CacheEntry get(String templateId) {
        return cache.get(templateId);
    }

    /**
     * 检查是否存在有效缓存。
     */
    public boolean has(String templateId) {
        return cache.containsKey(templateId);
    }

    /**
     * 使指定模板失效（模板更新时调用）。
     */
    public void invalidate(String templateId) {
        cache.remove(templateId);
    }

    /**
     * 清空所有缓存。
     */
    public void clear() {
        cache.clear();
    }

    /**
     * 获取缓存统计。
     */
    public String stats() {
        return "PromptTemplateCache[size=%d]".formatted(cache.size());
    }

    public record CacheEntry(String content, int tokens, long cachedAt) {}
}
