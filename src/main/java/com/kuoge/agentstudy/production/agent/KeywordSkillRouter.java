package com.kuoge.agentstudy.production.agent;

import com.kuoge.agentstudy.production.skill.Skill;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 关键词匹配版 SkillRouter —— 默认实现，便于学习和测试。
 *
 * <p>规则：
 * <ol>
 *   <li>输入文本包含 Skill.name（不区分大小写）→ 命中</li>
 *   <li>输入文本包含 Skill.domain → 命中</li>
 *   <li>输入文本与 Skill.description 关键词重叠 → 命中</li>
 *   <li>多个候选时返回第一个命中（确定性）</li>
 * </ol>
 *
 * <p>生产级路由应替换为 LLM 分类或 embedding 召回。
 */
public class KeywordSkillRouter implements SkillRouter {

    @Override
    public Optional<Skill> route(String userInput, List<Skill> candidates) {
        if (userInput == null || userInput.isBlank() || candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        String lower = userInput.toLowerCase(Locale.ROOT);

        for (Skill skill : candidates) {
            if (matchesByName(lower, skill) || matchesByDomain(lower, skill)) {
                return Optional.of(skill);
            }
        }
        for (Skill skill : candidates) {
            if (matchesByDescription(lower, skill)) {
                return Optional.of(skill);
            }
        }
        return Optional.empty();
    }

    private boolean matchesByName(String lower, Skill skill) {
        String name = skill.getName();
        return name != null && lower.contains(name.toLowerCase(Locale.ROOT));
    }

    private boolean matchesByDomain(String lower, Skill skill) {
        String domain = skill.getDomain();
        return domain != null && !domain.isBlank()
                && lower.contains(domain.toLowerCase(Locale.ROOT));
    }

    private boolean matchesByDescription(String lower, Skill skill) {
        String desc = skill.getDescription();
        if (desc == null || desc.isBlank()) return false;
        for (String token : desc.toLowerCase(Locale.ROOT).split("\\W+")) {
            if (token.length() >= 4 && lower.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
