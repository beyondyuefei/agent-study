package com.kuoge.agentstudy.production.memory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版偏好存储（学习项目用）。
 */
public class InMemoryPreferenceStore implements PreferenceStore {

    // userId -> (category + ":" + key -> UserPreference)
    private final Map<String, Map<String, UserPreference>> data = new ConcurrentHashMap<>();

    @Override
    public void save(UserPreference preference) {
        final String compositeKey = preference.category() + ":" + preference.key();
        data.computeIfAbsent(preference.userId(), k -> new ConcurrentHashMap<>())
            .put(compositeKey, preference);
    }

    @Override
    public Optional<UserPreference> find(String userId, String category, String key) {
        final Map<String, UserPreference> userPrefs = data.get(userId);
        if (userPrefs == null) return Optional.empty();
        return Optional.ofNullable(userPrefs.get(category + ":" + key));
    }

    @Override
    public List<UserPreference> findByUser(String userId) {
        return new ArrayList<>(data.getOrDefault(userId, Map.of()).values());
    }

    @Override
    public List<UserPreference> findByCategory(String userId, String category) {
        return data.getOrDefault(userId, Map.of()).values()
                .stream()
                .filter(p -> p.category().equals(category))
                .toList();
    }

    @Override
    public List<UserPreference> findHighConfidence(String userId, double minConfidence) {
        return data.getOrDefault(userId, Map.of()).values()
                .stream()
                .filter(p -> p.confidence() >= minConfidence)
                .toList();
    }

    @Override
    public void delete(String userId, String category, String key) {
        final Map<String, UserPreference> userPrefs = data.get(userId);
        if (userPrefs != null) {
            userPrefs.remove(category + ":" + key);
        }
    }
}
