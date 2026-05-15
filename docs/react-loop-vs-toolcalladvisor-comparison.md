# Claude Code ReAct Loop 与 Spring AI ToolCallAdvisor 深度对比

> **目标**：厘清 Claude Code 的「增强版 ReAct 循环」与 Spring AI `ToolCallAdvisor` 之间的关系——它们解决的问题域是否相同？技术实现是否类似？对 AI Agent 系统的架构选型有什么影响？
>
> **结论前置**：核心循环结构相似，但解决的问题域和技术层次完全不同。ToolCallAdvisor 只实现了 ReAct 循环中的**一个子环节**（Action Execution）。

---

## 一、解决的问题域对比

| 维度 | Claude Code 增强版 ReAct | Spring AI ToolCallAdvisor |
|---|---|---|
| **核心目标** | 让 AI **自主完成复杂长程任务**（如"重构项目日志系统"） | 让 AI **在回答前调用工具获取信息**（如"查一下订单状态"） |
| **任务复杂度** | 高：需要 10-50+ 步，涉及文件读写、终端执行、代码编辑、Web 浏览 | 低：通常 1-3 步，调用预注册的业务 API 或查询数据库 |
| **决策自主性** | **高**：每步都需要 LLM 重新推理"下一步该做什么" | **低**：工具调用由 LLM 在单次推理中决定，框架自动执行 |
| **环境交互范围** | 与**整个操作系统/文件系统/Web** 交互 | 与**预注册的 `@Tool` 方法**交互 |
| **错误处理方式** | Agent 自主决定 retry / 换策略 / 向用户求助 / 终止 | 框架捕获异常，由应用层处理或抛给调用方 |
| **典型使用场景** | "帮我排查这个 bug"、"把项目从 Java 11 升级到 21"、"分析竞品最近一个月的营销策略" | "订单 12345 的状态是什么"、"今天北京天气如何"、"这件商品库存还有多少" |

### 一句话概括区别

- **Claude Code ReAct** 解决的是 **"Agent 自主规划与执行"** 问题
- **ToolCallAdvisor** 解决的是 **"LLM 单次请求中的工具调用编排"** 问题

---

## 二、领域模型

> 本节从领域驱动设计（DDD）视角，抽象出 ReAct Loop 与 ToolCallAdvisor 涉及的核心模型及其关联关系，帮助读者建立统一的概念图谱。

### 2.1 核心模型一览

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        ReAct / ToolCallAdvisor 领域模型                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   ┌──────────────┐     1:N     ┌──────────────────┐                       │
│   │    Agent     │◄────────────│   AgentSession   │                       │
│   │  (AI Agent)  │             │   (会话/上下文)   │                       │
│   └──────┬───────┘             └────────┬─────────┘                       │
│          │                              │                                   │
│          │ 1:1                          │ 1:1                               │
│          ▼                              ▼                                   │
│   ┌──────────────┐              ┌──────────────────┐                       │
│   │   SkillSet   │              │  AgentContext    │                       │
│   │  (技能集合)   │              │ (请求/用户/场景)  │                       │
│   └──────┬───────┘              └──────────────────┘                       │
│          │                                                                  │
│          │ 1:N                                                              │
│          ▼                                                                  │
│   ┌──────────────┐              ┌──────────────────┐     1:N     ┌───────┐ │
│   │     Tool     │◄─────────────│    ToolCall      │◄────────────│LoopStep│ │
│   │  (工具定义)   │              │   (工具调用实例)  │             │(循环步)│ │
│   └──────────────┘              └──────────────────┘             └───┬───┘ │
│                                                                     │     │
│                              ┌──────────────────┐                   │     │
│                              │   ReActLoop      │◄──────────────────┘     │
│                              │  (主循环/编排器)  │                         │
│                              └────────┬─────────┘                         │
│                                       │ 1:1                               │
│                                       ▼                                   │
│                              ┌──────────────────┐                         │
│                              │  ToolCallAdvisor │                         │
│                              │ (工具调用顾问)    │                         │
│                              └────────┬─────────┘                         │
│                                       │ 1:1                               │
│                                       ▼                                   │
│                              ┌──────────────────┐                         │
│                              │   AdvisorChain   │                         │
│                              │   (顾问链)        │                         │
│                              └──────────────────┘                         │
│                                                                             │
│   ┌──────────────┐              ┌──────────────────┐     1:N     ┌───────┐ │
│   │  ChatClient  │◄─────────────│     Message      │◄────────────│ ChatMemory│
│   │ (聊天客户端)  │              │    (消息)         │             │(记忆)  │ │
│   └──────────────┘              └──────────────────┘             └───────┘ │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 模型关联关系

| 关联双方 | 关系类型 | 说明 |
|---|---|---|
| `Agent` → `AgentSession` | **1:N** | 一个 Agent 可处理多个会话，每个会话有独立的上下文生命周期 |
| `AgentSession` → `AgentContext` | **1:1** | 每个会话绑定一个上下文，包含 userId、scene、sessionId 等 |
| `Agent` → `SkillSet` | **1:1** | 每个 Agent 拥有一组可用技能（即注册的工具集合） |
| `SkillSet` → `Tool` | **1:N** | 一个技能集合包含多个工具定义（`@Tool` 方法） |
| `Tool` → `ToolCall` | **1:N** | 一个工具定义可被多次调用，每次调用产生一个 ToolCall 实例 |
| `ReActLoop` → `LoopStep` | **1:N** | 一个 ReAct 主循环包含多轮步骤（Step 1 → Step N） |
| `LoopStep` → `ToolCall` | **1:N** | 单步内 LLM 可能生成多个并行工具调用（如同时查库存和查价格） |
| `ChatClient` → `ToolCallAdvisor` | **1:1** | ToolCallAdvisor 附着于 ChatClient，负责驱动工具调用内循环 |
| `ToolCallAdvisor` → `AdvisorChain` | **1:1** | ToolCallAdvisor 是 AdvisorChain 中的一个节点，按 order 参与编排 |
| `ChatMemory` → `Message` | **1:N** | 对话记忆由多条 Message 组成（System / User / Assistant / Tool） |

