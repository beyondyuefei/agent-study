package com.kuoge.agentstudy.production.skill.governance;

import com.kuoge.agentstudy.production.skill.Skill;
import com.kuoge.agentstudy.production.skill.SkillStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SkillRegistryTest {

    @Test
    void shouldRegisterAndQuerySkill() {
        SkillRegistry registry = new SkillRegistry();
        Skill skill = Skill.builder()
                .skillId("price-compare")
                .name("商品比价")
                .description("自动比较多个平台价格")
                .domain("ECOMMERCE")
                .build();

        registry.register(skill);

        assertTrue(registry.get("price-compare").isPresent());
        assertEquals("商品比价", registry.getByName("商品比价").get().getName());
        assertEquals(1, registry.size());
    }

    @Test
    void shouldSupportLifecycleTransitions() {
        Skill skill = Skill.builder()
                .skillId("s1")
                .name("Test")
                .build();

        assertEquals(SkillStatus.DRAFT, skill.getStatus());

        skill.activate();
        assertEquals(SkillStatus.ACTIVE, skill.getStatus());

        skill.grayscale();
        assertEquals(SkillStatus.GRAYSCALE, skill.getStatus());

        skill.deprecate();
        assertEquals(SkillStatus.DEPRECATED, skill.getStatus());
    }

    @Test
    void shouldSearchSkills() {
        SkillRegistry registry = new SkillRegistry();
        Skill s1 = Skill.builder().skillId("s1").name("Price Compare").description("比价工具").build();
        Skill s2 = Skill.builder().skillId("s2").name("Content Writer").description("文案生成器").build();
        registry.register(s1);
        registry.register(s2);

        // 从 registry 中重新获取，验证 description 没有丢失
        Skill fetched1 = registry.get("s1").orElseThrow();
        Skill fetched2 = registry.get("s2").orElseThrow();
        assertEquals("比价工具", fetched1.getDescription(), "s1 description mismatch");
        assertEquals("文案生成器", fetched2.getDescription(), "s2 description mismatch");

        assertEquals(1, registry.search("Price").size());
        assertEquals(1, registry.search("比价").size());
        // s1 description="比价工具", s2 description="文案生成器"
        assertEquals(1, registry.search("具").size());
        assertEquals(1, registry.search("器").size());
        // 搜索 "e" 在两个 name 中都存在，应该返回 2
        assertEquals(2, registry.search("e").size());
    }
}
