# Agent Study — 持久化上下文

> **项目定位**：个人学习实践项目，用于深入理解 AI Agent / Skill / ReAct 的设计原理和代码实现。
> 基于 Spring AI + JDK 21 技术栈，根据 Claude Code、LangChain、Kimi Code 的设计思路做简化实现，便于源码级学习和 debug。

---

## 一、项目背景

### 1.1 为什么创建这个项目

在学习 AI Agent 过程中，发现以下概念容易混淆，需要专门的学习项目来动手实践：

1. **Skill 的三层定义**：SOP（.md 手册）vs Runtime（Java 代码）vs Governance（后台系统）
2. **ReAct 外循环 vs ToolCallAdvisor 内循环**：Claude Code 的长程 Agent 循环 vs Spring AI 的单次工具调用
3. **Eval-Driven Development**：如何给 Agent 写单元测试式的评估套件
4. **Context Engineering**：Prompt Engineering 的进阶——上下文窗口的结构化管理

### 1.2 学习路径

```
Phase 1: 理解概念（阅读 docs/ 下的设计文档）
    ↓
Phase 2: 动手实现（运行 ReActLoopTest 单元测试，单步 debug）
    ↓
Phase 3: 生产级运行时（基于 claw-code 泄露源码分析，实现结构化消息 + 自动压缩 + 权限 + 用量追踪）
    ↓
Phase 4: 对比学习（对比 ReActLoop 和 ToolCallAdvisor 的实现差异）
    ↓
Phase 5: 迁移到实际项目（将学到的设计应用到具体的 Skill 中）
```

---

## 二、技术栈

| 组件 | 版本 | 说明 |
|---|---|---|
| Java | 21 | Virtual Threads, HttpClient, Record |
| Spring Boot | 3.5.12 | Web 框架 |
| Spring AI | 1.1.6 | ChatClient, Tool Calling, RAG |
| OpenAI API | - | 学习阶段使用（也可用 DashScope） |
| Lombok | - | 减少样板代码 |
| JUnit 5 | - | 单元测试 |

---

## 三、核心概念澄清（必须理解）

### 3.1 Skill 的三层定义

在 `docs/skill-learning-and-iteration-best-practices.md` 中有详细说明，这里浓缩为一句话记忆：

- **SOP 层**（`.md`）：回答「这个 Skill 应该怎么做」
- **Runtime 层**（Java 代码）：回答「这个 Skill 怎么跑起来」
- **Governance 层**（后台系统）：回答「这个 Skill 跑得好不好、要不要升级」

**常见误区**：以为写好 `.md` SOP 就等于做好了 Skill。实际上三者缺一不可。

### 3.2 ReAct 外循环 vs ToolCallAdvisor 内循环

这是本项目最核心的学习点。

| 维度 | ReActLoop（本项目实现） | Spring AI ToolCallAdvisor |
|---|---|---|
| **循环位置** | `.call()` **外部** — 应用层显式控制 | `.call()` **内部** — 框架自动处理 |
| **LLM 调用次数** | 多次（每轮循环一次独立调用） | 1-2 次（封装在单次调用内） |
| **可见性** | 每步对应用层可见（可插入日志/检查点） | 对调用方透明 |
| **任务复杂度** | 长程任务（10-50+ 步） | 短程工具调用（1-3 步） |
| **类比** | 「整车」— 包含方向盘、导航、刹车 | 「内燃机」— 只负责驱动工具调用 |

**一句话记忆**：ToolCallAdvisor 是 ReAct 循环的一个**子集**（Action Execution 环节），ReAct 还包含观察、规划、终止判断等完整能力。

**debug 建议**：运行 `ReActLoopTest.testReActVsToolCallAdvisor()`，观察控制台的 `[DEBUG] LLM call #X` 输出，直观感受外循环的多轮调用。

### 3.3 为什么 ReAct 需要外循环

ToolCallAdvisor 能处理「查天气 → 回答」这种简单场景，但无法处理：

```
用户："帮我策划一个营销活动"
    │
    ▼
Step 1: 查竞品最近 30 天活动 → 获取数据
Step 2: 分析数据 → 发现某 KOL 带货效果很好
Step 3: 查该 KOL 联系方式 → 获取邮箱
Step 4: 生成邮件文案 → 产出内容
Step 5: 查库存 → 确认主推商品有货
Step 6: 生成完整活动方案 → 最终输出
```

