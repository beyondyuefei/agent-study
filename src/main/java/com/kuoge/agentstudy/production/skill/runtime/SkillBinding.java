package com.kuoge.agentstudy.production.skill.runtime;

import com.kuoge.agentstudy.production.skill.Skill;
import com.kuoge.agentstudy.production.skill.SkillStatus;
import com.kuoge.agentstudy.production.skill.sop.SkillSop;
import com.kuoge.agentstudy.production.tool.Tool;
import com.kuoge.agentstudy.production.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Skill 绑定 —— 不可变数据载体。
 *
 * <p>对应 claw-code Rust 实现：{@code task_packet.rs/ValidatedPacket}
 *  —— 一旦 {@link #bind} 通过，就是一个"已校验、可被 Agent 直接使用"的对象。
 *
 * <h3>设计定位</h3>
 * <p>SkillBinding <b>不是入口</b>，而是 Agent 在某一次执行中把
 * "Skill 元数据 + SOP + 工具子集 + 拼好的 system prompt"打包到一起的临时上下文。
 *
 * <ul>
 *   <li>不持有 Session（归 Agent 管）</li>
 *   <li>不持有 LlmClient（归 Agent 管）</li>
 *   <li>不持有状态机（一次绑定，用完即弃）</li>
 *   <li>只负责"准入校验 + 拼 system prompt + 暴露工具子集"</li>
 * </ul>
 *
 * @param skill                绑定的 Skill 元数据
 * @param sop                  绑定的 SOP，可为 null
 * @param scopedTools          本次开放的工具子集（已注册到 scopedToolRegistry）
 * @param scopedToolRegistry   仅包含本次开放工具的 ToolRegistry，可直接交给 ConversationRuntime
 * @param composedSystemPrompt 已拼好的完整 system prompt（base + identity + SOP + tools）
 */
public record SkillBinding(
        Skill skill,
        SkillSop sop,
        List<Tool> scopedTools,
        ToolRegistry scopedToolRegistry,
        String composedSystemPrompt
) {

    public SkillBinding {
        Objects.requireNonNull(skill, "skill must not be null");
        Objects.requireNonNull(scopedTools, "scopedTools must not be null");
        Objects.requireNonNull(scopedToolRegistry, "scopedToolRegistry must not be null");
        Objects.requireNonNull(composedSystemPrompt, "composedSystemPrompt must not be null");
        scopedTools = List.copyOf(scopedTools);
    }

    /**
     * 准入校验并构造 SkillBinding。
     *
     * <p>校验规则（仿 claw-code {@code validate_packet}）：
     * <ol>
     *   <li>skill 状态必须为 ACTIVE 或 GRAYSCALE</li>
     *   <li>skill.toolNames 中声明的工具必须全部存在于 tools 中</li>
     *   <li>tools 不允许重名</li>
     *   <li>sop（若非 null）skillId 必须与 skill 匹配</li>
     * </ol>
     *
     * @param skill         已注册的 Skill
     * @param sop           关联的 SOP，可为 null
     * @param tools         本次为该 Skill 开放的工具实例列表
     * @param basePrompt    Agent 提供的基底 system prompt
     * @return 已校验的 SkillBinding
     * @throws SkillBindingException 校验失败时抛出
     */
    public static SkillBinding bind(Skill skill, SkillSop sop, List<Tool> tools, String basePrompt) {
        List<String> errors = new ArrayList<>();

        if (skill == null) {
            errors.add("skill must not be null");
        } else {
            if (skill.getStatus() != SkillStatus.ACTIVE
                    && skill.getStatus() != SkillStatus.GRAYSCALE) {
                errors.add("skill.status must be ACTIVE or GRAYSCALE, got=" + skill.getStatus());
            }
        }

        if (tools == null) {
            errors.add("tools must not be null (use empty list if no tools)");
        } else {
            long distinct = tools.stream().map(Tool::name).distinct().count();
            if (distinct != tools.size()) {
                errors.add("tools contain duplicate names");
            }
            if (skill != null) {
                List<String> provided = tools.stream().map(Tool::name).toList();
                for (String declared : skill.getToolNames()) {
                    if (!provided.contains(declared)) {
                        errors.add("declared tool not provided: " + declared);
                    }
                }
            }
        }

        if (sop != null && skill != null) {
            if (!skill.getSkillId().equals(sop.skillId())) {
                errors.add("sop.skillId mismatch: skill=" + skill.getSkillId()
                        + " sop=" + sop.skillId());
            }
        }

        if (!errors.isEmpty()) {
            throw new SkillBindingException(errors);
        }

        ToolRegistry registry = new ToolRegistry();
        for (Tool tool : tools) {
            registry.register(tool);
        }

        String prompt = new SkillSystemPromptBuilder()
                .withBase(basePrompt)
                .withSkill(skill)
                .withSop(sop)
                .withTools(tools)
                .build();

        return new SkillBinding(skill, sop, tools, registry, prompt);
    }
}