### 2.3 核心模型字段

#### Agent（AI Agent 主体）

| 字段 | 类型 | 说明 |
|---|---|---|
| `agentId` | String | Agent 唯一标识，如 `customer-service-agent` |
| `agentType` | Enum | 类型：`SIMPLE_QUERY` / `REACT_LONG_HORIZON` / `RAG_RETRIEVAL` |
| `skillSet` | SkillSet | 当前 Agent 可用的技能集合 |
| `maxSteps` | int | 最大循环步数，ReAct Agent 通常 10-50，简单 Agent 为 1 |
| `systemPrompt` | String | 系统级角色定义 Prompt |

#### AgentSession（会话实例）

| 字段 | 类型 | 说明 |
|---|---|---|
| `sessionId` | String | 会话唯一标识（UUID） |
| `agentId` | String | 所属 Agent ID |
| `status` | Enum | 会话状态：`ACTIVE` / `PAUSED` / `COMPLETED` / `ERROR` |
| `createdAt` | Instant | 会话创建时间 |
| `lastActiveAt` | Instant | 最后活跃时间 |

#### AgentContext（请求上下文）

| 字段 | 类型 | 说明 |
|---|---|---|
| `sessionId` | String | 关联的会话 ID |
| `userId` | String | 用户唯一标识 |
| `instruction` | String | 当前用户指令 |
| `scene` | String | 业务场景标签，如 `CUSTOMER_SERVICE` / `PRODUCT_SEARCH` |
| `metadata` | Map | 扩展参数（如灰度版本号、traceId） |

#### ReActLoop（主循环/编排器）

| 字段 | 类型 | 说明 |
|---|---|---|
| `loopId` | String | 循环实例 ID |
| `sessionId` | String | 关联会话 ID |
| `currentStep` | int | 当前执行到第几步 |
| `maxIterations` | int | 最大迭代次数 |
| `terminationReason` | Enum | 终止原因：`TASK_COMPLETED` / `MAX_STEPS` / `USER_INTERRUPT` / `UNRECOVERABLE_ERROR` |
| `steps` | List&lt;LoopStep&gt; | 已执行的步骤列表 |

#### LoopStep（循环步骤）

| 字段 | 类型 | 说明 |
|---|---|---|
| `stepIndex` | int | 步骤序号（从 0 开始） |
| `thought` | String | LLM 推理过程（Thought） |
| `action` | String | 决策动作，如 `READ_FILE` / `EXECUTE_TOOL` / `EDIT_CODE` |
| `toolCalls` | List&lt;ToolCall&gt; | 本步骤生成的工具调用列表 |
| `observation` | String | 执行后观察到的结果 |
| `timestamp` | Instant | 步骤执行时间 |

#### Tool（工具定义）

| 字段 | 类型 | 说明 |
|---|---|---|
| `toolName` | String | 工具唯一名称，对应 `@Tool(name=...)` |
| `description` | String | 工具描述，**直接影响模型 tool choice 质量** |
| `parameterSchema` | JSON Schema | 参数结构定义（Spring AI 自动生成） |
| `targetBean` | Object | 工具方法所在的 Spring Bean 实例 |
| `timeoutMs` | long | 工具执行超时时间 |

#### ToolCall（工具调用实例）

| 字段 | 类型 | 说明 |
|---|---|---|
| `callId` | String | 调用唯一 ID（LLM 生成） |
| `toolName` | String | 调用的工具名称 |
| `arguments` | Map&lt;String, Object&gt; | 实际传入的参数键值对 |
| `result` | String | 工具执行结果（格式化后） |
| `status` | Enum | 调用状态：`PENDING` / `SUCCESS` / `FAILED` / `TIMEOUT` |
| `durationMs` | long | 执行耗时 |

#### ToolCallAdvisor（工具调用顾问）

| 字段 | 类型 | 说明 |
|---|---|---|
| `advisorId` | String | Advisor 标识 |
| `order` | int | 在 AdvisorChain 中的排序（数值越小越外层） |
| `toolCallingManager` | ToolCallingManager | 工具调用管理器，负责解析与反射执行 |
| `maxToolCallRounds` | int | 最大工具调用轮次，默认 5 |

#### Message（消息）

| 字段 | 类型 | 说明 |
|---|---|---|
| `messageId` | String | 消息唯一 ID |
| `role` | Enum | 角色：`SYSTEM` / `USER` / `ASSISTANT` / `TOOL` |
| `content` | String | 消息文本内容 |
| `toolCalls` | List&lt;ToolCall&gt; | ASSISTANT 消息中附带的工具调用请求 |
| `toolCallId` | String | TOOL 消息中回指的 callId |
| `timestamp` | Instant | 消息时间戳 |

### 2.4 两个视角下的模型差异

| 模型 | Claude Code ReAct 视角 | ToolCallAdvisor 视角 |
|---|---|---|
| **循环主体** | `ReActLoop` 是显式外部编排器，由 Agent 自己驱动 | `ToolCallAdvisor` 是隐式内环组件，由框架驱动 |
| **Step 粒度** | `LoopStep` 是**业务级步骤**（如"读取 pom.xml → 分析 → 修改"） | 一轮 tool-call 只是**技术级子步骤**，通常 1-2 轮即结束 |
| **上下文管理** | Agent 主动维护 `ChatMemory`，需手动总结/截断 | `ChatMemoryAdvisor` 自动管理，应用层无感知 |
| **Tool 范围** | `Tool` 可以是任意系统操作（文件/网络/进程） | `Tool` 仅限于 Spring Bean 中被 `@Tool` 注解的方法 |
| **终止判断** | `ReActLoop` 判断"任务是否完成" | `ToolCallAdvisor` 判断"是否还有 toolCalls" |