这种**多步骤、条件依赖、需要中间结果驱动下一步**的任务，必须由外循环驱动。

---

## 四、项目结构

```
agent-study/
├── pom.xml                                    # Maven 配置
├── CLAUDE.md                                  # 本文件：持久化上下文
├── docs/                                      # 学习文档
│   ├── skill-learning-and-iteration-best-practices.md
│   └── react-loop-vs-toolcalladvisor-comparison.md
├── src/
│   ├── main/
│   │   ├── java/com/kuoge/agentstudy/
│   │   │   ├── AgentStudyApplication.java     # Spring Boot 入口
│   │   │   ├── tutorial/                      # 教学版 ReAct【基础学习】
│   │   │   │   ├── ReActLoop.java             # ⭐ 外循环核心【必读源码】
│   │   │   │   ├── ReActAgent.java            # 入口 Facade
│   │   │   │   ├── ReActStep.java             # 单步记录
│   │   │   │   ├── Action.java                # 动作定义
│   │   │   │   ├── Observation.java           # 观察结果
│   │   │   │   └── tool/                      # 工具层
│   │   │   │       ├── Tool.java
│   │   │   │       ├── ToolRegistry.java
│   │   │   │       └── ToolExecutor.java
│   │   │   └── production/                    # 生产级实现【进阶学习】
│   │   │       ├── agent/
│   │   │       │   └── ProductionReActAgent.java   # ⭐ 整合记忆+压缩+成本的完整 Agent
│   │   │       ├── memory/                    # 偏好记忆系统
│   │   │       │   ├── PreferenceMemory.java  # ⭐ 核心偏好 + 归档偏好
│   │   │       │   ├── UserPreference.java    # 偏好条目（带置信度）
│   │   │       │   ├── PreferenceStore.java   # 存储接口
│   │   │       │   └── InMemoryPreferenceStore.java
│   │   │       ├── context/                   # 9段式上下文压缩
│   │   │       │   ├── ContextCompressor.java # ⭐ 9段式压缩核心
│   │   │       │   ├── ContextSegment.java    # 单段管理
│   │   │       │   ├── SegmentType.java       # 9段枚举（含优先级）
│   │   │       │   └── CompressionStrategy.java
│   │   │       └── cost/                      # 成本优化
│   │   │           ├── TokenBudget.java       # Token 预算分配
│   │   │           ├── PromptTemplateCache.java # Prompt 缓存
│   │   │           ├── LazyLoader.java        # 懒加载器
│   │   │           └── CostTracker.java       # 成本追踪
│   │   │   └── runtime/                       # 生产级运行时【基于 claw-code 精华】
│   │   │       ├── session/                   # 结构化消息系统
│   │   │       │   ├── AgentSession.java      # ⭐ 会话状态管理（消息 + 压缩历史 + Fork）
│   │   │       │   ├── ConversationMessage.java # ⭐ 结构化消息（role + blocks + usage）
│   │   │       │   ├── ContentBlock.java      # ⭐ 内容块：Text/Thinking/ToolUse/ToolResult
│   │   │       │   └── MessageRole.java       # 消息角色枚举
│   │   │       ├── core/                      # 核心运行时
│   │   │       │   ├── ConversationRuntime.java # ⭐ ReAct 核心循环（对应 Rust conversation.rs）
│   │   │       │   ├── TurnSummary.java       # Turn 执行摘要
│   │   │       │   └── RuntimeConfig.java     # 运行时配置
│   │   │       ├── compact/                   # 自动上下文压缩
│   │   │       │   ├── SessionCompactor.java  # ⭐ 自动压缩（保留最近消息 + 边界保护）
│   │   │       │   ├── CompactionConfig.java  # 压缩配置
│   │   │       │   └── CompactionResult.java  # 压缩结果
│   │   │       ├── permission/                # 权限策略引擎
│   │   │       │   ├── PermissionPolicy.java  # ⭐ 权限策略（allow/deny/ask 规则）
│   │   │       │   ├── PermissionMode.java    # 权限模式层级
│   │   │       │   ├── PermissionOutcome.java # 权限结果
│   │   │       │   └── PermissionRule.java    # 单条权限规则
│   │   │       ├── usage/                     # 用量与成本追踪
│   │   │       │   ├── UsageTracker.java      # ⭐ 累积用量追踪
│   │   │       │   ├── TokenUsage.java        # 单次用量（input/output/cache）
│   │   │       │   └── CostEstimator.java     # 成本估算（按模型定价）
│   │   │       ├── hook/                      # 钩子系统
│   │   │       │   ├── ToolHook.java          # 钩子接口（Pre/Post/Failure）
│   │   │       │   ├── HookResult.java        # 钩子结果（可 deny/修改输入/覆盖权限）
│   │   │       │   └── HookEvent.java         # 钩子事件类型
│   │   │       └── client/                    # LLM 客户端接口
    │   │   │           ├── LlmClient.java         # 结构化 LLM 客户端
    │   │   │           └── LlmResponse.java       # 结构化响应（blocks + usage）
│   │   └── resources/
│   │       └── application.yml                # 配置文件
│   └── test/
│       └── java/com/kuoge/agentstudy/
│           ├── tutorial/
│           │   └── ReActLoopTest.java         # ⭐ 教学版测试（7 个场景）
│           ├── production/
│           │   └── ProductionAgentTest.java   # ⭐ 生产级测试（11 个场景）
│           └── production/runtime/
│               ├── SessionAndMessageTest.java     # 结构化消息系统测试（9 个场景）
│               ├── UsageAndCostTest.java          # 用量追踪测试（10 个场景）
│               ├── PermissionTest.java            # 权限策略测试（11 个场景）
│               ├── CompactAndHookTest.java        # 压缩+钩子测试（14 个场景）
│               └── ConversationRuntimeTest.java   # ⭐ 核心运行时测试（14 个场景）
```

