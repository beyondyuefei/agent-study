package com.kuoge.agentstudy.production.skill.runtime;

import com.kuoge.agentstudy.production.skill.Skill;
import com.kuoge.agentstudy.production.skill.SkillStatus;
import com.kuoge.agentstudy.production.skill.sop.SkillSop;
import com.kuoge.agentstudy.production.tool.Tool;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SkillBinding 单元测试 —— 验证准入校验与 system prompt 拼装。
 */
class SkillBindingTest {

    private static Skill activeSkill(String skillId, String... toolNames) {
        Skill skill = Skill.builder()
                .skillId(skillId)
                .name("Marketing Campaign Planner")
                .domain("marketing")
                .description("Plan and orchestrate marketing campaigns")
                .build();
        skill.activate();
        for (String t : toolNames) skill.addTool(t);
        return skill;
    }

    private static SkillSop sampleSop(String skillId) {
        return SkillSop.builder()
                .sopId("sop-" + skillId)
                .skillId(skillId)
                .documentPath("docs/skill/marketing.md")
                .scoringDimensions(List.of("creativity", "compliance"))
                .iterationRoadmap(List.of("v1: awareness", "v2: conversion"))
                .boundaryCases(List.of("no refund promises"))
                .version("1.0.0")
                .updatedAt(Instant.now())
                .build();
    }

    private static Tool echoTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "echo " + name; }
            @Override public String execute(Map<String, Object> arguments) { return "ok"; }
        };
    }

    @Test
    void bind_buildsPromptAndScopedRegistry() {
        Skill skill = activeSkill("s-1", "calc");
        SkillSop sop = sampleSop("s-1");
        SkillBinding b = SkillBinding.bind(
                skill, sop, List.of(echoTool("calc")), "You are an assistant.");

        assertEquals(skill, b.skill());
        assertEquals(sop, b.sop());
        assertEquals(1, b.scopedTools().size());
        assertTrue(b.scopedToolRegistry().find("calc").isPresent());

        String p = b.composedSystemPrompt();
        assertTrue(p.contains("You are an assistant."));
        assertTrue(p.contains("Skill Identity"));
        assertTrue(p.contains("Marketing Campaign Planner"));
        assertTrue(p.contains("Standard Operating Procedure"));
        assertTrue(p.contains("creativity"));
        assertTrue(p.contains("Tools available"));
        assertTrue(p.contains("calc"));
    }

    @Test
    void bind_rejectsDraftSkill() {
        Skill draft = Skill.builder().skillId("draft-1").name("X").build(); // DRAFT
        SkillBindingException ex = assertThrows(SkillBindingException.class,
                () -> SkillBinding.bind(draft, null, List.of(), "base"));
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("status must be ACTIVE")));
    }

    @Test
    void bind_rejectsMissingDeclaredTool() {
        Skill skill = activeSkill("s-2", "search", "read_file");
        SkillBindingException ex = assertThrows(SkillBindingException.class,
                () -> SkillBinding.bind(skill, null,
                        List.of(echoTool("search")), "base"));
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("read_file")));
    }

    @Test
    void bind_rejectsDuplicateToolNames() {
        Skill skill = activeSkill("s-3", "calc");
        SkillBindingException ex = assertThrows(SkillBindingException.class,
                () -> SkillBinding.bind(skill, null,
                        List.of(echoTool("calc"), echoTool("calc")), "base"));
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("duplicate")));
    }

    @Test
    void bind_rejectsSopSkillIdMismatch() {
        Skill skill = activeSkill("s-A");
        SkillSop sop = sampleSop("s-B");
        SkillBindingException ex = assertThrows(SkillBindingException.class,
                () -> SkillBinding.bind(skill, sop, List.of(), "base"));
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("sop.skillId mismatch")));
    }

    @Test
    void bind_sopOptional_omitsSopSection() {
        Skill skill = activeSkill("s-4");
        SkillBinding b = SkillBinding.bind(skill, null, List.of(), "base");
        assertFalse(b.composedSystemPrompt().contains("Standard Operating Procedure"));
        assertTrue(b.composedSystemPrompt().contains("Skill Identity"));
    }
}
