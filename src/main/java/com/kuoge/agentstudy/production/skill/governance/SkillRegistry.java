package com.kuoge.agentstudy.production.skill.governance;

import com.kuoge.agentstudy.production.skill.Skill;
import com.kuoge.agentstudy.production.skill.SkillMetadata;
import com.kuoge.agentstudy.production.skill.SkillStatus;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Skill 注册中心 —— 生产级实现。
 *
 * <p>对应 claw-code 中 TaskRegistry 的设计：
 * <ul>
 *   <li>内存存储（生产环境应替换为 Redis + DB）</li>
 *   <li>支持按 ID / 名称 / 状态 / 域查询</li>
 *   <li>维护 Skill 的元数据索引（轻量级列表）</li>
 *   <li>支持依赖图谱（Skill → 依赖的其他 Skill）</li>
 * </ul>
 */
@Slf4j
public class SkillRegistry {

    private final Map<String, Skill> skills = new ConcurrentHashMap<>();
    private final Map<String, String> nameIndex = new ConcurrentHashMap<>();

    /**
     * 注册 Skill。
     */
    public void register(Skill skill) {
        String skillId = skill.getSkillId();
        if (skills.containsKey(skillId)) {
            throw new IllegalArgumentException("Skill already registered: " + skillId);
        }
        skills.put(skillId, skill);
        nameIndex.put(skill.getName(), skillId);
        log.info("Registered skill: {}", skill);
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
     * 获取所有 Skill。
     */
    public Collection<Skill> listAll() {
        return List.copyOf(skills.values());
    }

    /**
     * 获取轻量级元数据列表。
     */
    public List<SkillMetadata> listMetadata() {
        return skills.values().stream()
                .map(this::toMetadata)
                .collect(Collectors.toList());
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
     * 搜索（名称或描述包含关键词）。
     */
    public List<Skill> search(String keyword) {
        String lower = keyword.toLowerCase();
        return skills.values().stream()
                .filter(s -> {
                    String name = s.getName();
                    String desc = s.getDescription();
                    return (name != null && name.toLowerCase().contains(lower))
                            || (desc != null && desc.toLowerCase().contains(lower));
                })
                .toList();
    }

    /**
     * 注销 Skill。
     */
    public Optional<Skill> unregister(String skillId) {
        Skill removed = skills.remove(skillId);
        if (removed != null) {
            nameIndex.remove(removed.getName());
            log.info("Unregistered skill: {}", skillId);
        }
        return Optional.ofNullable(removed);
    }

    /**
     * 获取注册表统计。
     */
    public RegistryStats stats() {
        int total = skills.size();
        Map<SkillStatus, Long> byStatus = skills.values().stream()
                .collect(Collectors.groupingBy(Skill::getStatus, Collectors.counting()));
        return new RegistryStats(total, byStatus);
    }

    public int size() {
        return skills.size();
    }

    public boolean isEmpty() {
        return skills.isEmpty();
    }

    // ── 内部方法 ──────────────────────────────────────────

    private SkillMetadata toMetadata(Skill skill) {
        var currentVersion = skill.currentVersion();
        return new SkillMetadata(
                skill.getSkillId(),
                skill.getName(),
                skill.getDomain(),
                skill.getOwner(),
                skill.getStatus(),
                currentVersion.map(v -> v.versionId()).orElse(null),
                skill.getToolNames().size(),
                0, // evalCaseCount 需从外部获取
                0.0, // successRate 需从外部获取
                skill.getCreatedAt(),
                skill.getUpdatedAt()
        );
    }

    // ── 统计记录 ──────────────────────────────────────────

    public record RegistryStats(int total, Map<SkillStatus, Long> byStatus) {
        @Override
        public String toString() {
            return String.format("RegistryStats{total=%d, byStatus=%s}", total, byStatus);
        }
    }
}
