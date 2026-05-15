# Skill 学习与优化迭代：业界最佳实践与 Claude 架构深度解析

> **目标**：梳理 AI Skill（Agent Capability）在业界的最佳实践，以 **Claude（Anthropic）** 为核心案例，深度拆解其设计哲学、架构模型、执行循环与迭代方法论。结合 Java/Spring AI 技术栈给出可落地的代码示例。
>
> **参考来源**：Anthropic 官方技术博客（Building effective agents, 2024）、Tool Use 文档、Computer Use 技术报告、MCP 协议规范。
>
> ⚠️ **重要前置说明**：本文中的 "Skill" 包含**三个层次**，请先阅读下方的「概念澄清」章节，避免后续阅读混淆。

---

## ⚠️ 前置概念澄清：Skill 的分层定义

在 AI Agent 工程实践中，"Skill" 这个词在不同语境下指向完全不同的东西。本文涉及三个层次，必须先拆清楚：

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Skill 的三层定义                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 第一层：Skill SOP（规范/沉淀层）← 你日常说的 "Skill"                │   │
│  │ ─────────────────────────────────────────────────────────────────── │   │
│  │ • 形式：.claude/skills/xxx/SKILL.md（Markdown 文件）                │   │
│  │ • 内容：操作手册、判定标准、SOP、迭代路线图、反爬策略                │   │
│  │ • 读者：AI Agent / 开发者                                           │   │
│  │ • 例子：brand-official-website-detection/SKILL.md                   │   │
│  │         （六维评分模型、快速探测→深度探测流程、平台抓取策略）        │   │
│  │ • 本质：「知识资产」—— 沉淀下来的行业经验和操作规范                  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                    ↓                                        │
│                           指导实现 / 被代码化                               │
│                                    ↓                                        │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 第二层：Skill Runtime（运行时层）← 本文「架构/代码」部分讲的       │   │
│  │ ─────────────────────────────────────────────────────────────────── │   │
│  │ • 形式：Java Agent 代码（Spring AI ChatClient + @Tool + RAG）       │   │
│  │ • 内容：Prompt 模板、Tool 定义、编排逻辑、上下文管理、API 调用      │   │
│  │ • 读者：机器执行                                                    │   │
│  │ • 例子：OfficialWebsiteDetector.java                                │   │
│  │         （六维评分的代码实现、HTTP 探测、无头浏览器调用）            │   │
│  │ • 本质：「可执行程序」—— 把 SOP 翻译成机器能跑的代码                 │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                    ↓                                        │
│                           被管理 / 被观测                                   │
│                                    ↓                                        │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 第三层：Skill Governance（治理层）← skill-admin-backend 负责       │   │
│  │ ─────────────────────────────────────────────────────────────────── │   │
│  │ • 形式：Web 后台 + 配置中心 + 数据库 + 看板                          │   │
│  │ • 内容：版本管理、效果看板、Eval 套件、A/B Test、ROI 归因            │   │
│  │ • 读者：运营人员 / 技术负责人                                         │   │
│  │ • 例子：SkillRegistry、Prompt 版本历史、比价 Skill ROI 看板         │   │
│  │ • 本质：「管理系统」—— 让人能管理成百上千个 Skill 全生命周期         │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  一句话区分三层：                                                           │
│  • SOP 层回答「这个 Skill 应该怎么做」（.md 手册）                        │
│  • Runtime 层回答「这个 Skill 怎么跑起来」（Java 代码）                   │
│  • Governance 层回答「这个 Skill 跑得好不好、要不要升级」（后台系统）    │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 三层之间的联动关系

```
SOP 层 (.md)                    Runtime 层 (Java)              Governance 层 (Admin)
    │                                │                                │
    │ ① 沉淀行业经验                  │ ② 将 SOP 翻译为代码            │ ③ 管理全生命周期
    │    六维评分规则                 │    实现六维评分的类和方法      │    版本、灰度、回滚
    │    平台反爬策略                 │    配置代理和重试逻辑          │    Eval 执行与报告
    │    识别边界 case                │    处理边界 case 的代码分支    │    ROI 归因看板
    │                                │                                │
    │◄──────── ⑤ 反哺更新 ──────────│                                │
    │    新发现的 bad case            │                                │
    │    写入 SOP 的「迭代路线图」    │                                │
    │                                │                                │
    │◄───────────────────────────────┼──────── ④ 效果数据回流 ────────│
         根据 Governance 的分析      │         成功率、ROI、用户反馈   │
         更新 SOP 中的最佳实践       │         驱动 SOP 和代码迭代    │
```

**为什么本文会把三层混在一起讲？**

因为业界（尤其是 Anthropic）讨论 "Skill Design" 时，通常默认指的是 **Runtime 层的设计**（Prompt + Tool + 编排）。但在你们的项目中，`.claude/skills/` 目录下的 `.md` 文件是第一层（SOP）。

本文后续章节的标注约定：
- **【SOP 层】**：讨论 `.md` 技能手册的写法、SOP 的沉淀方法
- **【Runtime 层】**：讨论 Java 代码架构、Claude 的三层模型、ReAct Loop、Tool Use
- **【Governance 层】**：讨论版本管理、Eval 框架、A/B Test、ROI 归因

---

## 一、领域模型

> 本节从领域驱动设计（DDD）视角，建立 Skill 全生命周期涉及的核心模型、关联关系与核心字段，为后续 Runtime 实现与 Governance 治理提供统一语言（Ubiquitous Language）。

### 1.1 核心模型一览

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Skill 全生命周期领域模型                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   ┌─────────────────────────────────────────────────────────────────────┐  │
│   │                        SkillRegistry                               │  │
│   │                      (Skill 注册中心)                               │  │
│   │                                                                    │  │
│   │   ┌──────────┐  1:N  ┌──────────┐  1:1  ┌──────────┐             │  │
│   │   │  Skill   │◄─────│SkillVersion│◄────│PromptTemplate│          │  │
│   │   │ (技能根) │       │ (版本)    │       │(Prompt模板)│          │  │
│   │   └────┬─────┘       └──────────┘       └──────────┘             │  │
│   │        │                                                          │  │
│   │        │ 1:1              1:1              1:1                    │  │
│   │        ▼                 ▼                 ▼                      │  │
│   │   ┌──────────┐      ┌──────────┐      ┌──────────┐               │  │
│   │   │ SkillSOP │      │SkillRuntime│     │SkillGovernance│          │  │
│   │   │ (SOP层)  │      │(运行时层)  │      │  (治理层)    │          │  │
│   │   └──────────┘      └────┬─────┘      └────┬─────┘               │  │
│   │                          │                 │                      │  │
│   │                          │ 1:N             │ 1:N                  │  │
│   │                          ▼                 ▼                      │  │
│   │                    ┌──────────┐      ┌──────────┐                │  │
│   │                    │ToolDefinition│  │ Deployment │               │  │
│   │                    │(工具定义)  │      │  (部署记录) │               │  │
│   │                    └────┬─────┘      └──────────┘                │  │
│   │                         │                                         │  │
│   │                         │ 1:N                                     │  │
│   │                         ▼                                         │  │
│   │                   ┌──────────┐                                    │  │
│   │                   │ EvalSuite │                                   │  │
│   │                   │(评估套件) │                                   │  │
│   │                   └────┬─────┘                                    │  │
│   │                        │                                          │  │
│   │                        │ 1:N                                      │  │
│   │                        ▼                                          │  │
│   │                  ┌──────────┐     1:N    ┌──────────┐            │  │
│   │                  │ EvalCase │◄───────────│EvalResult│            │  │
│   │                  │(评估用例)│             │(评估结果)│            │  │
│   │                  └──────────┘             └──────────┘            │  │
│   └─────────────────────────────────────────────────────────────────────┘  │
│                                    ▲                                      │
│                                    │ 1:N                                   │
│                              ┌──────────┐                                 │
│                              │ Feedback │                                 │
│                              │ (反馈)   │                                 │
│                              └──────────┘                                 │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.2 模型关联关系

| 关联双方 | 关系类型 | 说明 |
|---|---|---|
| `SkillRegistry` → `Skill` | **1:N** | 注册中心管理多个 Skill，提供统一发现与路由能力 |
| `Skill` → `SkillSOP` | **1:1** | 每个 Skill 对应一份 SOP 手册，描述"应该怎么做" |
| `Skill` → `SkillRuntime` | **1:1** | 每个 Skill 对应一份运行时实现，描述"怎么跑起来" |
| `Skill` → `SkillGovernance` | **1:1** | 每个 Skill 对应一套治理配置，描述"跑得好不好" |
| `Skill` → `SkillVersion` | **1:N** | 一个 Skill 可存在多个不可变版本，支持回滚与 A/B Test |
| `SkillVersion` → `PromptTemplate` | **1:1** | 每个版本绑定一个 Prompt 模板，与代码分离存储 |
| `SkillRuntime` → `ToolDefinition` | **1:N** | 运行时依赖多个工具定义（对应 `@Tool` 方法集合） |
| `SkillRuntime` → `EvalSuite` | **1:N** | 一个 Skill Runtime 可配套多组 EvalSuite（单元/集成） |
| `EvalSuite` → `EvalCase` | **1:N** | 评估套件由多条测试用例组成 |
| `EvalCase` → `EvalResult` | **1:N** | 同一用例在不同版本/时间执行产生多条结果记录 |
| `Skill` → `Feedback` | **1:N** | 一个 Skill 可收集多条隐式或显式反馈 |
| `SkillGovernance` → `Deployment` | **1:N** | 治理层记录每次部署/灰度/回滚操作 |

### 1.3 核心模型字段

#### Skill（技能根实体）

| 字段 | 类型 | 说明 |
|---|---|---|
| `skillId` | String | Skill 唯一标识，如 `product-search` / `price-comparison` |
| `skillName` | String | 可读名称，如"商品自动比价" |
| `domain` | String | 所属业务域，如 `ECOMMERCE` / `CUSTOMER_SERVICE` |
| `owner` | String | 负责人（工号或邮箱） |
| `status` | Enum | 生命周期状态：`DRAFT` / `ACTIVE` / `DEPRECATED` / `ARCHIVED` |
| `currentVersion` | String | 当前生效版本号 |
| `createdAt` | Instant | 创建时间 |
| `updatedAt` | Instant | 最后更新时间 |

