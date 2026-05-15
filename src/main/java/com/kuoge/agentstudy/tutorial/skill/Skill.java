package com.kuoge.agentstudy.tutorial.skill;

import java.time.Instant;
import java.util.*;

/**
 * Tutorial 层 Skill 根实体 —— 简化版，用于理解 Skill 的核心概念。
 *
 * <p>对应 claw-code Rust 实现中的 Task 概念，但这里是面向 Skill 全生命周期的
 * 简化抽象。一个 Skill = 名称 + 描述 + SOP + Runtime + Eval + Governance。
 *
 * <p>学习重点：
 * <ol>
 *   <li>Skill 不是 Prompt，而是包含 Prompt + Tool + Eval + 治理的完整单元</li>
 *   <li>Skill 有明确的生命周期状态（Draft → Active → Deprecated）</li>
 *   <li>Skill 可以独立版本管理（与代码版本解耦）</li>
 * </ol>
 */
public class Skill {

    private final String skillId;
    private String name;
    private String description;
    private String domain;
    private SkillStatus status;
    private String currentVersion;

    /** SOP 文档路径（如 docs/skill/xxx/SKILL.md） */
    private String sopPath;

    /** 关联的 Runtime 类全限定名 */
    private String runtimeClass;

    /** 工具名称列表 */
    private final List<String> toolNames = new ArrayList<>();

    /** Eval 用例数量（简化：只记录数量，不持有用例） */
    private int evalCaseCount;

    /** 成功率（0.0-1.0） */
    private double successRate;

    private final Instant createdAt;
    private Instant updatedAt;

    public Skill(String skillId, String name, String description) {
        this.skillId = Objects.requireNonNull(skillId, "skillId cannot be null");
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.description = description != null ? description : "";
        this.status = SkillStatus.DRAFT;
        this.currentVersion = "0.0.1";
        this.successRate = 0.0;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    // ── Getters ───────────────────────────────────────────

    public String getSkillId() { return skillId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getDomain() { return domain; }
    public SkillStatus getStatus() { return status; }
    public String getCurrentVersion() { return currentVersion; }
    public String getSopPath() { return sopPath; }
    public String getRuntimeClass() { return runtimeClass; }
    public List<String> getToolNames() { return List.copyOf(toolNames); }
    public int getEvalCaseCount() { return evalCaseCount; }
    public double getSuccessRate() { return successRate; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    // ── Setters ───────────────────────────────────────────

    public void setName(String name) {
        this.name = Objects.requireNonNull(name);
        touch();
    }

    public void setDescription(String description) {
        this.description = description != null ? description : "";
        touch();
    }

    public void setDomain(String domain) {
        this.domain = domain;
        touch();
    }

    public void setStatus(SkillStatus status) {
        this.status = Objects.requireNonNull(status);
        touch();
    }

    public void setCurrentVersion(String version) {
        this.currentVersion = version;
        touch();
    }

    public void setSopPath(String sopPath) {
        this.sopPath = sopPath;
        touch();
    }

    public void setRuntimeClass(String runtimeClass) {
        this.runtimeClass = runtimeClass;
        touch();
    }

    public void addTool(String toolName) {
        if (!toolNames.contains(toolName)) {
            toolNames.add(toolName);
            touch();
        }
    }

    public void setEvalCaseCount(int count) {
        this.evalCaseCount = Math.max(0, count);
        touch();
    }

    public void setSuccessRate(double rate) {
        this.successRate = Math.max(0.0, Math.min(1.0, rate));
        touch();
    }

    // ── 生命周期方法 ──────────────────────────────────────

    /**
     * 激活 Skill（从 Draft → Active）。
     */
    public void activate() {
        if (this.status != SkillStatus.DRAFT) {
            throw new IllegalStateException(
                    "Cannot activate skill from status: " + this.status);
        }
        this.status = SkillStatus.ACTIVE;
        touch();
    }

    /**
     * 废弃 Skill（从 Active → Deprecated）。
     */
    public void deprecate() {
        if (this.status != SkillStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Cannot deprecate skill from status: " + this.status);
        }
        this.status = SkillStatus.DEPRECATED;
        touch();
    }

    // ── 内部方法 ──────────────────────────────────────────

    private void touch() {
        this.updatedAt = Instant.now();
    }

    @Override
    public String toString() {
        return String.format(
                "Skill[%s] '%s' v%s status=%s tools=%d eval=%d success=%.0f%%",
                skillId, name, currentVersion, status,
                toolNames.size(), evalCaseCount, successRate * 100
        );
    }
}