---

## 三、设计模型

> 本节将领域模型中的抽象概念映射到本项目代码中的具体类，给出完整的包路径和字段对应关系。阅读代码时，可对照此表快速定位。

### 3.1 领域模型 → 代码类映射总表

| 领域模型 | 代码类（完整路径） | 所在层级 | 说明 |
|---|---|---|---|
| `Agent` | `com.kuoge.agentstudy.tutorial.ReActAgent` | tutorial | ReAct Agent 入口 facade，封装 `ReActLoop` |
| `Agent` | `com.kuoge.agentstudy.production.agent.ProductionReActAgent` | production | 生产级 Agent，整合记忆/压缩/成本控制 |
| `ReActLoop` | `com.kuoge.agentstudy.tutorial.ReActLoop` | tutorial | 基础版 ReAct 外循环（StringBuilder 上下文） |
| `ReActLoop` | `com.kuoge.agentstudy.production.runtime.core.ConversationRuntime` | production | 生产级对话运行时（结构化消息 + Turn 内迭代） |
| `LoopStep` | `com.kuoge.agentstudy.tutorial.ReActStep` | tutorial | 单步记录（Thought + Action + Observation） |
| `LoopStep` | `com.kuoge.agentstudy.production.model.ReActStep` | production | 生产级单步记录（与 tutorial 同构） |
| `Action` | `com.kuoge.agentstudy.tutorial.Action` | tutorial | 工具调用动作（toolName + arguments） |
| `Action` | `com.kuoge.agentstudy.production.model.Action` | production | 生产级 Action（与 tutorial 同构） |
| `Observation` | `com.kuoge.agentstudy.tutorial.Observation` | tutorial | 工具执行结果（content + success） |
| `Observation` | `com.kuoge.agentstudy.production.model.Observation` | production | 生产级 Observation（与 tutorial 同构） |
| `Tool` | `com.kuoge.agentstudy.tutorial.tool.Tool` (interface) | tutorial | 工具接口（name / description / execute） |
| `Tool` | `com.kuoge.agentstudy.production.tool.Tool` (interface) | production | 生产级工具接口（与 tutorial 同构） |
| `ToolRegistry` | `com.kuoge.agentstudy.tutorial.tool.ToolRegistry` | tutorial | 工具注册中心（LinkedHashMap 存储） |
| `ToolRegistry` | `com.kuoge.agentstudy.production.tool.ToolRegistry` | production | 生产级工具注册中心 |
| `ToolExecutor` | `com.kuoge.agentstudy.tutorial.tool.ToolExecutor` | tutorial | 工具执行器（解析 Action → 反射调用 Tool） |
| `ToolExecutor` | `com.kuoge.agentstudy.production.tool.ToolExecutor` | production | 生产级工具执行器 |
| `AgentSession` | `com.kuoge.agentstudy.production.runtime.session.AgentSession` | production | 会话管理器（消息历史 + Fork + 压缩记录） |
| `Message` | `com.kuoge.agentstudy.production.runtime.session.ConversationMessage` | production | 结构化消息（role + blocks + usage） |
| `ContentBlock` | `com.kuoge.agentstudy.production.runtime.session.ContentBlock` (sealed interface) | production | 内容块：Text / Thinking / ToolUse / ToolResult |
| `MessageRole` | `com.kuoge.agentstudy.production.runtime.session.MessageRole` (enum) | production | SYSTEM / USER / ASSISTANT / TOOL |
| `LlmClient` | `com.kuoge.agentstudy.tutorial.ReActLoop.LlmClient` (inner interface) | tutorial | 基础版 LLM 客户端（String 上下文） |
| `LlmClient` | `com.kuoge.agentstudy.production.runtime.client.LlmClient` (interface) | production | 生产级 LLM 客户端（结构化消息列表） |
| `LlmResponse` | `com.kuoge.agentstudy.tutorial.ReActLoop.LlmResponse` (inner record) | tutorial | 基础版响应（thought + action） |
| `LlmResponse` | `com.kuoge.agentstudy.production.runtime.client.LlmResponse` (record) | production | 生产级响应（blocks + usage） |
| `TurnSummary` | `com.kuoge.agentstudy.production.runtime.core.TurnSummary` (record) | production | 单次 Turn 执行摘要 |
| `RuntimeConfig` | `com.kuoge.agentstudy.production.runtime.core.RuntimeConfig` (record) | production | 运行时配置（maxIterations / permissionPolicy / compaction） |
| `TokenUsage` | `com.kuoge.agentstudy.production.runtime.usage.TokenUsage` (record) | production | Token 用量（input / output / cacheCreate / cacheRead） |
| `UsageTracker` | `com.kuoge.agentstudy.production.runtime.usage.UsageTracker` | production | 会话级用量累积追踪 |
| `ToolHook` | `com.kuoge.agentstudy.production.runtime.hook.ToolHook` (interface) | production | 工具钩子（pre / post / failure） |
| `HookResult` | `com.kuoge.agentstudy.production.runtime.hook.HookResult` (record) | production | 钩子执行结果（denied / failed / cancelled / messages） |
| `PermissionPolicy` | `com.kuoge.agentstudy.production.runtime.permission.PermissionPolicy` | production | 权限策略引擎（allow / deny / ask 规则） |
| `PermissionMode` | `com.kuoge.agentstudy.production.runtime.permission.PermissionMode` (enum) | production | Allow / Prompt / WorkspaceWrite / DangerFullAccess |
| `PermissionOutcome` | `com.kuoge.agentstudy.production.runtime.permission.PermissionOutcome` (sealed) | production | Allow / Deny / Ask |
| `SessionCompactor` | `com.kuoge.agentstudy.production.runtime.compact.SessionCompactor` | production | 会话自动压缩器（摘要 + 边界保护） |
| `CompactionConfig` | `com.kuoge.agentstudy.production.runtime.compact.CompactionConfig` (record) | production | 压缩配置（preserveRecent / maxTokens） |
| `CompactionResult` | `com.kuoge.agentstudy.production.runtime.compact.CompactionResult` (record) | production | 压缩结果（summary / compactedSession / removedCount） |
| `ContextCompressor` | `com.kuoge.agentstudy.production.context.ContextCompressor` | production | 9段式上下文压缩器 |
| `ContextSegment` | `com.kuoge.agentstudy.production.context.ContextSegment` | production | 上下文段（type / content / strategy / tokens） |
| `SegmentType` | `com.kuoge.agentstudy.production.context.SegmentType` (enum) | production | SYSTEM_IDENTITY / USER_PREFERENCE / ... / SCRATCHPAD |
| `CompressionStrategy` | `com.kuoge.agentstudy.production.context.CompressionStrategy` (enum) | production | PRESERVE / TRUNCATE / SUMMARIZE / EVICT / LAZY_LOAD |
| `PreferenceMemory` | `com.kuoge.agentstudy.production.memory.PreferenceMemory` | production | 偏好记忆系统（Core + Archival） |
| `UserPreference` | `com.kuoge.agentstudy.production.memory.UserPreference` (record) | production | 用户偏好条目（confidence / freshness） |
| `CostTracker` | `com.kuoge.agentstudy.production.cost.CostTracker` | production | 成本追踪器（LLM + Tool 调用记录） |
| `TokenBudget` | `com.kuoge.agentstudy.production.cost.TokenBudget` | production | Token 预算管理 |
| `PromptTemplateCache` | `com.kuoge.agentstudy.production.cost.PromptTemplateCache` | production | Prompt 模板缓存 |