#### SkillSOP（规范/沉淀层）

| 字段 | 类型 | 说明 |
|---|---|---|
| `sopId` | String | SOP 文档 ID |
| `skillId` | String | 关联 Skill |
| `documentPath` | String | Markdown 文件路径，如 `.claude/skills/xxx/SKILL.md` |
| `scoringDimensions` | List&lt;String&gt; | 评分维度（如六维评分模型的维度定义） |
| `platformStrategies` | Map | 平台矩阵与反爬策略（Key: 平台名, Value: 策略配置） |
| `iterationRoadmap` | List&lt;String&gt; | 迭代路线图中记录的待优化项 |
| `boundaryCases` | List&lt;String&gt; | 已识别的边界 case 清单 |
| `version` | String | SOP 文档版本，与 SkillVersion 独立演进 |

#### SkillRuntime（运行时层）

| 字段 | 类型 | 说明 |
|---|---|---|
| `runtimeId` | String | Runtime 实例标识 |
| `skillId` | String | 关联 Skill |
| `implClass` | String | 实现类全限定名，如 `com.xxx.agent.skill.ProductSearchSkill` |
| `chatClientConfig` | Map | ChatClient 配置（temperature、model、maxTokens 等） |
| `toolBeans` | List&lt;String&gt; | 注册的 Tool Bean 名称列表 |
| `advisorChain` | List&lt;AdvisorConfig&gt; | Advisor 链配置（含 order、参数） |
| `contextEngineering` | ContextConfig | 上下文工程配置（历史长度、压缩策略、RAG 注入方式） |
| `maxIterations` | int | ReAct 最大循环步数 |
| `timeoutMs` | long | 单次调用超时 |

#### SkillGovernance（治理层）

| 字段 | 类型 | 说明 |
|---|---|---|
| `governanceId` | String | 治理配置 ID |
| `skillId` | String | 关联 Skill |
| `evalTrigger` | Enum | Eval 触发策略：`ON_DEPLOY` / `SCHEDULED` / `MANUAL` |
| `successRateThreshold` | double | 成功率告警阈值（如 0.85） |
| `latencyThresholdMs` | long | 延迟告警阈值（如 3000ms） |
| `feedbackCollection` | boolean | 是否收集用户显式反馈（👍/👎） |
| `alertChannels` | List&lt;String&gt; | 告警通道（钉钉/邮件/企业微信） |

#### SkillVersion（版本）

| 字段 | 类型 | 说明 |
|---|---|---|
| `versionId` | String | 版本号，遵循 SemVer，如 `1.2.3` |
| `skillId` | String | 关联 Skill |
| `promptTemplateId` | String | 绑定的 PromptTemplate ID |
| `runtimeConfig` | Map&lt;String, Object&gt; | 运行时参数覆盖（与基线合并） |
| `status` | Enum | 版本状态：`INACTIVE` / `GRAYSCALE` / `ACTIVE` / `DEPRECATED` |
| `grayscalePercent` | int | 灰度百分比（0-100） |
| `activatedAt` | Instant | 激活时间 |
| `createdBy` | String | 版本创建人 |

#### PromptTemplate（Prompt 模板）

| 字段 | 类型 | 说明 |
|---|---|---|
| `templateId` | String | 模板唯一 ID |
| `skillId` | String | 关联 Skill |
| `templateType` | Enum | 类型：`SYSTEM` / `USER` / `TOOL_DESCRIPTION` / `FEW_SHOT` |
| `content` | String | 模板内容（支持占位符如 `{{userQuery}}`） |
| `variables` | List&lt;String&gt; | 模板变量列表 |
| `version` | String | 模板自身版本 |

#### ToolDefinition（工具定义）

| 字段 | 类型 | 说明 |
|---|---|---|
| `toolName` | String | 工具名称，全局唯一 |
| `skillId` | String | 所属 Skill |
| `description` | String | 工具描述（**决定模型 tool choice 质量的关键字段**） |
| `parameterSchema` | JSON Schema | 参数 JSON Schema |
| `returnType` | String | 返回值类型全限定名 |
| `targetBeanClass` | String | 目标 Bean 类名 |
| `targetMethod` | String | 目标方法名 |
| `timeoutMs` | long | 执行超时 |
| `retryPolicy` | RetryConfig | 重试策略（次数、退避算法） |

#### EvalSuite（评估套件）

| 字段 | 类型 | 说明 |
|---|---|---|
| `suiteId` | String | 套件 ID |
| `skillId` | String | 关联 Skill |
| `suiteName` | String | 套件名称，如 `ProductSearch_IntentRecognition` |
| `evalType` | Enum | 类型：`UNIT`（单元） / `INTEGRATION`（集成） / `E2E`（端到端） |
| `cases` | List&lt;EvalCase&gt; | 用例列表 |
| `evaluatorClass` | String | 评估器实现类 |

#### EvalCase（评估用例）

| 字段 | 类型 | 说明 |
|---|---|---|
| `caseId` | String | 用例唯一 ID |
| `suiteId` | String | 所属套件 |
| `description` | String | 用例描述，如 "Colorway 黑话理解 - bred" |
| `input` | Object | 输入数据（类型由 Skill 决定） |
| `expected` | Object | 期望输出 |
| `difficulty` | Enum | 难度：`EASY` / `MEDIUM` / `HARD` |
| `category` | String | 类别标签，如 `colorway` / `brand_alias` / `edge_case` |
| `metadata` | Map | 扩展元数据 |

#### EvalResult（评估结果）

| 字段 | 类型 | 说明 |
|---|---|---|
| `resultId` | String | 结果 ID |
| `caseId` | String | 关联用例 |
| `skillVersion` | String | 被测 Skill 版本 |
| `passed` | boolean | 是否通过 |
| `score` | double | 得分（0.0 - 1.0） |
| `actualOutput` | String | 实际输出 |
| `failureReason` | String | 失败原因 |
| `executedAt` | Instant | 执行时间 |

#### Feedback（反馈）

| 字段 | 类型 | 说明 |
|---|---|---|
| `feedbackId` | String | 反馈 ID |
| `skillId` | String | 关联 Skill |
| `sessionId` | String | 关联会话 |
| `feedbackType` | Enum | 类型：`IMPLICIT`（隐式） / `EXPLICIT`（显式） |
| `channel` | Enum | 通道：`THUMB`（👍/👎） / `BAD_CASE`（标注） / `AUTO_METRIC`（自动指标） |
| `content` | String | 反馈内容 |
| `score` | Integer | 评分（如 1-5 星，或 👍=1 / 👎=-1） |
| `createdAt` | Instant | 反馈时间 |

#### Deployment（部署记录）

| 字段 | 类型 | 说明 |
|---|---|---|
| `deploymentId` | String | 部署 ID |
| `skillId` | String | 关联 Skill |
| `version` | String | 部署版本 |
| `action` | Enum | 操作：`DEPLOY` / `GRAYSCALE` / `ROLLBACK` / `DEPRECATE` |
| `operator` | String | 操作人 |
| `previousVersion` | String | 回滚前的上一个版本 |
| `executedAt` | Instant | 操作时间 |

### 1.4 三层映射关系速查

| 概念/操作 | SOP 层 | Runtime 层 | Governance 层 |
|---|---|---|---|
| **做什么** | 写 `.md` 手册 | 写 Java 代码 + Prompt | 配置后台 + 看板 |
| **核心模型** | `SkillSOP` | `SkillRuntime` + `ToolDefinition` | `SkillGovernance` + `Deployment` |
| **版本管理** | SOP 文档版本 | SkillVersion + PromptTemplate | 灰度/全量/回滚策略 |
| **质量验证** | 人工 Review 手册 | EvalSuite + EvalCase | 成功率/延迟/ROI 看板 |
| **反馈入口** | 更新迭代路线图 | 修复代码 + 调优 Prompt | 收集并分析用户反馈 |

---

## 二、设计模型

> 本节将「领域模型」中的抽象概念映射到本项目代码中的具体类与包，明确哪些模型已有代码实现、哪些还停留在文档/SOP 层。便于读者按图索骥直接阅读源码。

### 2.1 实现状态总览

```
领域模型                实现状态            代码位置（如有）
─────────────────────────────────────────────────────────────────
Skill                    ✅ 已实现         production.skill.Skill
SkillSOP                 ⚠️ 半实现         本文档 + production.skill.sop.SkillSop
SkillRuntime             ✅ 已实现         production.skill.runtime.SkillRuntime
SkillGovernance          ✅ 已实现         production.skill.governance.SkillGovernance
SkillVersion             ✅ 已实现         production.skill.governance.SkillVersion
PromptTemplate           ⚠️ 半实现         PromptTemplateCache（缓存层）
ToolDefinition           ✅ 已实现         production.tool.Tool + ToolRegistry
EvalSuite                ✅ 已实现         production.skill.eval.EvalSuite
EvalCase                 ✅ 已实现         production.skill.eval.EvalCase
EvalResult               ✅ 已实现         production.skill.eval.EvalResult
Feedback                 ✅ 已实现         production.skill.governance.Feedback
Deployment               ✅ 已实现         production.skill.governance.Deployment
SkillRegistry            ✅ 已实现         production.skill.governance.SkillRegistry
```

> **说明**：
> - **Runtime 层**：`production/` 包下已全量实现（对话运行时、上下文压缩、权限、成本、记忆）
> - **Skill 层**：本次补充实现，包含 `production.skill.*` 下的根实体、Runtime 封装、Eval 框架、Governance 治理、SOP 存储
> - **SOP 层**：以 Markdown 文档形式存在（本文及 `docs/` 目录），同时有 `SkillSop` 类做结构化存储
> - **Tutorial 层**：`tutorial.skill.*` 提供简化版实现，便于理解核心概念

### 2.2 Skill Runtime 层 → 代码类映射表