### 4.1 学习重点文件（按优先级排序）

#### Phase 1：教学版 ReAct（先理解核心机制）

| 优先级 | 文件 | 学习目标 |
|---|---|---|
| P0 | `tutorial/ReActLoop.java` | 理解 ReAct 外循环的六步模型 |
| P0 | `tutorial/ReActLoopTest.java` | 通过 7 个测试场景理解不同行为 |
| P1 | `react-loop-vs-toolcalladvisor-comparison.md` | 对比理解外循环 vs 内循环 |

#### Phase 2：生产级实现（再理解工程化增强）

| 优先级 | 文件 | 学习目标 |
|---|---|---|
| P0 | `PreferenceMemory.java` | **为什么只记偏好不记事实** + 置信度机制 + 冲突处理 |
| P0 | `ContextCompressor.java` | **9段式结构化压缩**的设计原理和实现 |
| P1 | `ProductionReActAgent.java` | 如何把记忆、压缩、成本三个模块整合到 Agent 中 |
| P1 | `ProductionAgentTest.java` | 11 个测试场景覆盖三个核心模块 |
| P2 | `PromptTemplateCache.java` + `LazyLoader.java` | 成本优化策略 |

#### Phase 3：生产级运行时（基于 claw-code 源码精华）

| 优先级 | 文件 | 学习目标 |
|---|---|---|
| P0 | `ConversationRuntime.java` | **Turn 内多轮迭代**（1 Turn = N 次 LLM 调用）+ 工具调用链 |
| P0 | `ConversationMessage.java` + `ContentBlock.java` | **结构化消息**：为什么不用 StringBuilder 拼接 |
| P0 | `AgentSession.java` | 会话状态管理 + ToolUse/ToolResult 对完整性保护 + Fork |
| P0 | `SessionCompactor.java` | **自动压缩**：基于 token 阈值触发 + 边界保护（不拆分 Tool 对） |
| P1 | `PermissionPolicy.java` | 权限策略引擎：allow/deny/ask 规则 + 模式层级 |
| P1 | `UsageTracker.java` + `CostEstimator.java` | 用量追踪 + 按模型成本估算 |
| P1 | `ToolHook.java` | 钩子系统：Pre/Post/Failure 干预工具执行 |
| P2 | `LlmClient.java` + `LlmResponse.java` | 结构化 LLM 客户端（输入/输出都从字符串升级为结构化对象） |

---

## 五、快速开始

