# Agent Study

个人 AI Agent / Skill / ReAct 学习实践项目。

基于 Spring AI + JDK 21技术栈，根据 Claude Code、LangChain、Kimi Code的设计思路做简化实现，便于源码级学习和 debug。

## 快速开始

```bash
# 运行单元测试（无需 API Key，全部使用 Mock）
mvn test -Dtest=ReActLoopTest

# 运行 Spring Boot 应用（需要配置 OpenAI API Key）
export OPENAI_API_KEY=sk-xxx
mvn spring-boot:run
```

## 学习文档

| 文档 | 内容 |
|---|---|
| `docs/skill-learning-and-iteration-best-practices.md` | Skill 迭代优化最佳实践（Claude 架构解析） |
| `docs/react-loop-vs-toolcalladvisor-comparison.md` | ReAct 外循环 vs ToolCallAdvisor 内循环深度对比 |
| `CLAUDE.md` | 项目持久化上下文（沟通历史、设计决策、TODO） |

## 核心源码

```
src/main/java/com/kuoge/agentstudy/tutorial/
├── ReActLoop.java        # ⭐ ReAct 外循环核心实现
├── ReActStep.java        # 单步记录 (Thought, Action, Observation)
├── Action.java           # 动作定义
├── Observation.java      # 观察结果
├── ReActAgent.java       # 入口 Facade
└── tool/
    ├── Tool.java           # 工具接口
    ├── ToolRegistry.java   # 工具注册中心
    └── ToolExecutor.java   # 工具执行器
```

## 单元测试

```bash
mvn test -Dtest=ReActLoopTest
```

覆盖 7 个场景：
1. 单次直接回答（无工具调用）
2. 多步工具调用
3. 工具失败后的容错恢复
4. 最大步数安全终止
5. 上下文累积验证
6. 多工具选择
7. ReAct 外循环 vs ToolCallAdvisor 内循环对比

## 技术栈

- Java 21
- Spring Boot 3.5.12
- Spring AI 1.1.6
- JUnit 5
