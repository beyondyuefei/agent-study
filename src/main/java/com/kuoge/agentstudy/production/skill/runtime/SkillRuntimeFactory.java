package com.kuoge.agentstudy.production.skill.runtime;

import com.kuoge.agentstudy.production.tool.ToolRegistry;

import java.util.UUID;

/**
 * Skill Runtime 工厂 —— 负责根据配置创建 SkillRuntime 实例。
 *
 * <p>参考 claw-code 中 worker_boot.rs 的启动逻辑：
 * 从配置构建运行时环境，包括工具注册、权限策略、上下文压缩策略等。
 */
public class SkillRuntimeFactory {

    /**
     * 创建 SkillRuntime。
     */
    public SkillRuntime create(String skillId, SkillRuntimeConfig config) {
        String runtimeId = "rt_" + skillId + "_" + UUID.randomUUID().toString().substring(0, 8);
        ToolRegistry toolRegistry = buildToolRegistry(config);
        return new SkillRuntime(runtimeId, skillId, config, toolRegistry);
    }

    private ToolRegistry buildToolRegistry(SkillRuntimeConfig config) {
        ToolRegistry registry = new ToolRegistry();
        // 生产级：从 Spring 容器中查找并注册工具
        // 当前实现：工具注册由外部注入，这里只创建空注册表
        return registry;
    }
}