| 领域模型 | 代码类（完整路径） | 所在包 | 说明 |
|---|---|---|---|
| `SkillRuntime` | `com.kuoge.agentstudy.production.runtime.core.ConversationRuntime` | runtime | 核心对话运行时，对应 Skill 的「可执行程序」 |
| `SkillRuntime` | `com.kuoge.agentstudy.production.agent.ProductionReActAgent` | agent | Agent 入口 facade，封装 Runtime 的调用 |
| `ToolDefinition` | `com.kuoge.agentstudy.production.tool.Tool` (interface) | tool | 工具定义接口（name / description / execute） |
| `ToolDefinition` | `com.kuoge.agentstudy.production.tool.ToolRegistry` | tool | 工具注册中心（管理 Tool 集合） |
| `ToolDefinition` | `com.kuoge.agentstudy.production.tool.ToolExecutor` | tool | 工具执行器（调用 Action → 执行 Tool） |
| `ReActLoop` | `com.kuoge.agentstudy.production.runtime.core.ConversationRuntime.runTurn()` | runtime | ReAct 外循环的工程化实现 |
| `LoopStep` | `com.kuoge.agentstudy.production.model.ReActStep` (record) | model | 单步记录（thought + action + observation） |
| `Action` | `com.kuoge.agentstudy.production.model.Action` (record) | model | 工具调用动作 |
| `Observation` | `com.kuoge.agentstudy.production.model.Observation` (record) | model | 观察结果 |
| `AgentSession` | `com.kuoge.agentstudy.production.runtime.session.AgentSession` | session | 会话管理（消息历史 + 压缩记录 + Fork） |
| `Message` | `com.kuoge.agentstudy.production.runtime.session.ConversationMessage` | session | 结构化消息（多 block + token 用量） |
| `Message` | `com.kuoge.agentstudy.production.runtime.session.ContentBlock` (sealed interface) | session | 内容块：Text / Thinking / ToolUse / ToolResult |
| `Message` | `com.kuoge.agentstudy.production.runtime.session.MessageRole` (enum) | session | SYSTEM / USER / ASSISTANT / TOOL |
| `LlmClient` | `com.kuoge.agentstudy.production.runtime.client.LlmClient` (interface) | client | LLM 客户端抽象 |
| `LlmResponse` | `com.kuoge.agentstudy.production.runtime.client.LlmResponse` (record) | client | LLM 结构化响应（blocks + usage） |
| `TokenUsage` | `com.kuoge.agentstudy.production.runtime.usage.TokenUsage` (record) | usage | Token 用量（input / output / cache） |
| `UsageTracker` | `com.kuoge.agentstudy.production.runtime.usage.UsageTracker` | usage | 会话级用量累积 |
| `TurnSummary` | `com.kuoge.agentstudy.production.runtime.core.TurnSummary` (record) | runtime | 单次 Turn 执行摘要 |
| `RuntimeConfig` | `com.kuoge.agentstudy.production.runtime.core.RuntimeConfig` (record) | runtime | 运行时配置（循环上限 / 压缩 / 权限） |
| `ContextEngineering` | `com.kuoge.agentstudy.production.context.ContextCompressor` | context | 9段式上下文压缩器 |
| `ContextEngineering` | `com.kuoge.agentstudy.production.context.ContextSegment` | context | 上下文段（内容 + 策略 + token 数） |
| `ContextEngineering` | `com.kuoge.agentstudy.production.context.SegmentType` (enum) | context | 9 种段类型（SYSTEM_IDENTITY ... SCRATCHPAD） |
| `SessionCompactor` | `com.kuoge.agentstudy.production.runtime.compact.SessionCompactor` | compact | 会话自动压缩器（摘要 + 边界保护） |
| `CompactionConfig` | `com.kuoge.agentstudy.production.runtime.compact.CompactionConfig` (record) | compact | 压缩配置 |
| `CompactionResult` | `com.kuoge.agentstudy.production.runtime.compact.CompactionResult` (record) | compact | 压缩结果 |
| `PermissionPolicy` | `com.kuoge.agentstudy.production.runtime.permission.PermissionPolicy` | permission | 权限策略引擎（allow / deny / ask 规则） |
| `PermissionMode` | `com.kuoge.agentstudy.production.runtime.permission.PermissionMode` (enum) | permission | 权限模式枚举 |
| `PermissionOutcome` | `com.kuoge.agentstudy.production.runtime.permission.PermissionOutcome` (sealed) | permission | 权限结果：Allow / Deny / Ask |
| `ToolHook` | `com.kuoge.agentstudy.production.runtime.hook.ToolHook` (interface) | hook | 工具钩子（pre / post / failure） |
| `HookResult` | `com.kuoge.agentstudy.production.runtime.hook.HookResult` (record) | hook | 钩子执行结果 |
| `PreferenceMemory` | `com.kuoge.agentstudy.production.memory.PreferenceMemory` | memory | 偏好记忆系统（Core + Archival） |
| `UserPreference` | `com.kuoge.agentstudy.production.memory.UserPreference` (record) | memory | 用户偏好条目（confidence / freshnessScore） |
| `CostTracker` | `com.kuoge.agentstudy.production.cost.CostTracker` | cost | 成本追踪器 |
| `TokenBudget` | `com.kuoge.agentstudy.production.cost.TokenBudget` | cost | Token 预算管理 |
| `PromptTemplate` | `com.kuoge.agentstudy.production.cost.PromptTemplateCache` | cost | Prompt 模板缓存 |
| `Skill` | `com.kuoge.agentstudy.production.skill.Skill` | skill | Skill 根实体（Builder 模式） |
| `SkillMetadata` | `com.kuoge.agentstudy.production.skill.SkillMetadata` | skill | 轻量级元数据索引 |
| `SkillStatus` | `com.kuoge.agentstudy.production.skill.SkillStatus` (enum) | skill | DRAFT / GRAYSCALE / ACTIVE / DEPRECATED / ARCHIVED |
| `SkillRuntime` | `com.kuoge.agentstudy.production.skill.runtime.SkillRuntime` | skill.runtime | Skill 级 Runtime 封装 |
| `SkillRuntimeConfig` | `com.kuoge.agentstudy.production.skill.runtime.SkillRuntimeConfig` | skill.runtime | Runtime 配置（maxIterations / contextBudget / autoCompaction） |
| `EvalSuite` | `com.kuoge.agentstudy.production.skill.eval.EvalSuite` | skill.eval | 评估套件（UNIT / INTEGRATION / E2E） |
| `EvalCase` | `com.kuoge.agentstudy.production.skill.eval.EvalCase` | skill.eval | 评估用例（input / expected / difficulty） |
| `EvalResult` | `com.kuoge.agentstudy.production.skill.eval.EvalResult` | skill.eval | 单条评估结果 |
| `EvalReport` | `com.kuoge.agentstudy.production.skill.eval.EvalReport` | skill.eval | 聚合评估报告（passRate / averageScore / regressions） |
| `Evaluator` | `com.kuoge.agentstudy.production.skill.eval.Evaluator` (interface) | skill.eval | 评估器接口 |
| `ExactMatchEvaluator` | `com.kuoge.agentstudy.production.skill.eval.ExactMatchEvaluator` | skill.eval | 精确匹配评估器 |
| `LlmAsJudgeEvaluator` | `com.kuoge.agentstudy.production.skill.eval.LlmAsJudgeEvaluator` | skill.eval | LLM-as-Judge 评估器 |
| `EvalRunner` | `com.kuoge.agentstudy.production.skill.eval.EvalRunner` | skill.eval | Eval 自动化执行器 |
| `EvalHistory` | `com.kuoge.agentstudy.production.skill.eval.EvalHistory` | skill.eval | 评估历史存储 + 回归检测 |
| `SkillRegistry` | `com.kuoge.agentstudy.production.skill.governance.SkillRegistry` | skill.governance | Skill 注册中心（内存实现） |
| `SkillVersion` | `com.kuoge.agentstudy.production.skill.governance.SkillVersion` | skill.governance | 不可变版本记录 |
| `SkillVersionManager` | `com.kuoge.agentstudy.production.skill.governance.SkillVersionManager` | skill.governance | 版本管理器（激活 / 灰度 / 回滚） |
| `Deployment` | `com.kuoge.agentstudy.production.skill.governance.Deployment` | skill.governance | 部署记录 |
| `DeploymentStatus` | `com.kuoge.agentstudy.production.skill.governance.DeploymentStatus` (enum) | skill.governance | INACTIVE / GRAYSCALE / ACTIVE / DEPRECATED |
| `Feedback` | `com.kuoge.agentstudy.production.skill.governance.Feedback` | skill.governance | 反馈统一抽象 |
| `SkillGovernance` | `com.kuoge.agentstudy.production.skill.governance.SkillGovernance` | skill.governance | 治理配置（阈值 / 告警 / 自动回滚） |
| `GovernanceDashboard` | `com.kuoge.agentstudy.production.skill.governance.GovernanceDashboard` | skill.governance | 治理看板（健康检查 / 告警聚合） |
| `SkillSop` | `com.kuoge.agentstudy.production.skill.sop.SkillSop` | skill.sop | SOP 结构化实体 |
| `SkillSopStore` | `com.kuoge.agentstudy.production.skill.sop.SkillSopStore` (interface) | skill.sop | SOP 存储接口 + 内存实现 |

### 2.3 三层架构的代码映射

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Skill 三层架构 → 代码映射                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  SOP 层（规范/沉淀层）                                                       │
│  ─────────────────────                                                       │
│  产物：docs/*.md  +  .claude/skills/*/SKILL.md（如有）                       │
│  对应本文：第 一、二、三、四 章均为 SOP 层知识资产                             │
│                                                                             │
│                                    ↓ 指导实现                                │
│                                                                             │
│  Runtime 层（可执行层）  ←── 本项目已全量实现                                  │
│  ─────────────────────                                                       │
│  核心包：com.kuoge.agentstudy.production.*                                   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  runtime/core/          → ConversationRuntime + RuntimeConfig       │   │
│  │  runtime/session/       → AgentSession + ConversationMessage        │   │
│  │  runtime/client/        → LlmClient + LlmResponse                   │   │
│  │  runtime/usage/         → TokenUsage + UsageTracker                 │   │
│  │  runtime/compact/       → SessionCompactor + CompactionResult       │   │
│  │  runtime/hook/          → ToolHook + HookResult                     │   │
│  │  runtime/permission/    → PermissionPolicy + PermissionOutcome      │   │
│  │  context/               → ContextCompressor + ContextSegment        │   │
│  │  memory/                → PreferenceMemory + UserPreference         │   │
│  │  cost/                  → CostTracker + TokenBudget                 │   │
│  │  tool/                  → Tool + ToolRegistry + ToolExecutor        │   │
│  │  agent/                 → ProductionReActAgent + AgentLlmClient     │   │
│  │  model/                 → ReActStep + Action + Observation          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│                                    ↓ 被管理 / 被观测                         │
│                                                                             │
│  Governance 层（治理层）  ←── 已编码实现（memory 实现，生产环境接 Redis/DB）    │
│  ─────────────────────                                                       │
│  核心包：com.kuoge.agentstudy.production.skill.governance.*                   │
│                                                                             │
│  • SkillRegistry —— 注册表 + 元数据索引 + 搜索                                │
│  • SkillVersionManager —— 版本管理（激活 / 灰度分桶 / 回滚）                   │
│  • GovernanceDashboard —— 健康检查 + 告警聚合 + 状态看板                       │
│  • EvalRunner + EvalHistory —— 自动化执行 + 回归检测                          │
│  • Feedback —— 隐式/显式反馈统一模型                                         │
│                                                                             │
│  待建设（需要 Web 后台 + 数据库）：                                           │
│  • Prompt Studio —— 在线编辑 Prompt + 实时预览 + 版本对比                     │
│  • Feedback Inbox UI —— 可视化反馈处理流程                                    │
│  • 持久化存储 —— 当前为内存实现，生产环境需接入数据库                            │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.4 领域模型 vs 设计模型对照速查