### 3.2 核心类的字段速查

#### ReActLoop（tutorial 层）

```java
public class ReActLoop {
    private final LlmClient llmClient;      // LLM 客户端接口
    private final ToolRegistry toolRegistry; // 工具注册表
    private final ToolExecutor toolExecutor; // 工具执行器
    private final ReActConfig config;        // 配置
    private final List<ReActStep> steps;     // 执行轨迹
}

public record ReActConfig(
    String systemPrompt,           // 系统提示词
    int maxSteps,                  // 最大步数（默认 10）
    boolean enableContextCompression, // 是否启用上下文压缩
    int contextMaxLength           // 上下文最大长度（默认 8000）
) {}

public record LlmResponse(String thought, Action action) {}
```

#### ConversationRuntime（production 层）

```java
public class ConversationRuntime {
    private final AgentSession session;        // 会话状态
    private final LlmClient llmClient;         // 生产级 LLM 客户端
    private final ToolRegistry toolRegistry;   // 工具注册表
    private final RuntimeConfig config;        // 运行时配置
    private final UsageTracker usageTracker;   // 用量追踪
    private final SessionCompactor compactor;  // 会话压缩器
    private final List<ToolHook> hooks;        // 注册的工具钩子
    private int turnCount;                     // 已完成 Turn 数
}

public record RuntimeConfig(
    String systemPrompt,               // 系统提示词
    int maxIterationsPerTurn,          // 每 Turn 最大迭代数
    int maxTurnsPerSession,            // 每 Session 最大 Turn 数
    CompactionConfig compactionConfig, // 压缩配置
    PermissionPolicy permissionPolicy, // 权限策略
    boolean autoCompactionEnabled,     // 是否自动压缩
    int autoCompactionTokenThreshold   // 自动压缩 token 阈值
) {}
```

#### AgentSession（production 层）

```java
public class AgentSession {
    private final String sessionId;                    // 会话 ID（16 位 UUID）
    private final Instant createdAt;                   // 创建时间
    private Instant updatedAt;                         // 最后更新时间
    private final List<ConversationMessage> messages;  // 消息列表
    private final List<CompactionRecord> compactionHistory; // 压缩历史
    private final List<PromptEntry> promptHistory;     // Prompt 审计记录
    private String workspaceRoot;                      // 工作空间根目录
}

public record CompactionRecord(
    int count,                // 压缩次数序号
    int removedMessageCount,  // 移除消息数
    String summary,           // 摘要内容
    Instant timestamp         // 压缩时间
) {}

public record PromptEntry(Instant timestamp, String text) {}
```

#### ConversationMessage（production 层）

```java
@Builder
public record ConversationMessage(
    MessageRole role,           // 角色：SYSTEM / USER / ASSISTANT / TOOL
    List<ContentBlock> blocks,  // 内容块列表
    TokenUsage usage            // 本次消息对应的 Token 用量
) {}
```

#### ContentBlock（production 层）

```java
public sealed interface ContentBlock {
    int estimateTokens();
}

// 四种实现：
record TextBlock(String text) implements ContentBlock;
record ThinkingBlock(String thinking, String signature) implements ContentBlock;
record ToolUseBlock(String toolUseId, String toolName, String input) implements ContentBlock;
record ToolResultBlock(String toolUseId, String toolName, String output, boolean isError)
    implements ContentBlock;
```

### 3.3 包结构一览

