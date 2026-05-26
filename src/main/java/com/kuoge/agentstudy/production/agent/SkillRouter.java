package com.kuoge.agentstudy.production.agent;

import com.kuoge.agentstudy.production.skill.Skill;

import java.util.List;
import java.util.Optional;

/**
 * Skill 路由器 —— 根据用户输入决定调用哪个 Skill。
 *
 * <p>对应 claude code 中"主 Agent 持有所有 skills，按 query 动态匹配"的设计。
 *
 * <p>简单实现可基于关键词匹配；进阶实现可调用 LLM 做 zero-shot 分类，或基于
 * embedding 做向量检索。
 */
@FunctionalInterface
public interface SkillRouter {

    /**
     * 根据用户输入选择 Skill。
     *
     * @param userInput 用户输入
     * @param candidates 可选的 Skill 列表（已激活）
     * @return 选中的 Skill；未匹配到时返回空
     */
    Optional<Skill> route(String userInput, List<Skill> candidates);
}