| 领域模型（概念） | 设计模型（代码类） | 实现状态 | 备注 |
|---|---|---|---|
| `Skill` | `production.skill.Skill` | ✅ | Skill 根实体，聚合版本/工具/元数据 |
| `SkillSOP` | `production.skill.sop.SkillSop` + `.md` 文档 | ⚠️ | 结构化 SOP + Markdown 手册 |
| `SkillRuntime` | `production.skill.runtime.SkillRuntime` + `ConversationRuntime` | ✅ | Skill 级 Runtime 封装 + 底层对话运行时 |
| `SkillGovernance` | `production.skill.governance.SkillGovernance` | ✅ | 质量阈值、告警策略、自动回滚配置 |
| `SkillVersion` | `production.skill.governance.SkillVersion` + `SkillVersionManager` | ✅ | 不可变版本 + 灰度/全量/回滚 |
| `PromptTemplate` | `production.cost.PromptTemplateCache` | ⚠️ | 缓存层，版本管理由 SkillVersionManager 覆盖 |
| `ToolDefinition` | `production.tool.Tool` + `ToolRegistry` | ✅ | 运行时工具定义完整实现 |
| `EvalSuite` | `production.skill.eval.EvalSuite` + `EvalRunner` | ✅ | 评估套件 + 自动化执行器 |
| `EvalCase` | `production.skill.eval.EvalCase` | ✅ | 用例（input / expected / difficulty） |
| `EvalResult` | `production.skill.eval.EvalResult` + `EvalReport` | ✅ | 单条结果 + 聚合报告 |
| `Feedback` | `production.skill.governance.Feedback` | ✅ | 隐式/显式反馈统一抽象 |
| `Deployment` | `production.skill.governance.Deployment` | ✅ | 部署记录（部署/灰度/回滚/废弃） |
| `SkillRegistry` | `production.skill.governance.SkillRegistry` | ✅ | 注册中心 + 元数据索引 + 依赖图谱预留 |

### 2.5 代码阅读路径建议

| 你想了解什么 | 从哪个包开始 | 核心类 |
|---|---|---|
| **ReAct 循环的最简实现** | `tutorial/` | `ReActLoop.java`（60 行核心逻辑） |
| **生产级对话运行时** | `production.runtime.core/` | `ConversationRuntime.runTurn()` |
| **结构化消息模型** | `production.runtime.session/` | `ConversationMessage` + `ContentBlock` |
| **上下文工程实践** | `production.context/` | `ContextCompressor`（9段式压缩） |
| **权限控制设计** | `production.runtime.permission/` | `PermissionPolicy.authorize()` |
| **工具调用链** | `production.tool/` | `Tool` → `ToolRegistry` → `ToolExecutor` |
| **成本与预算** | `production.cost/` | `CostTracker` + `TokenBudget` |
| **记忆系统** | `production.memory/` | `PreferenceMemory` + `UserPreference` |
| **会话压缩** | `production.runtime.compact/` | `SessionCompactor` + `CompactionConfig` |
| **Skill 根实体与生命周期** | `production.skill/` | `Skill` + `SkillStatus` |
| **Skill Runtime 封装** | `production.skill.runtime/` | `SkillRuntime` + `SkillRuntimeConfig` |
| **Eval 框架** | `production.skill.eval/` | `EvalSuite` → `EvalRunner` → `EvalReport` |
| **版本管理与灰度** | `production.skill.governance/` | `SkillVersionManager`（灰度分桶 / 回滚） |
| **治理看板** | `production.skill.governance/` | `GovernanceDashboard` + `SkillRegistry` |
| **SOP 存储** | `production.skill.sop/` | `SkillSop` + `SkillSopStore` |
| **Tutorial 层快速入门** | `tutorial.skill/` | `SkillRegistry` + `SkillEvaluator` |

---

## 三、业界 Skill 学习优化迭代概览

### 3.1 什么是 Skill（三层视角）

在你们的项目语境下，**一个完整的 Skill = SOP 手册 + Runtime 实现 + Governance 配置**。

以「商品自动比价」为例：

| 层次 | 对应产物 | 核心内容 |
|---|---|---|
| **SOP 层** | `.claude/skills/streetwear-platform-price-scraping/SKILL.md` | 平台矩阵、反爬策略、数据标准化格式、失败重试策略 |
| **Runtime 层** | `PlatformScraperRegistry.java` + `SsenseScraper.java` + ... | 注册中心、并行抓取、HTML 解析、价格字段映射 |
| **Governance 层** | `skill-admin-backend` 中的「比价 Skill 管理页」 | 各平台抓取成功率看板、版本灰度开关、Eval 报告 |

**常见误区**：以为写好 `.md` SOP 就等于做好了 Skill。实际上：
- SOP 写得好 ≠ Runtime 跑得稳（SOP 没写超时处理，代码可能挂）
- Runtime 跑得稳 ≠ Governance 能迭代（没有 Eval 数据，不知道好不好）
- 三者缺一不可，且必须形成联动闭环

### 3.2 业界核心方法论

| 方法论 | 代表厂商/项目 | 核心思想 | 对 Skill 迭代的意义 |
|---|---|---|---|
| **Eval-Driven Development (EDD)** | Anthropic, OpenAI | 像写单元测试一样写 Evals，用数据集驱动 Skill Runtime 优化 | 避免「感觉效果好」的主观判断，用数据说话 |
| **ReAct (Reasoning + Acting)** | Princeton / Google | LLM 先思考（Thought）再行动（Action），观察（Observation）后再思考 | Skill Runtime 执行的标准循环模式，支持多步复杂任务 |
| **Tool-Augmented Generation** | Anthropic, OpenAI | LLM 不直接回答问题，而是生成工具调用请求，由外部系统执行后返回结果 | 解决 LLM 幻觉、知识过期、无法访问私有数据的问题 |
| **Context Engineering** | Anthropic | 比 Prompt Engineering 更进一步，关注整个上下文窗口的结构化组织 | Skill Runtime 的输入输出格式、历史消息、工具结果的组织方式直接影响效果 |
| **DSPy** | Stanford | 用编程框架替代手写 Prompt，自动优化 Prompt 和权重 | 为 Skill Runtime 的 Prompt 自动化调优提供理论框架 |

### 3.3 为什么选 Claude（Anthropic）作为深度案例

Anthropic 在 Agentic 系统设计上具有业界标杆地位：

1. **「Building effective agents」**（2024.12）系统阐述了从简单到复杂的 Agent 设计范式
2. **Tool Use** 架构是 Spring AI `ToolCallback` 的设计源头之一
3. **Computer Use** 是目前最接近「通用数字员工」的 Skill 实现
4. **MCP（Model Context Protocol）** 正在成为 Skill/工具生态的事实标准
5. 设计理念强调 **"简单、可预测、可调试"**，与工程团队的 Java/Spring 技术文化高度契合

---

## 四、Claude Skill Runtime 架构深度解析【Runtime 层】

### 4.1 设计哲学：从「Prompt Engineering」到「Context Engineering」

Anthropic 的核心观点是：**Prompt Engineering 已经不够了，你需要的是 Context Engineering**。

| 维度 | Prompt Engineering | Context Engineering |
|---|---|---|
| 关注点 | 提示词文本的措辞 | 整个上下文窗口的结构化组织 |
| 手段 | 调 wording、加 few-shot | 设计消息角色、工具定义格式、历史消息管理、RAG 注入策略 |
| 目标 | 让 LLM 生成更好的文本 | 让 LLM 在结构化环境中做出可预测的正确决策 |
| 比喻 | 给厨师写菜谱 | 设计整个厨房的动线、工具摆放、食材存储 |

**对 Skill Runtime 设计的启示**：
- 一个 Skill Runtime 的效果，80% 取决于「上下文如何组织」，而非「Prompt 写得多么华丽」
- 工具定义（Tool Definition）的 schema 设计、描述文案的精确性，比 system prompt 的文采更重要

### 4.2 核心架构：三层模型

Claude 的 **Agent Runtime 系统**（即 Skill 的运行时实现层）采用**「输入层 → 推理层 → 执行层」**的三层架构：

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                 Claude Agent Runtime 三层架构【Skill Runtime 层】            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                        输入层 (Input Layer)                          │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │   │
│  │  │  System 提示  │  │  用户消息     │  │  历史上下文   │              │   │
│  │  │  (角色定义)   │  │  (当前请求)   │  │  (多轮记忆)   │              │   │
│  │  └──────────────┘  └──────────────┘  └──────────────┘              │   │
│  │  ┌──────────────────────────────────────────────────────────────┐  │   │
│  │  │  Tool Definitions (XML/JSON Schema)                           │  │   │
│  │  │  - 每个工具的名称、描述、参数 schema                           │  │   │
│  │  │  - 描述文案决定模型是否选择该工具                              │  │   │
│  │  └──────────────────────────────────────────────────────────────┘  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                    │                                        │
│                                    ▼                                        │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                       推理层 (Reasoning Layer)                       │   │
│  │                                                                      │   │
│  │  Claude 模型执行：                                                   │   │
│  │  1. 理解用户意图                                                     │   │
│  │  2. 判断是否需要调用工具                                             │   │
│  │  3. 如需要：生成结构化工具调用请求                                   │   │
│  │  4. 如不需要：直接生成自然语言回复                                   │   │
│  │                                                                      │   │
│  │  关键机制：                                                          │   │
│  │  - Chain of Thought（思维链）：模型内部推理过程                      │   │
│  │  - Tool Choice：模型自主选择工具的能力                               │   │
│  │  - Response Formatting：强制 JSON/XML 输出格式                       │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                    │                                        │
│                                    ▼                                        │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                        执行层 (Execution Layer)                      │   │
│  │                                                                      │   │
│  │  如模型生成了工具调用：                                              │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │   │
│  │  │ 解析调用请求  │──→│ 执行工具函数  │──→│ 格式化结果    │              │   │
│  │  └──────────────┘  └──────────────┘  └──────────────┘              │   │
│  │                                                                      │   │
│  │  工具结果作为新消息注入上下文，模型再次推理，直到任务完成。          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 4.3 核心执行模型：ReAct Loop 的工程化实现