```
com.kuoge.agentstudy
├── tutorial/                          ← 教学/简化版（对应基础 ReAct）
│   ├── ReActAgent.java                ← Agent 入口 facade
│   ├── ReActLoop.java                 ← ReAct 外循环核心
│   ├── ReActStep.java                 ← 单步记录
│   ├── Action.java                    ← 工具调用动作
│   ├── Observation.java               ← 观察结果
│   └── tool/
│       ├── Tool.java                  ← 工具接口
│       ├── ToolRegistry.java          ← 工具注册中心
│       └── ToolExecutor.java          ← 工具执行器
│
└── production/                        ← 生产级实现（对应完整 ReAct + 治理）
    ├── agent/
    │   ├── ProductionReActAgent.java  ← 生产级 Agent 入口
    │   ├── AgentLlmClient.java        ← Agent 专用 LLM 客户端适配
    │   └── AgentLlmResponse.java      ← Agent 专用响应结构
    ├── runtime/
    │   ├── core/
    │   │   ├── ConversationRuntime.java  ← 对话运行时核心
    │   │   ├── RuntimeConfig.java        ← 运行时配置
    │   │   └── TurnSummary.java          ← Turn 执行摘要
    │   ├── session/
    │   │   ├── AgentSession.java         ← 会话管理器
    │   │   ├── ConversationMessage.java  ← 结构化消息
    │   │   ├── ContentBlock.java         ← 内容块（sealed interface）
    │   │   └── MessageRole.java          ← 消息角色枚举
    │   ├── client/
    │   │   ├── LlmClient.java            ← LLM 客户端接口
    │   │   └── LlmResponse.java          ← LLM 结构化响应
    │   ├── usage/
    │   │   ├── TokenUsage.java           ← Token 用量记录
    │   │   ├── UsageTracker.java         ← 用量累积追踪
    │   │   └── CostEstimator.java        ← 成本估算
    │   ├── compact/
    │   │   ├── SessionCompactor.java     ← 会话压缩器
    │   │   ├── CompactionConfig.java     ← 压缩配置
    │   │   └── CompactionResult.java     ← 压缩结果
    │   ├── hook/
    │   │   ├── ToolHook.java             ← 工具钩子接口
    │   │   ├── HookResult.java           ← 钩子结果
    │   │   └── HookEvent.java            ← 钩子事件枚举
    │   └── permission/
    │       ├── PermissionPolicy.java     ← 权限策略引擎
    │       ├── PermissionMode.java       ← 权限模式枚举
    │       ├── PermissionOutcome.java    ← 权限结果（sealed）
    │       └── PermissionRule.java       ← 权限规则
    ├── context/
    │   ├── ContextCompressor.java        ← 9段式上下文压缩器
    │   ├── ContextSegment.java           ← 上下文段
    │   ├── SegmentType.java              ← 段类型枚举
    │   └── CompressionStrategy.java      ← 压缩策略枚举
    ├── memory/
    │   ├── PreferenceMemory.java         ← 偏好记忆系统
    │   ├── UserPreference.java           ← 用户偏好条目
    │   ├── PreferenceStore.java          ← 偏好存储接口
    │   └── InMemoryPreferenceStore.java  ← 内存存储实现
    ├── cost/
    │   ├── CostTracker.java              ← 成本追踪器
    │   ├── TokenBudget.java              ← Token 预算
    │   ├── PromptTemplateCache.java      ← Prompt 缓存
    │   └── LazyLoader.java               ← 懒加载工具
    ├── model/
    │   ├── ReActStep.java                ← 生产级单步记录
    │   ├── Action.java                   ← 生产级 Action
    │   └── Observation.java              ← 生产级 Observation
    └── tool/
        ├── Tool.java                     ← 生产级工具接口
        ├── ToolRegistry.java             ← 生产级工具注册中心
        └── ToolExecutor.java             ← 生产级工具执行器
```

### 3.4 学习路径建议

| 目标 | 从哪个类开始 | 重点看 |
|---|---|---|
| **理解 ReAct 核心循环** | `ReActLoop.run(String)` | tutorial 层，只有 60 行，无任何干扰 |
| **理解生产级消息模型** | `ConversationMessage` + `ContentBlock` | 结构化 vs 字符串的区别 |
| **理解上下文压缩** | `ContextCompressor.build()` | 9 段式压缩策略 |
| **理解权限控制** | `PermissionPolicy.authorize()` | 规则引擎的优先级设计 |
| **理解会话管理** | `AgentSession.pushMessage()` | 完整性校验 + Fork |
| **理解成本追踪** | `CostTracker` + `UsageTracker` | 精确到每次调用的记账 |

---

## 四、技术思路与实现对比

### 4.1 循环结构对比

#### Claude Code ReAct（完整 Agent 级循环）

```
用户输入: "帮我重构这个项目的日志系统"
    │
    ▼
┌─────────────────────────────────────────────────────────────────┐
│                        Agent 主循环                              │
│                                                                  │
│  第 1 轮：                                                       │
│    LLM 观察项目结构 → 思考 → 决定读取 pom.xml                    │
│        │                                                         │
│        ▼                                                         │
│    执行：读取文件 → 注入结果                                     │
│        │                                                         │
│  第 2 轮：                                                       │
│    LLM 分析依赖 → 思考 → 决定修改 logback.xml                    │
│        │                                                         │
│        ▼                                                         │
│    执行：修改文件 → 注入结果                                     │
│        │                                                         │
│  第 3 轮：                                                       │
│    LLM 决定运行测试 → 思考 → 调用终端                            │
│        │                                                         │
│        ▼                                                         │
│    执行：mvn test → 注入结果（测试失败）                         │
│        │                                                         │
│  第 4 轮：                                                       │
│    LLM 分析错误日志 → 思考 → 决定修复代码                        │
│        │                                                         │
│        ▼                                                         │
│    执行：编辑代码 → 注入结果                                     │
│        │                                                         │
│        ... 循环直到任务完成或达到最大步数 ...                     │
│                                                                  │
│  终止条件：任务完成 / 用户中断 / 最大步数(如 50) / 无法恢复错误   │
│                                                                  │
│  关键特征：                                                       │
│  • 每轮都是完整的 LLM 调用（生成 Thought + Action）              │
│  • 循环由外部 Agent 编排器驱动                                   │
│  • 需要主动管理上下文（总结历史、截断过长的对话）                 │
│  • 需要处理"环境状态"（文件系统变化、终端输出）                   │
└─────────────────────────────────────────────────────────────────┘
```

