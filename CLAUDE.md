# Agent Study — 持久化上下文

> **项目定位**：个人学习实践项目，用于深入理解 AI Agent / Skill / ReAct 的设计原理和代码实现。
> **与公司项目的关系**：基于公司项目 `psylos1/psylos-agent`（Spring AI + JDK 21 + Qwen-plus）的技术栈，提取核心概念做简化实现，便于源码级学习和 debug。

---

## 一、项目背景

### 1.1 为什么创建这个项目

在公司项目 `psylos-agent` 中，我们正在建设一套面向跨境电商的自然流量增长 AI Agent 系统，包含 13 个 Agent（商品搜索、SEO 内容生成、社媒运营、KOL 挖掘等）。随着系统复杂度增加，需要建立 **Skill 治理体系**（skill-admin-backend）来管理 Skill 的沉淀迭代和 ROI 归因。

在学习过程中，发现以下概念容易混淆，需要专门的学习项目来动手实践：

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
Phase 3: 对比学习（对比 ReActLoop 和 ToolCallAdvisor 的实现差异）
    ↓
Phase 4: 迁移到项目（将学到的设计应用到 psylos-agent 的具体 Skill 中）
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
├── docs/                                      # 学习文档（从公司项目转移）
│   ├── skill-learning-and-iteration-best-practices.md
│   ├── skill-governance-and-roi-attribution-design.md
│   └── react-loop-vs-toolcalladvisor-comparison.md
├── src/
│   ├── main/
│   │   ├── java/com/psylos/agentstudy/
│   │   │   ├── AgentStudyApplication.java     # Spring Boot 入口
│   │   │   ├── react/                         # 教学版 ReAct【基础学习】
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
│   │   └── resources/
│   │       └── application.yml                # 配置文件
│   └── test/
│       └── java/com/psylos/agentstudy/
│           ├── react/
│           │   └── ReActLoopTest.java         # ⭐ 教学版测试（7 个场景）
│           └── production/
│               └── ProductionAgentTest.java   # ⭐ 生产级测试（11 个场景）
```

### 4.1 学习重点文件（按优先级排序）

#### Phase 1：教学版 ReAct（先理解核心机制）

| 优先级 | 文件 | 学习目标 |
|---|---|---|
| P0 | `ReActLoop.java` | 理解 ReAct 外循环的六步模型 |
| P0 | `ReActLoopTest.java` | 通过 7 个测试场景理解不同行为 |
| P1 | `react-loop-vs-toolcalladvisor-comparison.md` | 对比理解外循环 vs 内循环 |

#### Phase 2：生产级实现（再理解工程化增强）

| 优先级 | 文件 | 学习目标 |
|---|---|---|
| P0 | `PreferenceMemory.java` | **为什么只记偏好不记事实** + 置信度机制 + 冲突处理 |
| P0 | `ContextCompressor.java` | **9段式结构化压缩**的设计原理和实现 |
| P1 | `ProductionReActAgent.java` | 如何把记忆、压缩、成本三个模块整合到 Agent 中 |
| P1 | `ProductionAgentTest.java` | 11 个测试场景覆盖三个核心模块 |
| P2 | `PromptTemplateCache.java` + `LazyLoader.java` | 成本优化策略 |

---

## 五、快速开始

### 5.1 运行单元测试（无需真实 LLM）

```bash
cd /Users/liq/work/idea/ai/agent-study

# 教学版 ReAct 测试（7 个场景）
mvn test -Dtest=ReActLoopTest

# 生产级实现测试（11 个场景）
mvn test -Dtest=ProductionAgentTest

# 全部测试
mvn test
```

所有测试使用 Mock LLM 客户端，**无需配置 API Key**。

### 5.2 单步 debug 建议

#### 教学版 ReAct

在 `ReActLoopTest.testMultiStepToolUse()` 中设置断点，观察：

1. `ReActLoop.run()` 的 `for` 循环如何执行多轮
2. 每轮 `llmClient.call(context)` 的 `context` 如何累积
3. `toolExecutor.execute(action)` 如何将 Observation 注入上下文
4. `steps` 列表如何记录完整的执行轨迹

#### 生产级实现

在 `ProductionAgentTest.testProductionAgentEndToEnd()` 中设置断点，观察：

1. `ContextCompressor.build()` 如何按 9 段优先级压缩
2. `PreferenceMemory.buildCorePreferencePrompt()` 如何筛选高置信度偏好
3. `TokenBudget` 如何随步骤消耗累积
4. `CostTracker` 如何记录每次 LLM 调用和工具调用

### 5.3 对比 ToolCallAdvisor

在公司项目 `psylos-agent` 中打开 `CustomerServiceAgent.java`，对比：

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

---

## 七、与公司项目 psylos-agent 的映射

| 学习项目 | 公司项目 | 说明 |
|---|---|---|
| `ReActLoop` | `AgentOrchestrator` + `Agent.execute()` | 公司项目是单次调用模式，未来长程任务需要引入 ReAct 外循环 |
| `ToolRegistry` | `AgentRegistry` | 公司项目注册的是 Agent，学习项目注册的是 Tool |
| `ToolExecutor` | `ToolCallAdvisor` | 公司项目由 Spring AI 框架自动执行，学习项目是手动实现便于理解 |
| `ReActStep` | 执行日志 | 公司项目通过 `SkillExecutionReporter` 上报，学习项目保存在内存中 |

---

## 八、待探索话题（TODO）

- [ ] 接入真实的 Spring AI ChatClient（OpenAI / DashScope）
- [ ] 实现 LLM 响应解析器（从自然语言中提取 Thought + Action）
- [ ] 添加上下文压缩策略（滑动窗口 / 关键信息摘要）
- [ ] 实现用户交互中断（长程任务中请求用户确认）
- [ ] 对比 LangGraph 的 StateGraph 实现
- [ ] 将学到的 ReAct 设计应用到 `price_comparison` Skill 中

---

> **最后更新**：2025-05-15
> **维护者**：个人学习项目，由 AI 辅助创建