Claude 的 **Agent 执行流程**采用**增强版 ReAct 循环**。与学术版 ReAct 不同，工程化实现强调**结构化、可中断、可观测**。

```
┌─────────────────────────────────────────────────────────────────────────────┐
│              Claude Agent ReAct Loop (工程化版)【Skill Runtime 层】          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   开始                                                                       │
│    │                                                                        │
│    ▼                                                                        │
│   ┌─────────────────────┐                                                   │
│   │ Step 1: 组装上下文   │  合并 system prompt + user message +            │
│   │   (Build Context)   │        tool definitions + history + knowledge     │
│   └──────────┬──────────┘                                                   │
│              │                                                              │
│              ▼                                                              │
│   ┌─────────────────────┐                                                   │
│   │ Step 2: LLM 推理     │  模型决定：直接回答？还是调用工具？              │
│   │   (LLM Reasoning)   │  输出：自然语言 或 结构化工具调用                │
│   └──────────┬──────────┘                                                   │
│              │                                                              │
│      ┌───────┴───────┐                                                      │
│      ▼               ▼                                                      │
│   [直接回答]      [工具调用]                                                 │
│      │               │                                                      │
│      │               ▼                                                      │
│      │      ┌─────────────────────┐                                        │
│      │      │ Step 3: 解析调用     │  提取 tool_name + arguments           │
│      │      │   (Parse Call)      │                                        │
│      │      └──────────┬──────────┘                                        │
│      │                 │                                                    │
│      │                 ▼                                                    │
│      │      ┌─────────────────────┐                                        │
│      │      │ Step 4: 执行工具     │  调用外部函数/API，获取结果            │
│      │      │   (Execute Tool)    │  ⚠️ 可中断：超时/异常/权限检查         │
│      │      └──────────┬──────────┘                                        │
│      │                 │                                                    │
│      │                 ▼                                                    │
│      │      ┌─────────────────────┐                                        │
│      │      │ Step 5: 注入结果     │  将工具结果格式化为消息，               │
│      │      │   (Inject Result)   │  追加到上下文                          │
│      │      └──────────┬──────────┘                                        │
│      │                 │                                                    │
│      └───────┬─────────┘                                                    │
│              │                                                              │
│              ▼                                                              │
│   ┌─────────────────────┐                                                   │
│   │ Step 6: 循环判断     │  是否达到终止条件？                              │
│   │   (Should Continue?)│  - 任务已完成（模型输出最终回答）                │
│   └──────────┬──────────┘  - 达到最大循环次数                               │
│              │            - 发生不可恢复异常                                │
│           是 │                                                              │
│              ▼                                                              │
│           结束                                                               │
│              │ 否                                                           │
│              └──────────────────────→ 回到 Step 2                           │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**工程化增强点**（区别于学术论文中的 ReAct）：

| 增强点 | 说明 | 为什么重要 |
|---|---|---|
| **最大循环限制** | 设置 `max_iterations`（通常 5-10 次） | 防止无限循环，保障响应时间 |
| **工具执行沙箱** | 工具在隔离环境中执行，异常不扩散 | 一个工具失败不影响整体系统 |
| **结果截断** | 工具返回结果超长时自动截断 | 防止上下文窗口溢出 |
| **并行工具调用** | 一次推理可生成多个独立工具调用 | 提升效率（如同时查库存和查价格） |
| **工具选择强制/禁用** | 可强制使用某工具，或禁止某些工具 | 精确控制 Skill 行为边界 |

---

## 五、典型案例深度拆解

### 案例 1：Web Search Skill（信息检索型）

这是最典型的 Skill 类型：LLM 需要获取最新信息，但知识有截止日期，因此需要调用搜索工具。

#### Claude 的 Tool Definition 设计

```xml
<!-- Anthropic 原生使用 XML 格式定义工具（API 层），Java/Spring AI 中对应 @Tool 注解 -->
<tools>
  <tool name="web_search">
    <description>
      执行网络搜索，获取与查询相关的最新信息。
      当用户询问时事、最新数据、或你不确定的事实时使用此工具。
      搜索关键词应尽量具体，避免过于宽泛。
    </description>
    <parameters>
      <parameter name="query" type="string" required="true">
        <description>搜索查询语句，应包含关键实体和具体信息</description>
      </parameter>
      <parameter name="num_results" type="integer" required="false">
        <description>返回结果数量，默认 5，最大 10</description>
      </parameter>
    </parameters>
  </tool>
</tools>
```

> **Java/Spring AI 对应**：Spring AI 使用 `@Tool` 注解定义工具，底层自动转换为 LLM 所需的 schema 格式（OpenAI 用 JSON Schema，Anthropic 用 XML）。

#### Java 代码示例（Spring AI 风格）

```java
package com.kuoge.tech.agent.skill.examples;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Web Search Skill - 对应 Claude 的 web_search 工具。
 * 
 * 在 Claude 架构中，这是一个 "Retrieval Skill"（检索型 Skill），
 * 核心职责：将用户的信息需求转化为搜索查询，获取外部信息后提供给模型。
 */
@Service
public class WebSearchSkill {

    private final HttpClient httpClient;
    private final String searchApiKey;

    public WebSearchSkill() {
        // JDK 21 HttpClient（对应项目 skill：java-http-client）
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.searchApiKey = System.getenv("SEARCH_API_KEY");
    }

    /**
     * Tool 定义对应 Claude XML 中的 <tool> 定义。
     * 
     * Spring AI 会自动将此方法转换为 LLM 可用的 tool schema。
     * 关键设计点：
     * 1. @Tool description 必须精确描述"何时使用此工具"
     *    → 这直接影响模型的 tool choice 决策质量
     * 2. @ToolParam description 必须描述参数的含义和约束
     *    → 这直接影响模型生成参数的质量
     */
    @Tool(name = "web_search", 
          description = """
              执行网络搜索，获取与查询相关的最新信息。
              当用户询问时事、最新数据、特定事实或你不确定的信息时使用此工具。
              搜索查询应包含关键实体，避免过于宽泛。
              """)
    public SearchResult search(
            @ToolParam(description = "搜索查询语句，应包含关键实体和具体信息，如 'Nike Air Jordan 1 2024 发售'")
            String query,
            
            @ToolParam(description = "返回结果数量，默认 5，最大 10")
            int numResults) {
        
        // 输入校验（工程化防御）
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("搜索查询不能为空");
        }
        if (numResults < 1 || numResults > 10) {
            numResults = 5; // 默认值保护
        }

        // 执行搜索（对应 Claude 执行层的外部 API 调用）
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(java.net.URI.create(
                    "https://api.search.com/v1/search?q=" + 
                    java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8) +
                    "&limit=" + numResults))
                .header("Authorization", "Bearer " + searchApiKey)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

            // 结果格式化（对应 Claude 执行层的 "Inject Result"）
            // 关键：结果必须简洁，避免污染上下文窗口
            return formatSearchResults(response.body(), numResults);
            
        } catch (Exception e) {
            // 异常处理：返回结构化错误，让模型决定如何继续
            return new SearchResult(
                List.of(), 
                "搜索服务暂时不可用: " + e.getMessage()
            );
        }
    }

    /**
     * 结果格式化策略（Claude 最佳实践）。
     * 
     * 核心原则：
     * 1. 只保留关键信息（标题 + 摘要 + URL），去除 HTML/CSS/广告
     * 2. 按相关性排序，截断过长内容
     * 3. 添加元信息（搜索时间、结果总数）帮助模型判断信息新鲜度
     */
    private SearchResult formatSearchResults(String rawJson, int limit) {
        // 简化示例：实际应使用 Jackson/Gson 解析
        List<SearchResultItem> items = parseItems(rawJson);
        
        StringBuilder formatted = new StringBuilder();
        formatted.append("<search_results query_time=\"").append(java.time.Instant.now()).append("\">\n");
        
        for (int i = 0; i < Math.min(items.size(), limit); i++) {
            SearchResultItem item = items.get(i);
            formatted.append("  <result index=\"").append(i + 1).append("\">\n");
            formatted.append("    <title>").append(truncate(item.title(), 100)).append("</title>\n");
            formatted.append("    <snippet>").append(truncate(item.snippet(), 300)).append("</snippet>\n");
            formatted.append("    <url>").append(item.url()).append("</url>\n");
            formatted.append("  </result>\n");
        }
        formatted.append("</search_results>");
        
        return new SearchResult(items, formatted.toString());
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    // ========== 数据模型 ==========
    public record SearchResult(List<SearchResultItem> items, String formattedOutput) {}
    public record SearchResultItem(String title, String snippet, String url) {}
    
    private List<SearchResultItem> parseItems(String rawJson) {
        // Jackson 解析逻辑省略
        return List.of();
    }
}
```

#### 这个 Runtime Skill（Web Search）的关键设计决策

| 决策点 | Claude 的设计选择 | 为什么 |
|---|---|---|
| **何时调用搜索** | 由模型自主决定（通过 tool description 引导） | 避免过度搜索（每个查询都搜）或搜索不足（该搜时不搜） |
| **查询生成** | 模型根据用户意图自动生成 `query` 参数 | 模型比规则更擅长将模糊需求转化为精确查询 |
| **结果截断** | 固定长度截断 + 限制结果数 | 防止上下文爆炸，保障响应速度 |
| **错误处理** | 返回错误信息给模型，让模型自主决策 | 不直接抛异常中断整个对话，提升鲁棒性 |

---

### 案例 2：Computer Use Skill（环境交互型）

Computer Use 是 Anthropic 在 2024 年推出的标杆能力，允许 Claude 像人类一样「看屏幕、移动鼠标、敲击键盘」。这是目前业界最复杂的 Skill 之一。

#### 架构设计

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Claude Computer Use Skill 架构                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   用户请求: "帮我在这个网页上找到最便宜的航班"                                │
│                                                                             │
│   ┌─────────────────────────────────────────────────────────────────────┐   │
│   │                        Claude 模型                                   │   │
│   │                                                                      │   │
│   │  输入：当前屏幕截图（base64 PNG）+ 用户指令                           │   │
│   │                                                                      │   │
│   │  推理过程：                                                          │   │
│   │  1. 观察屏幕：看到 Google 搜索页面                                    │   │
│   │  2. 规划行动：需要搜索 "flights to Tokyo"                             │   │
│   │  3. 生成工具调用：                                                   │   │
│   │     { "action": "screenshot" } → 获取当前画面                        │   │
│   │     { "action": "click", "coordinate": [420, 380] } → 点击搜索框     │   │
│   │     { "action": "type", "text": "flights to Tokyo" } → 输入文本      │   │
│   │     { "action": "keypress", "keys": ["Return"] } → 回车搜索          │   │
│   │                                                                      │   │
│   └──────────────────────────────┬───────────────────────────────────────┘   │
│                                  │                                          │
│                                  ▼                                          │
│   ┌─────────────────────────────────────────────────────────────────────┐   │
│   │                      沙箱执行环境 (Sandbox)                          │   │
│   │                                                                      │   │
│   │  接收工具调用 → 执行鼠标/键盘操作 → 截取新屏幕 → 返回截图            │   │
│   │                                                                      │   │
│   │  关键技术：                                                          │   │
│   │  - Docker 容器隔离（安全沙箱）                                        │   │
│   │  - VNC/RDP 远程桌面协议                                               │   │
│   │  - 屏幕截图压缩（减少 token 消耗）                                    │   │
│   │  - 操作序列化/回放（可审计、可复现）                                  │   │
│   │                                                                      │   │
│   └──────────────────────────────┬───────────────────────────────────────┘   │
│                                  │                                          │
│                                  └────────────────────→ 截图返回给模型        │
│                                                                             │
│   循环：模型观察新截图 → 判断任务进度 → 生成下一步操作 → 执行 → 观察...    │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Java 代码示例（Computer Use 简化版）

```java
package com.kuoge.tech.agent.skill.examples;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import javax.imageio.ImageIO;

