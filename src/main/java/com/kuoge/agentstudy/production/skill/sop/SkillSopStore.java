package com.kuoge.agentstudy.production.skill.sop;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SOP 存储接口 + 内存实现。
 *
 * <p>生产级应接入文件系统或数据库，当前提供内存实现便于测试。
 */
public interface SkillSopStore {

    void save(SkillSop sop);

    Optional<SkillSop> findBySkillId(String skillId);

    Optional<SkillSop> findBySopId(String sopId);

    // ── 内存实现 ──────────────────────────────────────────

    class InMemory implements SkillSopStore {
        private final Map<String, SkillSop> bySopId = new ConcurrentHashMap<>();
        private final Map<String, SkillSop> bySkillId = new ConcurrentHashMap<>();

        @Override
        public void save(SkillSop sop) {
            bySopId.put(sop.sopId(), sop);
            bySkillId.put(sop.skillId(), sop);
        }

        @Override
        public Optional<SkillSop> findBySkillId(String skillId) {
            return Optional.ofNullable(bySkillId.get(skillId));
        }

        @Override
        public Optional<SkillSop> findBySopId(String sopId) {
            return Optional.ofNullable(bySopId.get(sopId));
        }
    }
}
