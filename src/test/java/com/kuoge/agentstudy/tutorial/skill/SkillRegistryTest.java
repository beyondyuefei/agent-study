package com.kuoge.agentstudy.tutorial.skill;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SkillRegistryTest {

    @Test
    void shouldRegisterAndRetrieveSkill() {
        SkillRegistry registry = new SkillRegistry();
        Skill skill = new Skill("price-compare", "商品比价", "自动比较多个平台价格");
        skill.setDomain("ECOMMERCE");

        Skill registered = registry.register(skill);
        assertEquals("price-compare", registered.getSkillId());

        Optional<Skill> found = registry.get("price-compare");
        assertTrue(found.isPresent());
        assertEquals("商品比价", found.get().getName());
    }

    @Test
    void shouldRejectDuplicateRegistration() {
        SkillRegistry registry = new SkillRegistry();
        registry.register(new Skill("s1", "Skill 1", ""));

        assertThrows(IllegalArgumentException.class, () ->
                registry.register(new Skill("s1", "Skill 1 Dup", ""))
        );
    }

    @Test
    void shouldListByStatus() {
        SkillRegistry registry = new SkillRegistry();
        Skill s1 = new Skill("s1", "Active Skill", "");
        s1.activate();
        Skill s2 = new Skill("s2", "Draft Skill", "");

        registry.register(s1);
        registry.register(s2);

        List<Skill> active = registry.listByStatus(SkillStatus.ACTIVE);
        assertEquals(1, active.size());
        assertEquals("s1", active.get(0).getSkillId());
    }

    @Test
    void shouldGenerateStats() {
        SkillRegistry registry = new SkillRegistry();
        registry.register(new Skill("s1", "A", ""));
        Skill s2 = new Skill("s2", "B", "");
        s2.activate();
        registry.register(s2);

        SkillRegistry.RegistryStats stats = registry.stats();
        assertEquals(2, stats.total());
        assertEquals(1, stats.active());
        assertEquals(1, stats.draft());
    }
}
