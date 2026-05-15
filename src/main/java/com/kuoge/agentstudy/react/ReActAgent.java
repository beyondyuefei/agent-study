package com.kuoge.agentstudy.react;

import com.kuoge.agentstudy.react.tool.Tool;
import com.kuoge.agentstudy.react.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * ReAct Agent 入口：封装 ReActLoop，提供简洁的调用接口。
 *
 * <p>这是面向业务层的 facade，学习时应重点查看 {@link ReActLoop} 的实现。
 */
@Slf4j
@RequiredArgsConstructor
public class ReActAgent {

    private final ReActLoop loop;

    /**
     * 执行用户请求。
     *
     * @param userQuery 用户输入
     * @return 最终答案
     */
    public String execute(String userQuery) {
        final String result = loop.run(userQuery);
        loop.printTrace();
        return result;
    }

    /**
     * 获取执行轨迹（用于分析和 debug）。
     */
    public List<ReActStep> getSteps() {
        return List.copyOf(loop.getSteps());
    }

    /**
     * 工厂方法：快速创建一个带工具的 Agent。
     */
    public static ReActAgentBuilder builder() {
        return new ReActAgentBuilder();
    }

    public static class ReActAgentBuilder {
        private final ToolRegistry registry = new ToolRegistry();
        private ReActLoop.LlmClient llmClient;
        private ReActLoop.ReActConfig config = ReActLoop.ReActConfig.defaults();

        public ReActAgentBuilder withLlmClient(ReActLoop.LlmClient llmClient) {
            this.llmClient = llmClient;
            return this;
        }

        public ReActAgentBuilder withConfig(ReActLoop.ReActConfig config) {
            this.config = config;
            return this;
        }

        public ReActAgentBuilder withTool(Tool tool) {
            this.registry.register(tool);
            return this;
        }

        public ReActAgent build() {
            if (llmClient == null) {
                throw new IllegalStateException("LLM client is required");
            }
            return new ReActAgent(new ReActLoop(llmClient, registry, config));
        }
    }
}
