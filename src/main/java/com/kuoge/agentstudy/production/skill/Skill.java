package com.kuoge.agentstudy.production.skill;

import com.kuoge.agentstudy.production.skill.governance.SkillVersion;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.*;

/**
 * Skill 根实体 —— 生产级实现。
 *
 * <p>对应 claw-code 中 Task 概念，但这里是 Skill 全生命周期的聚合根。
 * 一个 Skill 聚合了 SOP、Runtime、Eval、Governance 四层信息，
 * 是 SkillRegistry 管理的基本单元。
 *
 * <p>设计原则：
 * <ol>
 *   <li>Skill 是不可变的版本化管理对象 —— 修改产生新版本，不覆盖旧版本</li>
 *   <li>Skill 持有的是引用（sopId / runtimeConfigId / governanceId），
 *       而非直接持有 SOP / Runtime / Governance 对象，避免聚合根过大</li>
 *   <li>当前激活版本通过 {@link #currentVersionId} 指定，支持快速回滚</li>
 * </ol>
 */
@Getter
@Builder
public class Skill {

    private final String skillId;
    private String name;
    private String description;
    private String domain;
    private String owner;

    @Builder.Default
    private SkillStatus status = SkillStatus.DRAFT;

    /** 当前激活的版本 ID */
    private String currentVersionId;

    /** SOP 文档 ID */
    private String sopId;

    /** Runtime 配置 ID */
    private String runtimeConfigId;

    /** Governance 配置 ID */
    private String governanceId;

    /** 版本历史（按时间倒序） */
    @Builder.Default
    private List<SkillVersion> versions = new ArrayList<>();

    /** 工具名称列表 */
    @Builder.Default
    private List<String> toolNames = new ArrayList<>();

    @Builder.Default
    private final Instant createdAt = Instant.now();
    @Builder.Default
    private Instant updatedAt = Instant.now();

    // ── 版本管理 ──────────────────────────────────────────

    /**
     * 注册一个新版本。
     */
    public void addVersion(SkillVersion version) {
        this.versions.add(0, version); // 新版本放前面
        this.currentVersionId = version.versionId();
        touch();
    }

    /**
     * 切换当前激活版本（回滚/切换）。
     */
    public void switchVersion(String versionId) {
        boolean exists = versions.stream().anyMatch(v -> v.versionId().equals(versionId));
        if (!exists) {
            throw new IllegalArgumentException("Version not found: " + versionId);
        }
        this.currentVersionId = versionId;
        touch();
    }

    /**
     * 获取当前激活的版本。
     */
    public Optional<SkillVersion> currentVersion() {
        if (currentVersionId == null) return Optional.empty();
        return versions.stream()
                .filter(v -> v.versionId().equals(currentVersionId))
                .findFirst();
    }

    // ── 生命周期 ──────────────────────────────────────────

    public void activate() {
        if (this.status == SkillStatus.ACTIVE) return;
        if (this.status == SkillStatus.ARCHIVED || this.status == SkillStatus.DEPRECATED) {
            throw new IllegalStateException("Cannot activate skill from status: " + this.status);
        }
        this.status = SkillStatus.ACTIVE;
        touch();
    }

    public void grayscale() {
        if (this.status != SkillStatus.DRAFT && this.status != SkillStatus.ACTIVE) {
            throw new IllegalStateException("Cannot grayscale from status: " + this.status);
        }
        this.status = SkillStatus.GRAYSCALE;
        touch();
    }

    public void deprecate() {
        if (this.status != SkillStatus.ACTIVE && this.status != SkillStatus.GRAYSCALE) {
            throw new IllegalStateException("Cannot deprecate from status: " + this.status);
        }
        this.status = SkillStatus.DEPRECATED;
        touch();
    }

    public void archive() {
        this.status = SkillStatus.ARCHIVED;
        touch();
    }

    // ── 工具管理 ──────────────────────────────────────────

    public void addTool(String toolName) {
        if (!toolNames.contains(toolName)) {
            toolNames.add(toolName);
            touch();
        }
    }

    // ── 内部 ──────────────────────────────────────────────

    private void touch() {
        this.updatedAt = Instant.now();
    }

    @Override
    public String toString() {
        return String.format("Skill[%s] '%s' status=%s version=%s tools=%d",
                skillId, name, status, currentVersionId, toolNames.size());
    }
}
