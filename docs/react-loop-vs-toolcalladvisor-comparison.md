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

## 二、技术思路与实现对比

### 2.1 循环结构对比

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

### 2.2 在 ReAct 六步模型中的定位

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

### 2.3 代码层面的差异

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

## 三、两者关系：子集 vs 全集

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

## 四、具体看你们项目中的 ToolCallAdvisor

### 4.1 配置方式

```java
// AgentConfig.java
@Bean
public ToolCallAdvisor toolCallAdvisor() {
    return ToolCallAdvisor.builder()
            .toolCallingManager(ToolCallingManager.builder().build())
            .build();
}
```

### 4.2 使用方式（CustomerServiceAgent）

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

### 4.3 Advisor 链中的位置

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

## 五、对 AI Agent 系统架构选型的影响

### 5.1 当前架构适合什么

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

### 5.2 什么时候需要自建 ReAct 外层循环

当遇到以下场景时，**需要在 Agent 内部或 Orchestrator 层增加显式的 ReAct 外层循环**，ToolCallAdvisor 不够：

| 场景 | 为什么 ToolCallAdvisor 不够 | 需要什么 |
|---|---|---|
| **多步骤任务** | 需要 LLM 每步重新规划，而非一次性决定所有工具调用 | Agent 内部 while 循环，每轮重新调用 LLM |
| **条件分支** | 第 2 步的结果决定第 3 步做什么 | 动态构建 prompt，根据上一步结果调整下一步 |
| **长上下文任务** | 多轮后上下文溢出，需要主动总结/截断 | 上下文管理策略（如滑动窗口、关键信息提取） |
| **环境状态依赖** | 需要根据外部系统状态变化调整策略 | 每轮重新观察环境状态 |
| **用户交互穿插** | 执行中需要向用户确认关键操作 | 支持中断/暂停/恢复的 Agent 状态机 |

### 5.3 如果未来需要 ReAct 外层循环的实现思路

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

## 六、总结

| 问题 | 答案 |
|---|---|
| **解决的问题域是否一样？** | **不一样**。Claude Code ReAct 解决"复杂长程任务自主执行"，ToolCallAdvisor 解决"单次请求中的工具调用编排"。 |
| **技术思路和实现是否类似？** | **结构相似，层次不同**。两者都有"LLM 推理 → 工具调用 → 执行 → 结果回注"的循环，但 Claude Code ReAct 是**Agent 级**的完整架构，ToolCallAdvisor 是**Advisor 级**的内环组件。 |
| **ToolCallAdvisor 能替代 ReAct 吗？** | **不能**。ToolCallAdvisor 只覆盖了 ReAct 中的"工具执行"子环节。长程任务需要在 Agent/Orchestrator 层自建外层循环。 |
| **ReAct 能替代 ToolCallAdvisor 吗？** | **可以但没必要**。简单的工具调用场景用 ToolCallAdvisor 更轻量、更可靠。ReAct 是更重的方案，只在需要长程规划时使用。 |
| **对你们的建议** | 当前**保持 ToolCallAdvisor**，它完美覆盖了客服、搜索、比价等场景。如果未来出现需要多步骤自主规划的业务（如"自动化营销campaign"），再在特定 Agent 中引入 ReAct 外层循环，ToolCallAdvisor 继续作为内层工具执行器复用。 |

---

> **一句话记忆**：ToolCallAdvisor 是 ReAct 循环的「**内燃机**」——它负责把工具调用这个核心动作跑起来；但 ReAct 是「**整车**」——还包含方向盘（规划）、导航（决策）、刹车（终止）等完整系统。ToolCallAdvisor 可以作为 ReAct 的内核使用，但 ReAct 不能降级为 ToolCallAdvisor。
