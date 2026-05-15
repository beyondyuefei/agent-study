package com.kuoge.agentstudy.production.skill.governance;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SkillVersionManagerTest {

    @Test
    void shouldRegisterAndActivateVersion() {
        SkillVersionManager manager = new SkillVersionManager();

        SkillVersion v1 = SkillVersion.builder()
                .versionId("1.0.0")
                .skillId("price-compare")
                .promptTemplate("Compare prices...")
                .runtimeParameters(Map.of("max_results", 5))
                .build();

        manager.registerVersion("price-compare", v1);
        manager.activate("price-compare", "1.0.0");

        SkillVersion active = manager.resolve("price-compare", "user-1");
        assertEquals("1.0.0", active.versionId());
        assertEquals(DeploymentStatus.ACTIVE, active.status());
    }

    @Test
    void shouldSupportGrayscale() {
        SkillVersionManager manager = new SkillVersionManager();

        // 注册 v1 并激活
        manager.registerVersion("skill", SkillVersion.builder()
                .versionId("1.0.0").skillId("skill")
                .promptTemplate("v1").build());
        manager.activate("skill", "1.0.0");

        // 注册 v2 并灰度 50%
        manager.registerVersion("skill", SkillVersion.builder()
                .versionId("2.0.0").skillId("skill")
                .promptTemplate("v2").build());
        manager.grayscale("skill", "2.0.0", 50);

        // 测试灰度分桶：用户 ID hashCode % 100 决定是否在灰度桶
        // 这里我们无法精确预测，但可以通过多次调用来验证概率分布
        int v2Count = 0;
        for (int i = 0; i < 1000; i++) {
            SkillVersion resolved = manager.resolve("skill", "user-" + i);
            if (resolved.versionId().equals("2.0.0")) {
                v2Count++;
            }
        }
        // 50% 灰度，大约 500 个用户应该命中 v2（允许 ±100 的误差）
        assertTrue(v2Count > 400 && v2Count < 600,
                "Expected ~500 v2 users, got " + v2Count);
    }

    @Test
    void shouldRollbackToStableVersion() {
        SkillVersionManager manager = new SkillVersionManager();

        manager.registerVersion("skill", SkillVersion.builder()
                .versionId("1.0.0").skillId("skill").promptTemplate("v1").build());
        manager.activate("skill", "1.0.0");

        manager.registerVersion("skill", SkillVersion.builder()
                .versionId("2.0.0").skillId("skill").promptTemplate("v2").build());
        manager.grayscale("skill", "2.0.0", 100); // 100% 灰度 = 全量切到 v2

        // 回滚到 v1
        manager.rollback("skill");

        SkillVersion active = manager.resolve("skill", "user-1");
        assertEquals("1.0.0", active.versionId());
    }

    @Test
    void shouldRejectUnknownVersion() {
        SkillVersionManager manager = new SkillVersionManager();
        assertThrows(IllegalArgumentException.class, () ->
                manager.activate("skill", "999.0.0")
        );
    }
}