/**
 * Computer Use Skill - 对应 Claude 的 computer_use 工具。
 * 
 * 这是 Anthropic 最复杂的 Skill 类型，核心挑战：
 * 1. 多模态输入：模型需要"看懂"屏幕截图（视觉理解）
 * 2. 精确空间定位：模型输出的是像素坐标 [x, y]，必须精确
 * 3. 长序列决策：完成一个任务可能需要 10-50 步操作
 * 4. 安全隔离：必须在沙箱中执行，防止恶意操作
 * 
 * 在电商场景中，类似 Skill 可以是：
 * - 自动填写复杂的采购订单表单
 * - 在第三方平台（如 Shopify 后台）执行批量操作
 * - 抓取竞品网站的视觉信息（价格、图片、布局）
 */
@Service
public class ComputerUseSkill {

    private final Robot robot;
    private final ScreenCapture capture;
    private final SafetyGuard guard;

    // 最大操作步数（防止无限循环）
    private static final int MAX_STEPS = 50;
    
    // 每步之间的延迟（模拟人类操作节奏，避免被反爬检测）
    private static final int STEP_DELAY_MS = 500;

    public ComputerUseSkill() throws Exception {
        this.robot = new Robot();
        this.capture = new ScreenCapture();
        this.guard = new SafetyGuard();
    }

    /**
     * 核心工具：执行计算机操作。
     * 
     * 注意：Claude 的 Computer Use 实际上不是单步工具，
     * 而是一个 "Agent Loop"，模型会反复调用 screenshot + action 组合。
     * 这里为了示例清晰，将循环逻辑外置在 orchestrator 中。
     */
    @Tool(name = "computer_action",
          description = """
              在受控计算机环境中执行鼠标或键盘操作。
              仅在需要与图形界面交互时使用（如点击按钮、填写表单、滚动页面）。
              每次调用后，系统会自动返回新的屏幕截图供你观察效果。
              """)
    public ComputerStepResult executeAction(
            @ToolParam(description = "操作类型：screenshot | click | type | keypress | scroll")
            String action,
            
            @ToolParam(description = "点击坐标 [x, y]，仅 click 操作需要")
            int[] coordinate,
            
            @ToolParam(description = "输入文本，仅 type 操作需要")
            String text,
            
            @ToolParam(description = "按键列表，仅 keypress 操作需要，如 [\"Return\", \"Control+c\"]")
            String[] keys) {

        // 1. 安全检查（沙箱边界控制）
        if (!guard.isActionAllowed(action, coordinate)) {
            return new ComputerStepResult(
                null,
                "ERROR: 操作被拒绝，超出允许范围",
                false
            );
        }

        try {
            // 2. 执行操作
            switch (action) {
                case "screenshot" -> {
                    // 截图并返回 base64（给多模态模型"看"）
                    return captureAndReturn();
                }
                case "click" -> {
                    robot.mouseMove(coordinate[0], coordinate[1]);
                    robot.delay(100);
                    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                    robot.delay(50);
                    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                }
                case "type" -> {
                    // 逐字符输入（模拟真实打字）
                    for (char c : text.toCharArray()) {
                        typeCharacter(c);
                        robot.delay(20);
                    }
                }
                case "keypress" -> {
                    for (String key : keys) {
                        pressKey(key);
                    }
                }
                case "scroll" -> {
                    robot.mouseWheel(coordinate[1] > 0 ? -3 : 3); // 负值向上，正值向下
                }
                default -> throw new IllegalArgumentException("未知操作: " + action);
            }

            // 3. 操作后等待页面稳定，再截图返回
            robot.delay(STEP_DELAY_MS);
            return captureAndReturn();

        } catch (Exception e) {
            return new ComputerStepResult(
                null,
                "ERROR: 操作执行失败: " + e.getMessage(),
                false
            );
        }
    }

    /**
     * 截图并编码为 base64（多模态模型输入）。
     * 
     * Claude 使用此格式让模型"看到"屏幕状态。
     * 在 Java 中，对应使用 java.awt.Robot 截屏 + Base64 编码。
     */
    private ComputerStepResult captureAndReturn() throws Exception {
        BufferedImage screenshot = capture.takeScreenshot();
        
        // 压缩为 PNG 减少传输大小
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(screenshot, "png", baos);
        String base64Image = Base64.getEncoder().encodeToString(baos.toByteArray());
        
        return new ComputerStepResult(
            base64Image,
            "Screenshot captured. Screen size: " + screenshot.getWidth() + "x" + screenshot.getHeight(),
            true
        );
    }

    private void typeCharacter(char c) {
        // 简化实现：实际需处理大小写、特殊字符等
        int keyCode = KeyEvent.getExtendedKeyCodeForChar(c);
        if (KeyEvent.CHAR_UNDEFINED == keyCode) return;
        robot.keyPress(keyCode);
        robot.keyRelease(keyCode);
    }

    private void pressKey(String key) {
        // 简化实现：处理 Return, Control+c 等组合键
        switch (key) {
            case "Return" -> {
                robot.keyPress(KeyEvent.VK_ENTER);
                robot.keyRelease(KeyEvent.VK_ENTER);
            }
            // ... 其他按键映射
        }
    }

    // ========== 数据模型 ==========
    public record ComputerStepResult(
        String screenshotBase64,   // 截图 base64（多模态输入）
        String status,             // 状态描述
        boolean success            // 是否成功
    ) {}
}

/**
 * 安全沙箱守卫 - Claude Computer Use 的核心安全机制。
 * 
 * 在 Claude 的生产环境中，此组件确保：
 * 1. 操作范围限制：只能在指定窗口/区域内操作
 * 2. 敏感操作拦截：禁止访问系统设置、执行 shell 命令等
 * 3. 网络隔离：沙箱无法访问内网敏感服务
 * 4. 超时控制：单次会话最大执行时间
 */
class SafetyGuard {
    public boolean isActionAllowed(String action, int[] coordinate) {
        // 示例：限制点击范围在 1920x1080 内
        if ("click".equals(action) && coordinate != null) {
            return coordinate[0] >= 0 && coordinate[0] <= 1920
                && coordinate[1] >= 0 && coordinate[1] <= 1080;
        }
        return true;
    }
}

