package com.kuoge.agentstudy.production.skill.runtime;

import com.kuoge.agentstudy.production.skill.Skill;
import com.kuoge.agentstudy.production.skill.sop.SkillSop;
import com.kuoge.agentstudy.production.tool.Tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Skill 系统提示词构建器 —— 分段拼接。
 *
 * <p>对应 claw-code Rust 实现：{@code prompt.rs/SystemPromptBuilder}
 *
 * <h3>分段顺序（与 Rust 实现保持一致的"基底 → 动态 → 业务"层次）</h3>
 * <ol>
 *   <li><b>Base</b>：来自 {@link SkillRuntimeConfig#getSystemPrompt()} 的基底人设</li>
 *   <li><b>Skill Identity</b>：Skill 名称 + 域 + 当前版本（从 {@link Skill}）</li>
 *   <li><b>SOP</b>：来自 {@link SkillSop}—— 评分维度、平台策略、迭代路线、边界用例</li>
 *   <li><b>Tool Manifest</b>：可用工具清单</li>
 * </ol>
 */
public class SkillSystemPromptBuilder {

    private static final String SECTION_SEP = "\n\n";

    private String basePrompt;
    private Skill skill;
    private SkillSop sop;
    private final List<Tool> tools = new ArrayList<>();

    public SkillSystemPromptBuilder withBase(String basePrompt) {
        this.basePrompt = basePrompt;
        return this;
    }

    public SkillSystemPromptBuilder withSkill(Skill skill) {
        this.skill = skill;
        return this;
    }

    public SkillSystemPromptBuilder withSop(SkillSop sop) {
        this.sop = sop;
        return this;
    }

    public SkillSystemPromptBuilder withTools(Collection<Tool> tools) {
        this.tools.addAll(tools);
        return this;
    }

    public String build() {
        List<String> sections = new ArrayList<>();

        if (basePrompt != null && !basePrompt.isBlank()) {
            sections.add(basePrompt.trim());
        }

        if (skill != null) {
            sections.add(renderSkillIdentity());
        }

        if (sop != null) {
            sections.add(renderSop());
        }

        if (!tools.isEmpty()) {
            sections.add(renderToolManifest());
        }

        return String.join(SECTION_SEP, sections);
    }

    private String renderSkillIdentity() {
        StringBuilder sb = new StringBuilder("# Skill Identity\n");
        sb.append(" - Name: ").append(skill.getName()).append('\n');
        if (skill.getDomain() != null) {
            sb.append(" - Domain: ").append(skill.getDomain()).append('\n');
        }
        if (skill.getCurrentVersionId() != null) {
            sb.append(" - Version: ").append(skill.getCurrentVersionId()).append('\n');
        }
        if (skill.getDescription() != null && !skill.getDescription().isBlank()) {
            sb.append(" - Description: ").append(skill.getDescription()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private String renderSop() {
        StringBuilder sb = new StringBuilder("# Standard Operating Procedure\n");
        if (sop.documentPath() != null && !sop.documentPath().isBlank()) {
            sb.append(" - Document: ").append(sop.documentPath()).append('\n');
        }
        if (!sop.scoringDimensions().isEmpty()) {
            sb.append(" - Scoring dimensions: ")
                    .append(String.join(", ", sop.scoringDimensions()))
                    .append('\n');
        }
        if (!sop.iterationRoadmap().isEmpty()) {
            sb.append(" - Iteration roadmap:\n");
            for (String step : sop.iterationRoadmap()) {
                sb.append("   * ").append(step).append('\n');
            }
        }
        if (!sop.boundaryCases().isEmpty()) {
            sb.append(" - Boundary cases:\n");
            for (String c : sop.boundaryCases()) {
                sb.append("   * ").append(c).append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

    private String renderToolManifest() {
        StringBuilder sb = new StringBuilder("# Tools available\n");
        for (Tool tool : tools) {
            sb.append(" - ").append(tool.name()).append(": ").append(tool.description()).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