#### Spring AI ToolCallAdvisor（工具调用子循环）

```
用户输入: "帮我查一下订单 12345 的状态"
    │
    ▼
┌─────────────────────────────────────────────────────────────────┐
│                    ToolCallAdvisor 内部循环                      │
│                                                                  │
│  chatClient.prompt()                                             │
│      .system(SYSTEM_PROMPT)                                      │
│      .user("帮我查一下订单 12345 的状态")                        │
│      .tools(this)          ← 注册 @Tool 方法                    │
│      .advisors(toolCallAdvisor)  ← 启用工具调用循环             │
│      .call()                                                     │
│                                                                  │
│  内部执行过程（对调用方完全透明）：                               │
│                                                                  │
│  第 1 轮（中间态）：                                             │
│    LLM 返回 → content="", toolCalls=[{name:"queryOrderStatus"}]  │
│        │                                                         │
│        ▼                                                         │
│    ToolCallAdvisor 解析 toolCalls                                │
│        │                                                         │
│        ▼                                                         │
│    反射调用 @Tool 方法 → queryOrderStatus("12345")               │
│        │                                                         │
│        ▼                                                         │
│    获取结果 → "订单已发货，预计 3 天送达"                        │
│        │                                                         │
│        ▼                                                         │
│    将 tool_result 追加到 ChatMemory                              │
│        │                                                         │
│  第 2 轮（最终响应）：                                           │
│    LLM 再次调用 → 基于 tool_result 生成自然语言回答              │
│    返回 → "订单 12345 当前已发货，预计 3 个工作日内送达..."      │
│                                                                  │
│  终止条件：无更多 toolCalls / 达到最大循环数(默认 5)             │
│                                                                  │
│  关键特征：                                                       │
│  • 只有第 1 轮生成 toolCalls，后续轮次是"消费中间态"             │
│  • 循环由 Advisor 内部驱动，对调用方完全透明                     │
│  • 上下文由 ChatMemory 自动管理，无需应用层干预                   │
│  • 只处理"工具返回值"，不感知外部环境变化                        │
└─────────────────────────────────────────────────────────────────┘
```

### 4.2 在 ReAct 六步模型中的定位

参考 `skill-learning-and-iteration-best-practices.md` 中的 ReAct 六步模型：

```
Step 1: 组装上下文(Build Context)
Step 2: LLM 推理(LLM Reasoning)          ← 两者都有
Step 3: 解析调用(Parse Call)              ← 两者都有
Step 4: 执行工具(Execute Tool)            ← ToolCallAdvisor 主要负责这里
Step 5: 注入结果(Inject Result)           ← ToolCallAdvisor 主要负责这里
Step 6: 循环判断(Should Continue?)        ← 两者都有，但策略完全不同
```

| 步骤 | Claude Code ReAct | ToolCallAdvisor |
|---|---|---|
| **Step 1-2** | Agent 编排器负责组装上下文 + LLM 生成 Thought + Action | `ChatClient` 组装上下文 + LLM 生成 toolCalls |
| **Step 3-5** | Agent 编排器解析并执行 → 结果格式化 → 注入新消息 | **Advisor 内部完成**：解析 `@Tool` → 反射调用 → 追加到 `ChatMemory` |
| **Step 6** | Agent 判断"任务是否完成"，决定是否继续循环 | Advisor 判断"是否还有 toolCalls"，决定是否再次调用 LLM |

**关键区别**：ToolCallAdvisor 只覆盖了 Step 3-5，且是在**单次 `ChatClient.call()` 调用**的边界内完成的。Claude Code 的 ReAct 则覆盖了全部六步，且循环是**跨多次独立 LLM API 请求**的。

### 4.3 代码层面的差异

| 维度 | Claude Code ReAct | Spring AI ToolCallAdvisor |
|---|---|---|
| **循环驱动代码** | 外部 Agent 主循环（需要自己实现） | `ToolCallAdvisor.advise()` 内部封装，对调用方透明 |
| **工具注册** | 动态发现、配置文件或代码硬编码 | `@Tool` 注解 + `.tools(Object)` 注册到 `ChatClient` |
| **工具执行方式** | 直接调用系统 API、启动子进程、操作文件系统 | 反射调用 Spring Bean 中被 `@Tool` 注解的方法 |
| **结果格式化** | Agent 自行决定如何呈现给用户 | 框架自动将返回值转为 `ToolResponseMessage` |
| **上下文追加** | Agent 主动构造新的 user/assistant message | Advisor 自动将 `tool_result` 追加到 `ChatMemory` |
| **最大循环次数** | 由 Agent 配置（通常 10-50） | 由 `ToolCallAdvisor` 内部控制（默认 5 次） |
| **观察对象** | 文件系统状态、终端输出、进程返回值 | `@Tool` 方法的 Java 返回值 |
| **中断/暂停** | 支持用户中断、等待用户确认 | 不支持，在 `.call()` 返回前阻塞 |

---