class ScreenCapture {
    public BufferedImage takeScreenshot() throws Exception {
        java.awt.Rectangle screenRect = new java.awt.Rectangle(
            java.awt.Toolkit.getDefaultToolkit().getScreenSize());
        return new Robot().createScreenCapture(screenRect);
    }
}
```

#### Computer Use 对 Runtime 设计的启示

| 挑战 | Claude 的解决方案 | 对 AI Agent 系统 的借鉴 |
|---|---|---|
| **多模态输入** | 屏幕截图 → base64 → 多模态模型理解 | 视觉搜索 Agent 可复用此模式：用户上传图片 → 模型理解 → 调用工具 |
| **长序列决策** | 模型自主判断任务进度，决定下一步 | ShoppingGuide 的多轮对话本质也是长序列，需要模型自主管理对话状态 |
| **错误恢复** | 操作失败后，模型从截图中观察并调整策略 | Skill 执行失败时，应将错误上下文返回给模型，让其自主决策 retry 策略 |
| **安全隔离** | Docker 沙箱 + 操作白名单 | AI Agent 系统 的价格抓取、竞品监控等 Skill 也应在隔离环境中执行 |

---

## 六、Skill 迭代优化方法论【跨三层】

### 6.1 Eval-Driven Development（评估驱动开发）

Anthropic 内部的核心方法论：**Skill Runtime 的每次迭代都必须由 Evals 数据集驱动，而非主观感觉**。

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Eval-Driven Development 流程                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌──────────────┐                                                           │
│  │ 1. 定义目标   │  "ProductSearch Skill 应该能正确理解 90% 的潮流黑话"     │
│  └──────┬───────┘                                                           │
│         │                                                                   │
│         ▼                                                                   │
│  ┌──────────────┐                                                           │
│  │ 2. 构建 Evals │  数据集 = {input: "想搞一双 bred，预算2k",                │
│  │    数据集     │           expected: {colorway: "bred", priceMax: 2000}}   │
│  └──────┬───────┘                                                           │
│         │  至少 100-500 条覆盖各种场景的测试用例                              │
│         ▼                                                                   │
│  ┌──────────────┐                                                           │
│  │ 3. 建立基线   │  当前版本在 Evals 上的准确率：72%                         │
│  │              │  主要失败 case："bred" 被误识别为品牌名而非配色            │
│  └──────┬───────┘                                                           │
│         │                                                                   │
│         ▼                                                                   │
│  ┌──────────────┐                                                           │
│  │ 4. 迭代优化   │  在 Prompt 中增加 Colorway 词典说明：                     │
│  │              │  "bred = 黑红配色，常用于 Air Jordan 系列"                 │
│  └──────┬───────┘                                                           │
│         │                                                                   │
│         ▼                                                                   │
│  ┌──────────────┐                                                           │
│  │ 5. 回归验证   │  新版本在 Evals 上重新测试：准确率 89%                    │
│  │              │  满足目标（≥90%）？否 → 继续优化                          │
│  └──────┬───────┘                                                           │
│         │ 是                                                                │
│         ▼                                                                   │
│  ┌──────────────┐                                                           │
│  │ 6. 发布上线   │  更新 Skill 版本，配置中心推送到生产环境                  │
│  └──────────────┘                                                           │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Java 代码示例：Eval 框架

```java
package com.kuoge.tech.agent.skill.eval;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Skill Runtime Eval 框架 - 对应 Anthropic 内部的 Evals 系统。
 * 
 * 核心设计：
 * 1. 每个 Skill Runtime 必须配套一个 EvalSuite
 * 2. EvalCase 包含输入 + 期望输出 + 评判标准
 * 3. Evaluator 自动执行并输出报告
 * 
 * 在 Claude 的实践中，Evals 分为两类：
 * - Unit Evals：针对单个工具调用的精确性（如 query 解析是否正确）
 * - Integration Evals：针对端到端任务完成度（如用户说"找双鞋"，最终是否推荐了合适的鞋）
 */
public class SkillEvalFramework {

    /**
     * 单个测试用例。
     * 
     * 对应 Anthropic 的 "eval case" 概念。
     * 
     * T: Skill Runtime 的输入类型
     * R: Skill Runtime 的输出类型
     * E: 期望的评估维度（可以是精确匹配、模糊匹配、或自定义评分）
     */
    public record EvalCase<T, R, E>(
        String id,                  // 用例 ID
        String description,         // 用例描述（如 "Colorway 黑话理解 - bred"）
        T input,                    // 输入
        E expected,                 // 期望结果
        Map<String, Object> metadata  // 元数据（如难度标签、类别标签）
    ) {}

    /**
     * 评估结果。
     */
    public record EvalResult(
        String caseId,
        boolean passed,             // 是否通过
        double score,               // 分数（0-1）
        String actualOutput,        // 实际输出（用于调试）
        String failureReason        // 失败原因（如 "colorway 解析错误"）
    ) {}

    /**
     * EvalSuite：一组相关的测试用例。
     */
    public static class EvalSuite<T, R, E> {
        private final String name;
        private final List<EvalCase<T, R, E>> cases;
        private final Evaluator<T, R, E> evaluator;

        public EvalSuite(String name, 
                         List<EvalCase<T, R, E>> cases,
                         Evaluator<T, R, E> evaluator) {
            this.name = name;
            this.cases = cases;
            this.evaluator = evaluator;
        }

        /**
         * 运行全部测试用例。
         * 
         * 对应 Claude 内部 CI/CD 中的 "eval run" 命令。
         */
        public EvalReport run(Function<T, R> skillExecutor) {
            List<EvalResult> results = cases.stream()
                .map(c -> evaluator.evaluate(c, skillExecutor.apply(c.input())))
                .toList();

            long passed = results.stream().filter(EvalResult::passed).count();
            double avgScore = results.stream()
                .mapToDouble(EvalResult::score).average().orElse(0);

            return new EvalReport(name, results, passed, cases.size(), avgScore);
        }
    }

    /**
     * 评估器接口。
     * 
     * Claude 使用多种评估策略：
     * 1. Exact Match：精确匹配（适合结构化输出）
     * 2. LLM-as-Judge：用另一个 LLM 评判输出质量（适合开放性问题）
     * 3. Code Execution：执行代码验证正确性（适合数学/编程任务）
     */
    @FunctionalInterface
    public interface Evaluator<T, R, E> {
        EvalResult evaluate(EvalCase<T, R, E> testCase, R actualOutput);
    }

    public record EvalReport(
        String suiteName,
        List<EvalResult> results,
        long passedCount,
        long totalCount,
        double averageScore
    ) {
        public double passRate() {
            return totalCount == 0 ? 0 : (double) passedCount / totalCount;
        }

        public List<EvalResult> failures() {
            return results.stream().filter(r -> !r.passed()).toList();
        }

        @Override
        public String toString() {
            return String.format(
                "EvalReport[%s]: %.1f%% passed (%d/%d), avg score: %.2f, failures: %d",
                suiteName, passRate() * 100, passedCount, totalCount, 
                averageScore, failures().size()
            );
        }
    }
}
```

#### 使用示例：ProductSearch Skill 的 Eval

```java
package com.kuoge.tech.agent.skill.eval;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.kuoge.tech.agent.skill.eval.SkillEvalFramework.*;

/**
 * ProductSearch Skill 的 Eval 套件示例。
 * 
 * 在 Claude 的实践中，一个好的 Eval 套件应该：
 * 1. 覆盖所有意图类型（DIRECT_SEARCH, EXPLORATORY, STYLING...）
 * 2. 包含边界 case（如空结果、歧义查询、超长 query）
 * 3. 包含回归 case（历史上出错的 case，防止再次出错）
 */
public class ProductSearchEvalSuite {

    /**
     * 定义 EvalSuite。
     * 
     * 输入：用户 query 字符串
     * 输出：解析后的 SearchFilter
     * 期望：期望的 SearchFilter（用于精确匹配验证）
     */
    public static EvalSuite<String, SearchFilter, SearchFilter> createSuite() {
        
        List<EvalCase<String, SearchFilter, SearchFilter>> cases = List.of(
            // === 基础意图识别 ===
            new EvalCase<>("colorway-basic", 
                "Colorway 黑话理解 - bred",
                "想搞一双 bred，预算2k",
                new SearchFilter(null, "bred", BigDecimal.valueOf(2000), null),
                Map.of("category", "colorway", "difficulty", "easy")
            ),
            
            new EvalCase<>("colorway-advanced",
                "Colorway + 场景组合",
                "夏天海边穿的 Chicago 配色球鞋，不要太贵",
                new SearchFilter("sneakers", "Chicago", null, 
                    List.of("summer", "beach")),
                Map.of("category", "colorway+scene", "difficulty", "medium")
            ),
            
            // === 品牌别名 ===
            new EvalCase<>("brand-alias",
                "品牌别名识别 - 钩子",
                "钩子最新款跑步鞋",
                new SearchFilter("sneakers", null, null, null),
                Map.of("expectedBrand", "Nike", "difficulty", "medium")
            ),
            
            // === 边界 case ===
            new EvalCase<>("empty-query",
                "空查询处理",
                "",
                null,  // 期望：返回 null 或抛出特定异常
                Map.of("category", "edge_case", "difficulty", "hard")
            ),
            
            new EvalCase<>("ambiguous",
                "歧义查询 - AJ1 指 Air Jordan 1",
                "aj1 怎么搭",
                new SearchFilter(null, "Air Jordan 1", null, 
                    List.of("styling")),
                Map.of("category", "alias_disambiguation", "difficulty", "hard")
            )
        );

        // 精确匹配评估器
        Evaluator<String, SearchFilter, SearchFilter> exactMatchEvaluator = 
            (testCase, actual) -> {
                SearchFilter expected = testCase.expected();
                
                // 逐项比较
                boolean colorwayMatch = expected.colorway() == null 
                    ? actual.colorway() == null 
                    : expected.colorway().equalsIgnoreCase(actual.colorway());
                boolean categoryMatch = expected.category() == null 
                    ? actual.category() == null 
                    : expected.category().equals(actual.category());
                boolean priceMatch = expected.maxPrice() == null 
                    ? actual.maxPrice() == null 
                    : expected.maxPrice().equals(actual.maxPrice());
                
                boolean passed = colorwayMatch && categoryMatch && priceMatch;
                
                return new EvalResult(
                    testCase.id(),
                    passed,
                    passed ? 1.0 : 0.0,
                    actual.toString(),
                    passed ? null : buildFailureReason(expected, actual)
                );
            };

        return new EvalSuite<>("ProductSearch_FilterParse", cases, exactMatchEvaluator);
    }

    private static String buildFailureReason(SearchFilter expected, SearchFilter actual) {
        StringBuilder sb = new StringBuilder("Mismatch: ");
        if (!expected.colorway().equalsIgnoreCase(actual.colorway())) {
            sb.append("colorway expected=").append(expected.colorway())
              .append(" actual=").append(actual.colorway()).append("; ");
        }
        // ... 其他字段
        return sb.toString();
    }

