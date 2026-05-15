package com.kuoge.agentstudy.production.skill.governance;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill 版本管理器 —— 生产级实现。
 *
 * <p>参考 claw-code 中不可变部署原则：
 * <ol>
 *   <li>版本不可变：发布后不允许修改，只能创建新版本</li>
 *   <li>配置与代码分离：Prompt、参数在配置中心管理</li>
 *   <li>灰度发布：新版本先小流量验证，再逐步放量</li>
 *   <li>一键回滚：发现问题可立即切回上一版本</li>
 * </ol>
 */
@Slf4j
public class SkillVersionManager {

    // skillId -> versionId -> SkillVersion
    private final Map<String, Map<String, SkillVersion>> versions = new ConcurrentHashMap<>();

    // skillId -> 当前激活版本
    private final Map<String, SkillVersion> activeConfigs = new ConcurrentHashMap<>();

    /**
     * 注册新版本。
     */
    public void registerVersion(String skillId, SkillVersion version) {
        versions.computeIfAbsent(skillId, k -> new ConcurrentHashMap<>())
                .put(version.versionId(), version);
        log.info("Registered version {} for skill {}", version.versionId(), skillId);
    }

    /**
     * 全量激活某个版本。
     */
    public void activate(String skillId, String versionId) {
        SkillVersion version = findVersion(skillId, versionId);
        SkillVersion activated = new SkillVersion(
                version.versionId(), version.skillId(),
                version.promptTemplate(), version.runtimeParameters(),
                DeploymentStatus.ACTIVE, 0,
                version.createdBy(), version.createdAt(), Instant.now()
        );
        versions.get(skillId).put(versionId, activated);
        activeConfigs.put(skillId, activated);
        log.info("Activated version {} for skill {}", versionId, skillId);
    }

    /**
     * 灰度发布某个版本。
     */
    public void grayscale(String skillId, String versionId, int percent) {
        SkillVersion version = findVersion(skillId, versionId);
        SkillVersion grayscale = new SkillVersion(
                version.versionId(), version.skillId(),
                version.promptTemplate(), version.runtimeParameters(),
                DeploymentStatus.GRAYSCALE, percent,
                version.createdBy(), version.createdAt(), version.activatedAt()
        );
        versions.get(skillId).put(versionId, grayscale);
        activeConfigs.put(skillId, grayscale);
        log.info("Grayscale version {} for skill {} at {}%", versionId, skillId, percent);
    }

    /**
     * 解析用户应使用的版本（考虑灰度分桶）。
     */
    public SkillVersion resolve(String skillId, String userId) {
        SkillVersion active = activeConfigs.get(skillId);
        if (active == null) {
            throw new IllegalStateException("No active version for skill: " + skillId);
        }
        if (active.isGrayscale() && !active.inGrayscaleBucket(userId)) {
            // 用户不在灰度桶，回退到上一个稳定版本
            return findLastStableVersion(skillId);
        }
        return active;
    }

    /**
     * 一键回滚到上一个稳定版本。
     */
    public void rollback(String skillId) {
        SkillVersion stable = findLastStableVersion(skillId);
        activeConfigs.put(skillId, stable);
        log.info("Rolled back skill {} to version {}", skillId, stable.versionId());
    }

    /**
     * 废弃某个版本。
     */
    public void deprecate(String skillId, String versionId) {
        SkillVersion version = findVersion(skillId, versionId);
        SkillVersion deprecated = new SkillVersion(
                version.versionId(), version.skillId(),
                version.promptTemplate(), version.runtimeParameters(),
                DeploymentStatus.DEPRECATED, 0,
                version.createdBy(), version.createdAt(), version.activatedAt()
        );
        versions.get(skillId).put(versionId, deprecated);
        log.info("Deprecated version {} for skill {}", versionId, skillId);
    }

    /**
     * 获取某 Skill 的所有版本。
     */
    public List<SkillVersion> listVersions(String skillId) {
        return List.copyOf(versions.getOrDefault(skillId, Map.of()).values());
    }

    /**
     * 获取当前激活版本。
     */
    public Optional<SkillVersion> getActive(String skillId) {
        return Optional.ofNullable(activeConfigs.get(skillId));
    }

    // ── 内部方法 ──────────────────────────────────────────

    private SkillVersion findVersion(String skillId, String versionId) {
        Map<String, SkillVersion> skillVersions = versions.get(skillId);
        if (skillVersions == null || !skillVersions.containsKey(versionId)) {
            throw new IllegalArgumentException("Version not found: " + skillId + "@" + versionId);
        }
        return skillVersions.get(versionId);
    }

    private SkillVersion findLastStableVersion(String skillId) {
        Map<String, SkillVersion> skillVersions = versions.get(skillId);
        if (skillVersions == null) {
            throw new IllegalStateException("No versions found for skill: " + skillId);
        }
        return skillVersions.values().stream()
                .filter(v -> v.status() == DeploymentStatus.ACTIVE)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No stable version found for skill: " + skillId));
    }
}
