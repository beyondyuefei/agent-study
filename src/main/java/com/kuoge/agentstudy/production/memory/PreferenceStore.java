package com.kuoge.agentstudy.production.memory;

import java.util.List;
import java.util.Optional;

/**
 * 偏好存储接口。
 *
 * <p>生产级实现应支持持久化（数据库/Redis），学习项目用内存实现。
 */
public interface PreferenceStore {

    void save(UserPreference preference);

    Optional<UserPreference> find(String userId, String category, String key);

    List<UserPreference> findByUser(String userId);

    List<UserPreference> findByCategory(String userId, String category);

    List<UserPreference> findHighConfidence(String userId, double minConfidence);

    void delete(String userId, String category, String key);
}
