package com.kuoge.agentstudy.production.skill.runtime;

import lombok.Getter;

import java.util.List;

/**
 * Skill 准入校验失败异常。
 *
 * <p>对应 claw-code Rust 实现：{@code task_packet.rs/TaskPacketValidationError}
 * —— 累积所有错误一次性抛出，便于调试。
 */
@Getter
public class SkillBindingException extends RuntimeException {

    private final List<String> errors;

    public SkillBindingException(List<String> errors) {
        super("Skill binding failed: " + String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }
}
