package com.kuoge.agentstudy.tutorial.skill;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tutorial 层 Skill 注册表 —— 内存实现。
 *
 * <p>对应 claw-code Rust 实现中的 {@code TaskRegistry}，但管理的是 Skill 而非 Task。
 * 核心职责：
 * <ol>
 *   <li>注册 / 注销 Skill</li>
 *   <li>按 ID / 名称 / 状态 / 域查询 Skill</li>
 *   <li>维护 Skill 的索引（名称索引、域索引）</li>
 * </ol>
 *
 * <p>学习重点：
 * <ul>
 *   <li>注册表是 Skill 治理的入口 —— 所有 Skill 操作都经过这里</li>
 *   <li>使用 ConcurrentHashMap 保证线程安全（生产级应使用 Redis）</li>
 *   <li>索引设计：name → skillId 的映射支持按名查找</li>
 * </ul>
 */
public class SkillRegistry {

    private final Map<String, Skill> skills = new ConcurrentHashMap<>();
    private final Map<String, String> nameIndex = new ConcurrentHashMap<>();

    /**
     * 注册一个新 Skill。
     *
     * @return 注册后的 Skill（skillId 由调用方指定）
     * @throws IllegalArgumentException 如果 skillId 已存在
     */
    public Skill register(Skill skill) {
        String skillId = skill.getSkillId();
        if (skills.containsKey(skillId)) {
            throw new IllegalArgumentException("Skill already registered: " + skillId);
        }
        skills.put(skillId, skill);
        nameIndex.put(skill.getName(), skillId);
        return skill;
    }

    /**
     * 按 ID 获取 Skill。
     */
    public Optional<Skill> get(String skillId) {
        return Optional.ofNullable(skills.get(skillId));
    }

    /**
     * 按名称获取 Skill。
     */
    public Optional<Skill> getByName(String name) {
        String skillId = nameIndex.get(name);
        return skillId != null ? Optional.ofNullable(skills.get(skillId)) : Optional.empty();
    }

    /**
     * 获取所有 Skill（不可修改视图）。
     */
    public Collection<Skill> listAll() {
        return List.copyOf(skills.values());
    }

    /**
     * 按状态筛选。
     */
    public List<Skill> listByStatus(SkillStatus status) {
        return skills.values().stream()
                .filter(s -> s.getStatus() == status)
                .toList();
    }

    /**
     * 按业务域筛选。
     */
    public List<Skill> listByDomain(String domain) {
        return skills.values().stream()
                .filter(s -> domain.equals(s.getDomain()))
                .toList();
    }

    /**
     * 注销 Skill。
     */
    public Optional<Skill> unregister(String skillId) {
        Skill removed = skills.remove(skillId);
        if (removed != null) {
            nameIndex.remove(removed.getName());
        }
        return Optional.ofNullable(removed);
    }

    /**
     * 获取注册表统计信息。
     */
    public RegistryStats stats() {
        int total = skills.size();
        long draft = skills.values().stream().filter(s -> s.getStatus() == SkillStatus.DRAFT).count();
        long active = skills.values().stream().filter(s -> s.getStatus() == SkillStatus.ACTIVE).count();
        long deprecated = skills.values().stream().filter(s -> s.getStatus() == SkillStatus.DEPRECATED).count();
        return new RegistryStats(total, (int) draft, (int) active, (int) deprecated);
    }

    public int size() {
        return skills.size();
    }

    public boolean isEmpty() {
        return skills.isEmpty();
    }

    // ── 统计记录 ──────────────────────────────────────────

    public record RegistryStats(int total, int draft, int active, int deprecated) {
        @Override
        public String toString() {
            return String.format("RegistryStats{total=%d, draft=%d, active=%d, deprecated=%d}",
                    total, draft, active, deprecated);
        }
    }
}