## 五、两者关系：子集 vs 全集

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Claude Code 增强版 ReAct（全集）                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 1. 环境观察（Observation）                                           │   │
│  │    • 读取文件系统状态                                               │   │
│  │    • 捕获终端输出                                                   │   │
│  │    • 监控进程状态                                                   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                    ↓                                        │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 2. 自主规划（Thought/Reasoning）                                     │   │
│  │    • LLM 每轮重新推理"当前状态 → 下一步行动"                        │   │
│  │    • 可能生成详细思考过程（Chain of Thought）                       │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                    ↓                                        │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 3. 工具选择（Action Selection）                                      │   │
│  │    • 从工具集中选择最合适的工具                                     │   │
│  │    • 生成工具调用参数                                               │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                    ↓                                        │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 4. 工具执行（Action Execution）◄─────────────────────────────────┐   │   │
│  │    • 调用系统 API / 执行命令 / 操作文件                             │   │   │
│  │    • 处理超时、异常、权限问题                                       │   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │   │
│                                    ↓                                        │   │
│  ┌─────────────────────────────────────────────────────────────────────┐   │   │
│  │ 5. 结果解析与上下文管理（Observation → Context Update）            │   │   │
│  │    • 将执行结果格式化为 LLM 可理解的形式                            │   │   │
│  │    • 主动管理上下文长度（总结、截断）                               │   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │   │
│                                    ↓                                        │   │
│  ┌─────────────────────────────────────────────────────────────────────┐   │   │
│  │ 6. 终止判断（Should Continue?）                                      │   │   │
│  │    • 判断任务是否已完成                                             │   │   │
│  │    • 达到最大步数 / 用户中断 / 不可恢复错误                         │   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │   │
│                                                                             │   │
│  ┌─────────────────────────────────────────────────────────────────┐      │   │
│  │ 7. 用户交互（可选）                                               │      │   │
│  │    • 请求用户确认关键操作                                         │      │   │
│  │    • 展示 diff / 询问是否应用                                     │      │   │
│  └─────────────────────────────────────────────────────────────────┘      │   │
│                                                                             │   │
└─────────────────────────────────────────────────────────────────────────────┘   │
                                                                                   │
                              ToolCallAdvisor 覆盖了这里 ──────────────────────────┘
                              （Step 4-5 的部分功能）
```

**ToolCallAdvisor 是 ReAct 循环中的一个「内环组件」**，它负责：
1. 检测 LLM 响应中的 `toolCalls`
2. 解析工具名和参数
3. 反射调用对应的 `@Tool` 方法
4. 将返回值格式化为 `ToolResponseMessage`
5. 追加到对话历史
6. 再次调用 LLM 获取最终回答

但它**不负责**：
- 决定"任务是否完成"
- 管理长程任务的上下文（它只管理单次请求内的上下文）
- 与外部操作系统/文件系统交互（只与 Spring Bean 交互）
- 自主规划多步骤任务

---

## 六、具体看你们项目中的 ToolCallAdvisor

### 6.1 配置方式

```java
// AgentConfig.java
@Bean
public ToolCallAdvisor toolCallAdvisor() {
    return ToolCallAdvisor.builder()
            .toolCallingManager(ToolCallingManager.builder().build())
            .build();
}
```

### 6.2 使用方式（CustomerServiceAgent）

```java
// CustomerServiceAgent.java
ChatClient.ChatClientRequestSpec buildRequestWithoutRAG(final AgentContext context) {
    return chatClient.prompt()
            .system(SYSTEM_PROMPT)
            .user(context.instruction())
            .tools(this)              // ← 注册当前对象中的 @Tool 方法
            .advisors(toolCallAdvisor) // ← ToolCallAdvisor 驱动 tool-call loop
            .advisors(spec -> spec
                    .param(ChatMemory.CONVERSATION_ID, context.sessionId())
                    .param("scene", SCENE)
                    .param("userId", context.userId()));
}
```

代码注释已经清晰描述了其工作方式：

> **第1轮**：LLM 返回 `content="", toolCalls=[...]`（中间态）  
> **ToolCallAdvisor**：调用对应的 `@Tool` 方法，获取结果  
> **第2轮**：将工具结果追回历史，LLM 生成最终文本响应

这个过程**只涉及 1-2 轮 LLM 调用**，且完全封装在 `.call()` 的一次阻塞调用内。

### 6.3 Advisor 链中的位置

```
Advisor 链执行顺序（order 升序 = 从外到内）：

  ToolCallAdvisor          (HIGHEST_PRECEDENCE + 300)   ← 最外层，驱动工具循环
  MessageChatMemoryAdvisor  (HIGHEST_PRECEDENCE + 1000)  ← 管理会话历史
  RetrievalAugmentationAdvisor (0)                        ← RAG 知识检索
    → [SceneFilter → Rewrite → Retrieve → Rerank → ConfidenceFilter]
  FallbackShortCircuitAdvisor (某值)                      ← 低置信度短路