### 5.1 运行单元测试（无需真实 LLM）

```bash
cd /Users/liq/work/idea/ai/agent-study

# 教学版 ReAct 测试（7 个场景）
mvn test -Dtest=ReActLoopTest   # 或 tutorial.ReActLoopTest

# 生产级实现测试（11 个场景）
mvn test -Dtest=ProductionAgentTest

# 生产级运行时测试（58 个场景，覆盖结构化消息/压缩/权限/用量/核心循环）
mvn test -Dtest='production.*RuntimeTest,production.runtime.SessionAndMessageTest,production.runtime.UsageAndCostTest,production.runtime.PermissionTest,production.runtime.CompactAndHookTest'

# 全部测试
mvn test
```

所有测试使用 Mock LLM 客户端，**无需配置 API Key**。

### 5.2 单步 debug 建议

#### 教学版 ReAct

在 `tutorial/ReActLoopTest.testMultiStepToolUse()` 中设置断点，观察：

1. `tutorial.ReActLoop.run()` 的 `for` 循环如何执行多轮
2. 每轮 `llmClient.call(context)` 的 `context` 如何累积
3. `toolExecutor.execute(action)` 如何将 Observation 注入上下文
4. `steps` 列表如何记录完整的执行轨迹

#### 生产级实现

在 `ProductionAgentTest.testProductionAgentEndToEnd()` 中设置断点，观察：

1. `ContextCompressor.build()` 如何按 9 段优先级压缩
2. `PreferenceMemory.buildCorePreferencePrompt()` 如何筛选高置信度偏好
3. `TokenBudget` 如何随步骤消耗累积
4. `CostTracker` 如何记录每次 LLM 调用和工具调用

#### 生产级运行时（基于 claw-code 分析）

在 `ConversationRuntimeTest.testSingleToolUse()` 中设置断点，观察：

1. `ConversationRuntime.runTurn()` 的 `while (true)` 循环如何执行多轮迭代
2. 第一轮 LLM 返回 `ToolUseBlock`，第二轮返回 `TextBlock`（最终答案）
3. `executeToolUse()` 如何串联 Hook → Permission → Tool Execution → PostHook
4. `AgentSession.pushMessage()` 如何维护消息完整性（ToolResult 必须有前置 Assistant+ToolUse）
5. 在 `testParallelToolUses()` 中观察：一次 Assistant 消息可包含多个 ToolUseBlock

在 `CompactAndHookTest.testSessionCompactorCompactsLargeSession()` 中观察：
1. `SessionCompactor.compact()` 如何基于 token 阈值判断是否需要压缩
2. 压缩后保留的最近 N 条消息如何不破坏 ToolUse/ToolResult 配对
3. 生成的结构化摘要包含哪些信息（scope、timeline、pending work、key files）

### 5.3 对比 ToolCallAdvisor

在实际的 Spring AI 项目中，打开使用 ToolCallAdvisor 的代码，对比：

- `ToolCallAdvisor`：在 `.call()` 内部自动完成 1-2 轮工具调用
- `ReActLoop`：在 `.call()` 外部显式控制 N 轮循环

---

## 六、关键设计决策

### 6.1 为什么 LlmClient 是接口而非直接用 ChatClient

为了**单元测试不依赖真实 LLM**。`ReActLoopTest` 中的 `MockLlm` 实现可以精确控制每一步的 Thought + Action，让测试可预测、可重复。

在实际项目中，会包装 Spring AI 的 `ChatClient` 实现这个接口。

### 6.2 为什么上下文是 StringBuilder 而非 ChatMemory

为了**简化学习**。真实项目使用 `MessageChatMemoryAdvisor` 管理对话历史，但这里用 `StringBuilder` 拼接文本上下文，便于 debug 时直接打印查看内容。

### 6.3 为什么最大步数是硬性限制

防止 LLM 进入无限循环（如反复调用同一工具）。Claude Code 默认限制约 50 步，本项目默认 10 步。

### 6.4 为什么从 StringBuilder 升级到结构化消息（ConversationMessage + ContentBlock）

教学版（`tutorial/`）使用 `StringBuilder` 拼接上下文，生产级（`production/runtime/`）使用 `List<ConversationMessage>`：