    // ========== 简化数据模型 ==========
    public record SearchFilter(
        String category,
        String colorway,
        BigDecimal maxPrice,
        List<String> sceneTags
    ) {}
}
```

### 6.2 反馈闭环：隐式 vs 显式

Skill Runtime 的迭代优化依赖**双通道反馈**：

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    双通道反馈闭环                                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  隐式反馈通道（Implicit Feedback）                                           │
│  ├─ 工具调用成功率：模型选了工具但执行失败 → 工具描述/参数设计有问题       │
│  ├─ 循环次数：完成同样任务需要的步数增加 → Skill 推理链退化                │
│  ├─ Token 消耗：单次调用 token 数异常增长 → 上下文管理有问题               │
│  ├─ 用户后续行为：Skill 输出后用户是否继续对话/转化 → 输出质量指标         │
│  └─ 自动采集，量大，信号弱，适合发现「趋势性问题」                         │
│                                                                             │
│  显式反馈通道（Explicit Feedback）                                           │
│  ├─ 👍/👎 按钮：用户对 Skill 输出直接评价                                   │
│  ├─ Bad Case 标注：运营/测试人员标记错误输出，附上正确期望                 │
│  ├─ 人工 Review：抽样检查 Skill 执行记录，打分并记录问题类型               │
│  ├─ Evals 失败 case：自动化测试失败的用例，直接加入回归集                 │
│  └─ 需要人工介入，量小，信号强，适合「精确定位问题」                       │
│                                                                             │
│  闭环机制：                                                                 │
│  隐式反馈 ──→ 发现问题趋势（如 ProductSearch 成功率下降 5%）              │
│       │                                                                     │
│       ▼                                                                     │
│  显式反馈 ──→ 定位根因（如 colorway 词典缺少新品牌 "8LOME"）              │
│       │                                                                     │
│       ▼                                                                     │
│  生成优化任务 ──→ 更新知识库 / 调优 Prompt / 修复代码                      │
│       │                                                                     │
│       ▼                                                                     │
│  Evals 验证 ──→ 确认问题解决，防止回归                                     │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 6.3 版本管理与 A-B 测试

Skill Runtime 的版本管理遵循**「不可变部署」**原则（由 Governance 层执行）：

```java
package com.kuoge.tech.agent.skill.versioning;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill 版本管理器 - 对应 Claude 内部的 Skill Registry + 配置中心。
 * 
 * 核心原则：
 * 1. Skill 版本不可变（Immutable）：发布后不允许修改，只能创建新版本
 * 2. 配置与代码分离：Prompt、参数在配置中心管理，不在代码中硬编码
 * 3. 灰度发布：新版本先小流量验证，再逐步放量
 * 4. 一键回滚：发现问题可立即切回上一版本
 */
public class SkillVersionManager {

    // 内存中的 Skill 配置存储（生产环境应使用 Redis/Nacos/Apollo）
    private final Map<String, SkillVersionConfig> activeConfigs = new ConcurrentHashMap<>();
    
    // 版本历史（用于回滚）
    private final Map<String, Map<String, SkillVersionConfig>> versionHistory = new ConcurrentHashMap<>();

    /**
     * 注册新版本 Skill。
     * 
     * 对应 Claude 的 "Skill deployment" 流程。
     * 新版本初始状态为 INACTIVE，需要手动激活或灰度发布。
     */
    public void registerVersion(String skillId, String version, SkillVersionConfig config) {
        versionHistory.computeIfAbsent(skillId, k -> new ConcurrentHashMap<>())
                      .put(version, config);
        
        // 新注册版本不自动生效，需调用 activate 或 grayscale
    }

    /**
     * 全量激活某个版本。
     */
    public void activate(String skillId, String version) {
        Map<String, SkillVersionConfig> versions = versionHistory.get(skillId);
        if (versions == null || !versions.containsKey(version)) {
            throw new IllegalArgumentException("Skill version not found: " + skillId + "@" + version);
        }
        activeConfigs.put(skillId, versions.get(version));
    }

    /**
     * 灰度发布：按用户 ID 分桶。
     * 
     * 对应 Claude 的 "shadow traffic" 或 "canary deployment"。
     * 只有指定百分比的用户会被路由到新版本。
     */
    public SkillVersionConfig resolve(String skillId, String userId) {
        SkillVersionConfig active = activeConfigs.get(skillId);
        if (active == null) {
            throw new IllegalStateException("No active version for skill: " + skillId);
        }

        // 如果当前激活的是灰度版本，检查用户是否在灰度桶中
        if (active.status() == DeploymentStatus.GRAYSCALE) {
            int bucket = Math.abs(userId.hashCode()) % 100;
            if (bucket >= active.grayscalePercent()) {
                // 用户不在灰度桶中，回退到稳定版本
                return findStableVersion(skillId);
            }
        }
        
        return active;
    }

    /**
     * 一键回滚到上一个稳定版本。
     */
    public void rollback(String skillId) {
        SkillVersionConfig stable = findStableVersion(skillId);
        activeConfigs.put(skillId, stable);
    }

    private SkillVersionConfig findStableVersion(String skillId) {
        // 找到最新的非灰度版本
        return versionHistory.getOrDefault(skillId, Map.of()).values().stream()
            .filter(v -> v.status() == DeploymentStatus.ACTIVE)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No stable version found"));
    }

    // ========== 数据模型 ==========
    public record SkillVersionConfig(
        String skillId,
        String version,
        String promptTemplate,
        Map<String, Object> parameters,
        DeploymentStatus status,
        int grayscalePercent,      // 灰度百分比（0-100）
        Instant activatedAt
    ) {}

    public enum DeploymentStatus {
        INACTIVE,      // 已注册但未生效
        GRAYSCALE,     // 灰度中
        ACTIVE,        // 全量生效
        DEPRECATED     // 已废弃
    }
}
```

### 6.4 Anthropic 总结的 Agent Runtime 设计原则

基于 Anthropic 「Building effective agents」博客和内部实践，以下是 Claude **Agent Runtime** 设计的核心原则（供 Skill Runtime 层参考）：

```
┌─────────────────────────────────────────────────────────────────────────────┐
│              Claude Agent Runtime 设计十诫【Skill Runtime 层】               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. 【简单优先】能用规则解决就不用 LLM，能用单步完成就不用多步循环         │
│     → 每增加一步推理，失败概率呈指数增长                                   │
│                                                                             │
│  2. 【工具描述即 API】工具 description 的精确度直接决定模型调用质量        │
│     → 花 50% 的时间写工具描述，比花 50% 的时间调 Prompt 更有效             │
│                                                                             │
│  3. 【上下文是稀缺资源】优先保留高信噪比信息，主动丢弃无关历史              │
│     → 上下文窗口不是无限的，垃圾进垃圾出                                   │
│                                                                             │
│  4. 【可观测性优先】每次 Skill 执行必须生成结构化日志，支持全链路追踪       │
│     → 出了问题能复盘，是成功的必要条件                                     │
│                                                                             │
│  5. 【优雅降级】工具失败时，返回错误信息给模型，让模型自主决定下一步        │
│     → 不要直接抛异常中断整个任务                                           │
│                                                                             │
│  6. 【Eval 即契约】Runtime 的每次变更必须有 Evals 验证，防止回归            │
│     → 没有 Evals 的优化是盲目的                                            │
│                                                                             │
│  7. 【延迟敏感】同步 Runtime 的总耗时 = LLM 推理耗时 + 工具执行耗时         │
│     → 工具执行应异步/并行，LLM 推理应控制输出长度                          │
│                                                                             │
│  8. 【安全边界】Runtime 的工具执行必须在沙箱/隔离环境中，有明确的权限白名单 │
│     → 默认拒绝，显式授权                                                   │
│                                                                             │
│  9. 【版本不可变】已发布的 Skill 版本不允许修改，只能创建新版本              │
│     → 保证可复现性和可追溯性                                               │
│                                                                             │
│  10. 【人效最大化】Runtime 的终极目标不是替代人，而是让 1 个人管理 100 个 Skill│
│     → 治理工具（admin 后台）的投入回报率高于单个 Skill 的极致优化           │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 七、对 AI Agent 系统 的具体借鉴建议【三层映射】

### 7.1 架构层面

| Claude 实践 | AI Agent 系统 现状 | 建议改进 |
|---|---|---|
| Tool Definition 作为一等公民 | 已有 `@Tool` 注解，但 description 质量参差不齐 | 建立 Tool Description Review 机制，像审 API 一样审 Tool 描述 |
| 上下文工程（Context Engineering） | 依赖 Spring AI Advisor Chain | 增加「上下文压缩」Advisor，在对话过长时自动总结历史 |
| 多模态输入 | 视觉搜索 Agent 已规划 | 复用 Computer Use 的截图 → base64 → 模型理解模式 |
| 沙箱执行 | 价格抓取已用隔离环境 | 所有外部调用 Skill 统一接入沙箱执行框架 |

### 7.2 迭代优化层面

| Claude 实践 | AI Agent 系统 现状 | 建议改进 |
|---|---|---|
| Eval-Driven Development | 无系统化的 Eval 框架 | 优先建设 ProductSearch 和 SeoContentFactory 的 EvalSuite |
| 隐式 + 显式双反馈 | 只有执行日志，无用户反馈 | 在 skill-admin 中增加 👍/👎 按钮和 Bad Case 标注 |
| 版本不可变部署 | Prompt 可能硬编码在代码中 | 全部 Prompt 外置到 skill-admin 配置中心 |
| 灰度发布 | 无 | skill-admin A/B Test 模块第一版应支持流量分桶 |

### 7.3 与 skill-admin-backend 的联动

基于 Claude 的治理经验，skill-admin-backend 应重点建设以下能力：

1. **Skill Registry**：注册表 + 版本历史 + 依赖图谱
2. **Eval Runner**：自动化执行 EvalSuite，生成回归报告
3. **Prompt Studio**：在线编辑 Prompt + 实时预览效果 + 版本对比
4. **Feedback Inbox**：聚合隐式反馈（指标异常）和显式反馈（人工标注）
5. **Deployment Manager**：灰度/全量/回滚 + 与 AI Agent 系统 配置中心联动

---

## 八、参考资源

| 资源 | 类型 | 链接 |
|---|---|---|
| Building effective agents | Anthropic 官方博客 | https://www.anthropic.com/research/building-effective-agents |
| Anthropic Tool Use 文档 | 官方文档 | https://docs.anthropic.com/en/docs/build-with-claude/tool-use |
| Computer Use 技术报告 | 官方研究 | https://www.anthropic.com/news/computer-use |
| MCP (Model Context Protocol) | 开源协议 | https://modelcontextprotocol.io/ |
| DSPy | Stanford 开源框架 | https://github.com/stanfordnlp/dspy |
| OpenAI Evals | 开源评估框架 | https://github.com/openai/evals |

---

> **后记**：Claude 的设计理念与 Java/Spring 生态的「显式优于隐式」「可预测性优先」高度契合。在 AI Agent 系统 的建设中，不必追求最复杂的 Agent 架构，而应追求**最简单、最可观测、最可迭代**的 Skill Runtime 设计——这正是 Anthropic 反复强调的「从简单开始，只在必要时增加复杂度」。
