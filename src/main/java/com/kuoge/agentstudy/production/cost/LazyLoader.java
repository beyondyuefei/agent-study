package com.kuoge.agentstudy.production.cost;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 懒加载器 —— 按需加载，避免初始化时加载全部内容。
 *
 * <h3>设计原理</h3>
 * <p>在 ReAct Agent 中，工具定义、RAG 知识库等可能非常庞大，
 * 但单次请求通常只用到其中一小部分。懒加载可以：
 * <ul>
 *   <li>减少初始内存占用</li>
 *   <li>减少 token 消耗（未使用的工具不进入上下文）</li>
 *   <li>加快响应速度</li>
 * </ul>
 *
 * <h3>应用场景</h3>
 * <ol>
 *   <li><b>工具定义懒加载</b>：Agent 有 50 个工具，但当前任务只需要 2-3 个，只加载相关的</li>
 *   <li><b>知识库懒加载</b>：RAG 检索结果按需注入，不提前加载全部文档</li>
 *   <li><b>历史对话懒加载</b>：摘要始终加载，详细历史只在需要时加载</li>
 * </ol>
 *
 * <h3>与 Claude Code 的对应</h3>
 * <p>Claude Code 有数百个可用工具（文件操作、终端、搜索等），
 * 但不可能在每次请求时把所有工具定义都塞进上下文。
 * 其内部实现必然包含工具选择 + 懒加载机制。
 */
@Slf4j
@RequiredArgsConstructor
public class LazyLoader<T> {

    private final Map<String, LazyEntry<T>> entries = new ConcurrentHashMap<>();

    /**
     * 注册一个懒加载项。
     *
     * @param key      标识键
     * @param loader   加载函数
     * @param metadata 元数据（用于判断是否相关）
     */
    public void register(String key, Supplier<T> loader, Map<String, Object> metadata) {
        entries.put(key, new LazyEntry<>(loader, metadata, false, null));
    }

    /**
     * 按需加载指定项。
     */
    public T load(String key) {
        LazyEntry<T> entry = entries.get(key);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown lazy key: " + key);
        }
        if (!entry.loaded) {
            log.debug("Lazy loading: {}", key);
            entry.value = entry.loader.get();
            entry.loaded = true;
        }
        return entry.value;
    }

    /**
     * 根据相关性筛选并加载。
     *
     * <p>生产级：用 Embedding 相似度或 LLM 判断相关性。
     * 学习项目：用简单的关键词匹配。
     */
    public Map<String, T> loadRelevant(String query) {
        Map<String, T> result = new ConcurrentHashMap<>();
        for (Map.Entry<String, LazyEntry<T>> e : entries.entrySet()) {
            if (isRelevant(e.getValue(), query)) {
                result.put(e.getKey(), load(e.getKey()));
            }
        }
        return result;
    }

    /**
     * 预加载所有项（用于需要全量的场景）。
     */
    public Map<String, T> loadAll() {
        Map<String, T> result = new ConcurrentHashMap<>();
        for (String key : entries.keySet()) {
            result.put(key, load(key));
        }
        return result;
    }

    /**
     * 获取已加载项的数量。
     */
    public long loadedCount() {
        return entries.values().stream().filter(e -> e.loaded).count();
    }

    private boolean isRelevant(LazyEntry<T> entry, String query) {
        // 简化实现：检查 metadata 中是否包含 query 关键词
        if (entry.metadata == null) return false;
        String lowerQuery = query.toLowerCase();
        return entry.metadata.values().stream()
                .anyMatch(v -> v != null && v.toString().toLowerCase().contains(lowerQuery));
    }

    private static class LazyEntry<T> {
        final Supplier<T> loader;
        final Map<String, Object> metadata;
        boolean loaded;
        T value;

        LazyEntry(Supplier<T> loader, Map<String, Object> metadata, boolean loaded, T value) {
            this.loader = loader;
            this.metadata = metadata;
            this.loaded = loaded;
            this.value = value;
        }
    }
}