| 维度 | StringBuilder | ConversationMessage |
|---|---|---|
| 工具调用识别 | 字符串解析（容易出错） | `ToolUseBlock` 精确提取 |
| 多工具并行 | 不支持 | 一个 Assistant 消息可含多个 `ToolUseBlock` |
| Token 估算 | 粗略字符数/4 | 按 block 类型精确估算 |
| 消息完整性 | 无保护 | `AgentSession` 强制 ToolResult 必须有对应 ToolUse |
| 思考过程 | 文本混在输出中 | `ThinkingBlock` 独立存储 |

参考 claw-code Rust 实现：`session.rs/ConversationMessage` + `session.rs/ContentBlock`。

### 6.5 为什么需要自动压缩（SessionCompactor）而非仅 9 段式压缩

- **9 段式压缩**（`ContextCompressor`）：在**构建请求时**按业务优先级分配 token 配额，属于「静态压缩」
- **自动压缩**（`SessionCompactor`）：在**运行时**检测 token 阈值，将旧消息总结为摘要，属于「动态压缩」

两者互补：9 段式决定「什么该留」，自动压缩决定「什么时候该清理」。

参考 claw-code Rust 实现：`compact.rs` 中的 `should_compact()` + `compact_session()` + ToolUse/ToolResult 边界保护。

### 6.6 为什么权限系统需要规则引擎（PermissionRule）

简单的 allow/deny 列表无法处理「只允许 git 命令，但禁止 rm -rf」这类细粒度需求。

`PermissionRule` 支持三种匹配器：
- `AnyMatcher`：匹配任意输入（`bash` → 匹配所有 bash 调用）
- `ExactMatcher`：精确匹配（`bash(ls)` → 只匹配 `ls`）
- `PrefixMatcher`：前缀匹配（`bash(git:*)` → 匹配 `git status`, `git log` 等）

参考 claw-code Rust 实现：`permissions.rs/PermissionRule` + `PermissionPolicy.authorize_with_context()`。

### 6.7 为什么需要 Hook 系统（ToolHook）

Hook 提供**不修改核心循环**的扩展点：

| 钩子 | 用途 | 示例 |
|---|---|---|
| `preToolUse` | 修改输入、阻止执行 | 安全审计：拦截危险命令 |
| `postToolUse` | 修改输出、追加反馈 | 追加审计日志到 tool result |
| `postToolUseFailure` | 错误处理、重试逻辑 | 网络超时自动重试 |

参考 claw-code Rust 实现：`hooks.rs/HookRunner`（支持外部命令作为钩子）。

---

## 七、与实际项目的映射

| 学习项目 | 实际项目 | 说明 |
|---|---|---|
| `ReActLoop` | `AgentOrchestrator` + `Agent.execute()` | 实际项目通常是单次调用模式，未来长程任务需要引入 ReAct 外循环 |
| `ToolRegistry` | `AgentRegistry` | 实际项目注册的是 Agent，学习项目注册的是 Tool |
| `ToolExecutor` | `ToolCallAdvisor` | 实际项目由 Spring AI 框架自动执行，学习项目是手动实现便于理解 |
| `ReActStep` | 执行日志 | 实际项目通过日志系统上报，学习项目保存在内存中 |

---

## 八、待探索话题（TODO）

- [x] 分析 claw-code 泄露源码，提取 ReAct 精华架构（Python Porting Workspace + Rust Runtime）
- [x] 实现生产级运行时：结构化消息 + 会话管理 + 自动压缩 + 权限 + 用量追踪 + 钩子（34 个新文件，58 个测试，全部通过）
- [ ] 接入真实的 Spring AI ChatClient（OpenAI / DashScope）
- [ ] 实现 LLM 响应解析器（从自然语言/流式事件中提取 Text/Thinking/ToolUse blocks）
- [ ] 添加上下文压缩策略（滑动窗口 / 关键信息摘要）
- [ ] 实现用户交互中断（长程任务中请求用户确认）
- [ ] 对比 LangGraph 的 StateGraph 实现
- [ ] 将学到的 ReAct 设计应用到具体的 Skill 中

---

> **最后更新**：2025-05-15（包结构重构：react→tutorial，runtime→production/runtime；新增生产级运行时，基于 claw-code 分析）
> **维护者**：个人学习项目，由 AI 辅助创建