```

ToolCallAdvisor 位于**最外层**，意味着：
- 它可以观察到其他 Advisor 的处理结果（包括 RAG 检索到的知识）
- 当 LLM 决定调用工具时，它负责实际执行
- 当内层 FallbackShortCircuitAdvisor 短路时，其兜底响应无 `toolCalls`，ToolCallAdvisor 的 loop 自然退出

---

## 七、对 AI Agent 系统架构选型的影响

### 7.1 当前架构适合什么

你们当前的 `AgentOrchestrator` 是**单次调用**模式：

```java
public AgentResponse process(AgentRequest request) {
    Agent agent = resolveAgent(request);
    AgentContext context = buildContext(request);
    return agent.execute(context);  // ← 单次执行，无循环
}
```

这个模式 + ToolCallAdvisor 完全适合以下场景：

| 场景 | 说明 | 代表 Agent |
|---|---|---|
| **信息查询型** | 用户提问 → LLM 判断需要查工具 → 查完回答 | CustomerServiceAgent（查订单、查政策） |
| **检索生成型** | 用户提问 → RAG 检索知识 → LLM 生成回答 | ContentAgent（生成营销文案） |
| **简单操作型** | 用户请求 → 调用 1-2 个工具 → 返回结果 | ProductSearchAgent（搜索商品） |

### 7.2 什么时候需要自建 ReAct 外层循环

当遇到以下场景时，**需要在 Agent 内部或 Orchestrator 层增加显式的 ReAct 外层循环**，ToolCallAdvisor 不够：

| 场景 | 为什么 ToolCallAdvisor 不够 | 需要什么 |
|---|---|---|
| **多步骤任务** | 需要 LLM 每步重新规划，而非一次性决定所有工具调用 | Agent 内部 while 循环，每轮重新调用 LLM |
| **条件分支** | 第 2 步的结果决定第 3 步做什么 | 动态构建 prompt，根据上一步结果调整下一步 |
| **长上下文任务** | 多轮后上下文溢出，需要主动总结/截断 | 上下文管理策略（如滑动窗口、关键信息提取） |
| **环境状态依赖** | 需要根据外部系统状态变化调整策略 | 每轮重新观察环境状态 |
| **用户交互穿插** | 执行中需要向用户确认关键操作 | 支持中断/暂停/恢复的 Agent 状态机 |

### 7.3 如果未来需要 ReAct 外层循环的实现思路

```java
/**
 * 长程任务 Agent 的 ReAct 外层循环示例。
 * 
 * 这与 ToolCallAdvisor 的单次调用模式不同：
 * - ToolCallAdvisor：在 .call() 内部循环 1-2 轮
 * - ReAct Agent：在 .call() 外部循环 N 轮
 */
@Service
public class LongHorizonAgent implements Agent {

    private final ChatClient chatClient;
    private final List<ToolCallback> tools;
    private static final int MAX_STEPS = 20;

    @Override
    public AgentResponse execute(AgentContext context) {
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage("你是自动化运营专家..."));
        history.add(new UserMessage(context.instruction()));

        for (int step = 0; step < MAX_STEPS; step++) {
            // 每轮都是独立的 LLM 调用（不是 ToolCallAdvisor 的内循环）
            ChatResponse response = chatClient.prompt()
                    .messages(history)
                    .tools(tools)  // 注册工具供 LLM 选择
                    .call()
                    .chatResponse();

            AssistantMessage assistantMsg = response.getResult().getOutput();
            history.add(assistantMsg);

            // 检查是否生成了 toolCalls
            if (assistantMsg.getToolCalls() == null || assistantMsg.getToolCalls().isEmpty()) {
                // 没有工具调用 = 任务完成，直接返回
                return AgentResponse.success(context.sessionId(), assistantMsg.getText());
            }

            // 手动执行工具（这里不用 ToolCallAdvisor，自己控制）
            for (ToolCall toolCall : assistantMsg.getToolCalls()) {
                String result = executeTool(toolCall);
                history.add(new ToolResponseMessage(
                    List.of(new ToolResponseMessage.ToolResponse(toolCall.id(), result)),
                    Map.of()
                ));
            }

            // 每 5 轮主动总结上下文，防止溢出
            if (step > 0 && step % 5 == 0) {
                history = summarizeContext(history);
            }
        }

        return AgentResponse.success(context.sessionId(), "任务执行达到最大步数，请检查中间结果。");
    }
}
```

**关键设计决策**：

| 方案 | 适用场景 | 复杂度 |
|---|---|---|
| **纯 ToolCallAdvisor**（当前） | 单次/少数工具调用，信息获取型任务 | 低 |
| **ToolCallAdvisor + 代码级编排** | 固定多步骤流程（如比价：搜索→抓取→匹配→聚合） | 中 |
| **自建 ReAct 外层循环** | 动态多步骤任务（如"帮我排查 bug"） | 高 |
| **LangGraph / 工作流引擎** | 复杂业务流程（状态机、分支、并行） | 高 |

---

## 八、总结

| 问题 | 答案 |
|---|---|
| **解决的问题域是否一样？** | **不一样**。Claude Code ReAct 解决"复杂长程任务自主执行"，ToolCallAdvisor 解决"单次请求中的工具调用编排"。 |
| **技术思路和实现是否类似？** | **结构相似，层次不同**。两者都有"LLM 推理 → 工具调用 → 执行 → 结果回注"的循环，但 Claude Code ReAct 是**Agent 级**的完整架构，ToolCallAdvisor 是**Advisor 级**的内环组件。 |
| **ToolCallAdvisor 能替代 ReAct 吗？** | **不能**。ToolCallAdvisor 只覆盖了 ReAct 中的"工具执行"子环节。长程任务需要在 Agent/Orchestrator 层自建外层循环。 |
| **ReAct 能替代 ToolCallAdvisor 吗？** | **可以但没必要**。简单的工具调用场景用 ToolCallAdvisor 更轻量、更可靠。ReAct 是更重的方案，只在需要长程规划时使用。 |
| **对你们的建议** | 当前**保持 ToolCallAdvisor**，它完美覆盖了客服、搜索、比价等场景。如果未来出现需要多步骤自主规划的业务（如"自动化营销campaign"），再在特定 Agent 中引入 ReAct 外层循环，ToolCallAdvisor 继续作为内层工具执行器复用。 |

---

> **一句话记忆**：ToolCallAdvisor 是 ReAct 循环的「**内燃机**」——它负责把工具调用这个核心动作跑起来；但 ReAct 是「**整车**」——还包含方向盘（规划）、导航（决策）、刹车（终止）等完整系统。ToolCallAdvisor 可以作为 ReAct 的内核使用，但 ReAct 不能降级为 ToolCallAdvisor。
