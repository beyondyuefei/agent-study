# Skill 治理与 ROI 归因：三系统联动设计方案

> **目标**：围绕「Skill 沉淀迭代/训练/优化」和「Skill 自然流量增长收益衡量」两大核心目标，明确 **psylos-agent**、**psylos1-skill-admin-backend**、**跨境电商业务中台** 三系统的职责边界、联动机制与数据闭环，并识别必须补充的扩展系统。
>
> **范围**：架构设计、交互协议、数据流、闭环机制、扩展系统必要性分析。

---

## 一、背景与核心问题

### 1.1 已有基础

| 系统 | 现状 | 职责 |
|---|---|---|
| **psylos-agent** | 已 MVP 运行，包含 ProductSearch、PriceCompare 等多个 Agent | AI Skill **执行引擎**，直接面向用户和业务场景产生价值 |
| **psylos1-skill-admin-backend** | 新建系统 | Skill **治理中枢**，负责 Skill 全生命周期管理、效果评估、迭代训练 |
| **跨境电商业务中台** | 已有成熟业务系统 | **业务底座**，提供商品、订单、用户、流量、SEO 等核心数据与埋点能力 |

### 1.2 两大核心目标

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        两大核心目标                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  目标一：Skill 的沉淀迭代 / 训练 / 优化                                      │
│  ├─ 每个 Skill 不是一次写好就永久有效，需要持续基于真实数据反馈进化          │
│  ├─ Prompt、RAG 知识、参数、模型版本需要可追踪、可回滚、可 A/B 测试        │
│  └─ 需要建立「执行 → 反馈 → 分析 → 优化 → 验证」的闭环                     │
│                                                                             │
│  目标二：衡量每个 Skill 带来的自然流量增长收益                               │
│  ├─ 13 个 Agent（Skill）对自然流量的贡献是交织的，必须可精确归因             │
│  ├─ 需要回答：SeoContentFactory 上个月贡献了多少自然搜索 GMV？               │
│  │            ShoppingGuide 的导购对话带来了多少首次购买？                   │
│  └─ 需要建立「Skill 执行 → 用户行为追踪 → 多触点归因 → ROI 看板」的闭环    │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.3 为什么必须三系统联动

单靠任何一方都无法同时实现两个目标：

- **只有 psylos-agent**：Skill 会沦为「黑盒脚本」，执行了但不知道好不好，无法迭代。
- **只有 skill-admin-backend**：没有真实执行数据和业务结果，治理变成「无米之炊」。
- **只有业务中台**：没有 Skill 级别的执行标记和归因能力，无法区分「是自然流量自己涨的」还是「某个 Skill 驱动的」。

**三系统必须形成「执行产生数据 → 数据驱动治理 → 治理优化执行」的飞轮。**

---

## 二、三系统定位与职责边界

### 2.1 职责边界总览

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          三系统职责边界                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────┐                                            │
│  │   psylos1-skill-admin       │  ← Skill 治理层（ Governance Layer ）      │
│  │        -backend             │                                            │
│  │  ├─ Skill 注册与元数据管理   │                                            │
│  │  ├─ Prompt / 参数版本控制    │                                            │
│  │  ├─ 执行效果分析与诊断       │                                            │
│  │  ├─ ROI 归因与收益看板       │                                            │
│  │  ├─ 迭代策略生成与发布       │                                            │
│  │  └─ A/B 测试编排             │                                            │
│  └─────────────┬───────────────┘                                            │
│                │ 下发 Skill 配置 / 接收执行报告                              │
│                ▼                                                             │
│  ┌─────────────────────────────┐                                            │
│  │      psylos-agent           │  ← Skill 执行层（ Execution Layer ）       │
│  │  ├─ AgentOrchestrator       │                                            │
│  │  ├─ 13 个 Skill 运行时       │                                            │
│  │  ├─ LLM Gateway / RAG       │                                            │
│  │  ├─ 执行日志与追踪（Trace）  │                                            │
│  │  └─ 上报执行事件到 admin     │                                            │
│  └─────────────┬───────────────┘                                            │
│                │ 调用业务 API / 上报用户行为                                  │
│                ▼                                                             │
│  ┌─────────────────────────────┐                                            │
│  │   跨境电商业务中台            │  ← 业务底座层（ Business Layer ）          │
│  │  ├─ 商品 / 订单 / 库存 API   │                                            │
│  │  ├─ 用户行为埋点（GA4/自研） │                                            │
│  │  ├─ SEO 数据（GSC / SEMrush）│                                            │
│  │  ├─ 流量来源与转化归因       │                                            │
│  │  └─ 财务数据（GMV / 毛利）   │                                            │
│  └─────────────────────────────┘                                            │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 各系统详细职责

#### psylos-agent（Skill 执行引擎）

| 模块 | 职责 |
|---|---|
| **AgentOrchestrator** | 接收用户请求，按意图路由到对应 Skill；注入 Skill 配置（版本号、参数、Prompt 模板） |
| **Skill 运行时** | 执行 13 个 Agent 的业务逻辑（SEO 生成、社媒内容、商品搜索、导购对话等） |
| **LLM Gateway** | 统一调用大模型，限流/熔断/重试/成本监控；按 Skill 维度统计 Token 消耗 |
| **RAG Pipeline** | 为 Skill 提供领域知识检索（潮流知识库、商品知识库、SEO 内容库、客服知识库） |
| **Trace & Event** | 每次 Skill 执行生成唯一 `traceId`，记录输入/输出/耗时/异常；通过 Event Bus 上报到 skill-admin |

#### psylos1-skill-admin-backend（Skill 治理中枢）

| 模块 | 职责 |
|---|---|
| **Skill Registry** | Skill 注册表：Skill 名称、版本、负责人、状态（草稿/灰度/全量/下线）、依赖关系 |
| **Prompt & Config Versioning** | Prompt 模板、系统参数、模型参数的版本化管理，支持一键回滚 |
| **Execution Analytics** | 聚合各 Skill 的执行量、成功率、延迟、异常率、Token 成本 |
| **ROI Attribution Engine** | 对接中台归因数据，计算每个 Skill 对自然流量各维度（SEO/社媒/引荐/直接访问）的贡献 GMV |
| **Iteration Planner** | 基于执行数据和用户反馈，生成 Skill 优化建议（Prompt 调优、RAG 补充、参数调整） |
| **A/B Test Manager** | 管理 Skill 版本的流量切分、实验周期、显著性检验、自动择优发布 |
| **Feedback Loop** | 收集运营人员的显式反馈（ thumbs up/down ）和用户行为的隐式反馈（转化率、停留时长） |

#### 跨境电商业务中台（业务底座）

| 模块 | 职责 |
|---|---|
| **Ecommerce API** | 商品详情、库存查询、订单创建、价格数据——供 psylos-agent Skill 调用 |
| **Behavior Tracking** | 全站用户行为埋点（页面浏览、点击、加购、下单、搜索 query），支持 `skill_trace_id` 透传 |
| **SEO Data Hub** | 对接 Google Search Console API、SEMrush API，提供关键词排名、收录量、自然流量数据 |
| **Attribution Core** | 多触点归因模型（首次触点 / 末次触点 / 线性归因 / Shapley Value），支持 Skill 级别归因 |
| **Financial Data** | GMV、毛利、客单价、获客成本——用于计算 Skill ROI |

---

## 三、系统交互图

### 3.1 全链路交互时序

```
用户/运营人员
    │
    ├─[1] 用户访问自建站（自然流量）──────────────┐
    │                                              │
    ▼                                              │
psylos-agent                                       │
    │                                              │
    ├─[2] AgentOrchestrator 路由到对应 Skill       │
    │    注入：skillId, version, promptTemplate,   │
    │           ragContext, configParams           │
    │                                              │
    ├─[3] Skill 执行（如 ProductSearch /           │
    │           SeoContentFactory）                │
    │    ├─ 调用 LLM Gateway 生成内容/回答         │
    │    ├─ 调用 RAG Pipeline 检索知识             │
    │    └─ 调用 中台 API 获取实时业务数据          │
    │                                              │
    ├─[4] 生成 traceId，记录执行日志               │
    │                                              │
    ├─[5] 上报 Event 到 skill-admin-backend        │
    │    { traceId, skillId, version, input,       │
    │      output, latency, tokenCost, error }     │
    │                                              │
    ├─[6] 向用户返回结果 ──────────────────────────┤
    │                                              ▼
    │                                         业务中台
    │                                              │
    ├─[7] 用户后续行为（浏览/加购/下单）            │
    │    埋点携带 skill_trace_id ────────────────→ │
    │                                              │
    │                                         [8] Attribution Core
    │                                              计算该 traceId 关联的 GMV/转化
    │                                              │
    │                                         [9] 定时同步归因结果到 skill-admin
    │                                              │
    ▼                                              │
skill-admin-backend ◄─────────────────────────────┘
    │
    ├─[10] 聚合：Skill 执行数据 + 业务归因数据
    │
    ├─[11] 生成 Skill 效果报告 & ROI 看板
    │        "SeoContentFactory v2.3 本月贡献：
    │         - 自然搜索流量 +23%
    │         - 着陆页 GMV $45,200
    │         - 投入：Token 成本 $120 → ROI 376:1"
    │
    ├─[12] 运营人员审阅报告，标记优化方向
    │
    ├─[13] 生成 Skill 优化任务（Prompt 调优 / RAG 补充）
    │
    ├─[14] A/B Test：发布 v2.4 到 10% 流量
    │
    └─[15] 全量发布 / 回滚
```

### 3.2 核心接口契约

#### A. skill-admin → psylos-agent：Skill 配置下发

```java
// Skill 配置响应（skill-admin 提供，agent 启动时/定时拉取）
public record SkillConfig(
    String skillId,           // "product_search"
    String version,           // "2.3.1"
    String promptTemplate,    // Prompt 模板（含占位符）
    Map<String, Object> params, // 业务参数（temperature, topK, fusionWeights 等）
    List<String> ragCollections, // 关联的 RAG 知识库集合
    String status,            // "ACTIVE" / "GRAYSCALE_10" / "DEPRECATED"
    String grayscaleRule      // 灰度规则（如 userId % 100 < 10）
) {}
```

#### B. psylos-agent → skill-admin：执行事件上报

```java
// 执行事件（每次 Skill 调用后异步上报）
public record SkillExecutionEvent(
    String traceId,
    String skillId,
    String version,
    Instant timestamp,
    long latencyMs,
    boolean success,
    String errorCode,
    int inputTokens,
    int outputTokens,
    String userId,            // 可选，用于归因
    String sessionId,         // 可选
    Map<String, Object> metadata  // Skill 自定义维度
) {}
```

#### C. 业务中台 → skill-admin：归因数据同步

```java
// 定时（每小时/每天）同步的归因结果
public record SkillAttributionRecord(
    String traceId,
    String skillId,
    String version,
    String attributionModel,  // "first_touch" / "last_touch" / "linear" / "shapley"
    BigDecimal attributedGmv, // 归因到该 Skill 的 GMV
    BigDecimal attributedProfit,
    int attributedOrders,
    int attributedSessions,
    String trafficSource,     // "organic_search" / "social_referral" / "direct"
    LocalDate eventDate
) {}
```

---

## 四、双目标闭环设计

### 4.1 目标一：Skill 沉淀迭代 / 训练 / 优化闭环

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Skill 沉淀迭代 / 训练 / 优化闭环                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   ┌──────────────┐                                                          │
│   │  生产执行     │  psylos-agent 各 Skill 在真实流量中运行                   │
│   │  (Run)       │  → 产生执行日志、用户交互数据、业务结果                   │
│   └──────┬───────┘                                                          │
│          │ 执行事件 + 埋点数据                                               │
│          ▼                                                                  │
│   ┌──────────────┐                                                          │
│   │  观测与度量   │  skill-admin 聚合数据                                    │
│   │  (Observe)   │  ├─ 技术指标：成功率、延迟、Token 成本、异常率            │
│   │              │  ├─ 业务指标：转化率、GMV、留存率                         │
│   │              │  └─ 用户体验：对话满意度、搜索无结果率、退货率            │
│   └──────┬───────┘                                                          │
│          │ 多维度分析                                                        │
│          ▼                                                                  │
│   ┌──────────────┐                                                          │
│   │  诊断与规划   │  skill-admin 自动/人工诊断                               │
│   │  (Diagnose)  │  ├─ ProductSearch 无结果率升高 → Query 理解模块需调优    │
│   │              │  ├─ SeoContentFactory 收录率低 → 内容质量评分规则需调整   │
│   │              │  ├─ ShoppingGuide 转化率下降 → Prompt 引导话术需优化      │
│   │              │  └─ RAG 检索 topK 准确率 62% → 知识库需补充新品牌数据    │
│   └──────┬───────┘                                                          │
│          │ 优化任务工单                                                      │
│          ▼                                                                  │
│   ┌──────────────┐                                                          │
│   │  迭代与训练   │  执行优化（可自动 + 人工）                                │
│   │  (Optimize)  │  ├─ Prompt Engineering：调优 system prompt / few-shot   │
│   │              │  ├─ RAG 知识库更新：补充新品牌、新发售、新穿搭内容        │
│   │              │  ├─ 参数调优：temperature、fusion weights、topK         │
│   │              │  ├─ 模型升级：Qwen-plus → Qwen-max 对某 Skill 生效      │
│   │              │  └─ 代码修复：修复工具调用异常、接口变更适配              │
│   └──────┬───────┘                                                          │
│          │ 新版本发布                                                        │
│          ▼                                                                  │
│   ┌──────────────┐                                                          │
│   │  验证与发布   │  skill-admin A/B Test 编排                               │
│   │  (Validate)  │  ├─ v2.4 灰度 5% → 对比 v2.3 的核心指标                 │
│   │              │  ├─ 显著性检验通过 → 扩大灰度 → 全量发布                │
│   │              │  └─ 指标恶化 → 自动回滚 → 重新诊断                        │
│   └──────┬───────┘                                                          │
│          │ 全量发布                                                          │
│          └──────────────────────────────────────→ 回到「生产执行」           │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**关键机制说明**：

| 机制 | 说明 | 负责系统 |
|---|---|---|
| **执行事件总线** | psylos-agent 每次 Skill 调用后，异步发送 `SkillExecutionEvent` 到 skill-admin（通过 Kafka/RabbitMQ），确保不影响主链路延迟 | psylos-agent + skill-admin |
| **Prompt 版本管理** | skill-admin 维护每个 Skill 的 Prompt 历史版本，支持 diff 对比、一键回滚。发布时通过配置中心推送到 psylos-agent | skill-admin |
| **RAG 知识库版本** | 知识库更新与 Skill 版本解耦。知识库独立迭代，Skill 可指定使用特定版本的知识库集合 | psylos-agent |
| **A/B Test 框架** | skill-admin 定义灰度规则（用户分桶、流量比例），psylos-agent 从配置中心读取并执行。实验结果自动计算置信区间 | skill-admin |
| **反馈双通道** | ① 隐式反馈：用户行为（转化率、停留时长）；② 显式反馈：运营人员在 admin 后台对 Skill 输出打标（好评/差评/修改建议） | 业务中台 + skill-admin |

### 4.2 目标二：Skill 自然流量增长收益衡量闭环

```
┌─────────────────────────────────────────────────────────────────────────────┐
│              Skill 自然流量增长收益衡量闭环                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  流量来源层                                                                 │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐       │
│  │ 自然搜索     │  │ 社媒引荐     │  │ 直接访问     │  │ 邮件回流     │       │
│  │ (SEO)       │  │ (Social)    │  │ (Direct)    │  │ (Email)     │       │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘       │
│         │                │                │                │               │
│         └────────────────┴────────────────┴────────────────┘               │
│                          │                                                  │
│                          ▼ 用户进入自建站                                    │
│  Skill 执行层                                                               │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  psylos-agent：用户旅程中触发多个 Skill                              │   │
│  │                                                                     │   │
│  │  例：用户从 Google 搜索 "8LOME hoodie styling" 进入                 │   │
│  │       ↓                                                             │   │
│  │  ① IntentSeo Agent 生成的 SEO 文章页（首次触点，Skill A）            │   │
│  │       ↓ 点击站内商品链接                                            │   │
│  │  ② ProductSearch Agent 被触发，用户搜索 "8LOME 卫衣"（Skill B）     │   │
│  │       ↓ 未找到想要的                                                │   │
│  │  ③ ShoppingGuide Agent 启动多轮对话，推荐搭配（Skill C）             │   │
│  │       ↓ 用户加购并下单                                              │   │
│  │                                                                     │   │
│  │  每个触点生成独立 traceId，全链路透传 session_skill_trace_chain     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                          │                                                  │
│                          ▼ 埋点上报（携带 traceId）                          │
│  归因计算层                                                                 │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  业务中台 Attribution Core                                          │   │
│  │                                                                     │   │
│  │  多触点归因模型（推荐 Shapley Value）                                │   │
│  │  ├─ 首次触点归因：识别流量来源 Skill（① 获功劳）                    │   │
│  │  ├─ 末次触点归因：识别转化催化剂 Skill（③ 获功劳）                  │   │
│  │  ├─ 线性归因：①②③ 均分功劳                                        │   │
│  │  └─ Shapley Value：基于边际贡献计算公平分配                         │   │
│  │                                                                     │   │
│  │  输出：每个 traceId 在各归因模型下的 attributed_gmv                 │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                          │                                                  │
│                          ▼ 定时同步                                          │
│  收益看板层                                                                 │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  skill-admin ROI 看板                                               │   │
│  │                                                                     │   │
│  │  ┌─────────────┬──────────┬──────────┬──────────┬────────────────┐  │   │
│  │  │   Skill     │ 自然流量  │ 归因 GMV │ Token成本 │    ROI         │  │   │
│  │  ├─────────────┼──────────┼──────────┼──────────┼────────────────┤  │   │
│  │  │SeoContent   │ +23%     │ $45,200  │ $120     │ 376:1          │  │   │
│  │  │ProductSearch│ +5%      │ $12,800  │ $45      │ 284:1          │  │   │
│  │  │ShoppingGuide│ +8%      │ $28,500  │ $180     │ 158:1          │  │   │
│  │  │SocialContent│ +15%     │ $18,200  │ $85      │ 214:1          │  │   │
│  │  └─────────────┴──────────┴──────────┴──────────┴────────────────┘  │   │
│  │                                                                     │   │
│  │  下钻能力：                                                          │   │
│  │  ├─ 按流量来源：SEO Skill vs 社媒 Skill vs 邮件 Skill               │   │
│  │  ├─ 按地域：北美 / 欧洲 / 日本                                       │   │
│  │  ├─ 按商品：哪些商品通过 Skill 获得了最多自然流量                    │   │
│  │  └─ 按时间：日/周/月趋势，对比不同版本的效果                        │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                          │                                                  │
│                          ▼ 指导决策                                          │
│  资源分配层                                                                 │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  基于 ROI 数据，运营决策：                                           │   │
│  │  ├─ ROI > 300:1 的 Skill → 增加资源投入（更多 Token / 更频繁执行）  │   │
│  │  ├─ ROI 100-300:1 → 维持当前投入，关注迭代空间                       │   │
│  │  ├─ ROI < 50:1 → 诊断问题，考虑重构或下线                            │   │
│  │  └─ 新 Skill 立项 → 参考同类 Skill ROI 基线评估可行性                │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**归因实现要点**：

| 要点 | 实现方式 |
|---|---|
| **Trace 透传** | psylos-agent 在 HTTP Header / URL Param / Cookie 中透传 `skill_trace_id`，确保从 Skill 触点到下单全链路可追踪 |
| **自然流量标记** | 中台在 GA4 或自研埋点中，将 `source=organic` 的访问与 `skill_trace_id` 关联，确保只计算自然流量带来的收益 |
| **多 Skill 贡献** | 一个用户旅程可能触发多个 Skill，必须使用多触点归因模型（推荐 Shapley Value），避免简单归因导致低估/高估 |
| **时间窗口** | 归因窗口设为 30 天（行业惯例），Skill 触发后 30 天内的转化计入该 Skill 收益 |
| **成本计算** | Skill 的 Token 成本 + 运行资源成本，从 LLM Gateway 和执行日志中精确统计 |

---

## 五、扩展系统必要性分析

基于上述双目标闭环，我们需要评估是否需要新增扩展系统。

### 5.1 RAG 知识库平台 —— **非常有必要，且需加强**

**必要性论证**：

RAG 不是「锦上添花」，而是 Skill 沉淀迭代的**核心数据载体**。在目标一的闭环中，「RAG 知识库更新」是 Skill 优化的四大手段之一（Prompt / RAG / 参数 / 模型）。

当前 psylos-agent 的 RAG 基础设施（DashVector + DocumentIndexer / Retriever）已具备基础能力，但需要升级为**企业级 RAG 知识库平台**：

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    RAG 知识库平台架构升级建议                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  当前状态（已有）                    目标状态（升级）                        │
│  ├─ DashVector 存储                 ├─ 多向量数据库管理                     │
│  ├─ 手工注入文档                    ├─ 自动化文档采集管道                   │
│  ├─ 单一文本索引                    ├─ 多模态索引（文本 + 图片 + 视频）     │
│  └─ 无版本概念                      └─ 知识库版本与 Skill 版本联动          │
│                                                                             │
│  升级后的知识库分类（与 Skill 强关联）：                                     │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 知识库              │ 内容                       │ 主要消费 Skill   │   │
│  ├─────────────────────┼────────────────────────────┼──────────────────┤   │
│  │ 潮流文化知识库       │ 品牌故事、设计师、穿搭风格   │ 全部 Skill      │   │
│  │ 商品知识库           │ 商品详情、尺码、面料、搭配   │ ProductSearch   │   │
│  │                     │                            │ ShoppingGuide   │   │
│  │ SEO 内容库           │ 已发布文章、关键词、效果     │ SeoContentFactory│   │
│  │                     │                            │ IntentSeo       │   │
│  │ 客服知识库           │ FAQ、政策、术语词典         │ ShoppingGuide   │   │
│  │                     │                            │ SizeGuide       │   │
│  │ 社媒热点知识库       │ Trending hashtags、热点事件  │ SocialContent   │   │
│  │                     │                            │ KolDiscovery    │   │
│  │ 竞品知识库           │ 竞品商品、价格、营销动态     │ CompetitorIntel │   │
│  │                     │                            │ DynamicPricing  │   │
│  │ 用户画像知识库       │ 审美偏好、购买历史、尺码档案 │ EmailMarketing  │   │
│  │                     │                            │ ChurnPrevent    │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  skill-admin 对 RAG 的管理能力：                                            │
│  ├─ 知识库与 Skill 的关联关系图谱                                           │
│  ├─ 知识库覆盖率分析：哪些新品牌/新品类尚未被索引                           │
│  ├─ 检索质量监控：topK 准确率、检索延迟、用户反馈                           │
│  └─ 自动化更新：商品上新 → 自动触发向量索引更新                             │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 5.2 A/B 测试平台 —— **有必要，skill-admin 内置**

不需要独立建设第三方 A/B 平台，skill-admin 内置轻量级 A/B 测试模块即可满足需求：

- **分桶维度**：userId / sessionId / 商品类目 / 地域
- **流量比例**：支持 1%-50% 灰度
- **对比指标**：成功率、转化率、GMV、Token 成本
- **统计检验**：自动计算 p-value，给出显著性结论
- **安全机制**：灰度期间异常率飙升 → 自动熔断回滚

### 5.3 统一数据归因平台 —— **有必要，建议业务中台建设**

当前业务中台的埋点和归因能力大概率是「平台级」的（区分渠道、广告计划），但缺乏「Skill 级」的归因粒度。建议由**业务中台扩展「Skill 归因模块」**：

- 接收 psylos-agent 上报的 `skill_trace_id`
- 在现有归因模型基础上，增加 Skill 维度
- 定时向 skill-admin 同步归因结果

**如果中台改造优先级低，可先在 skill-admin 中自建「归因计算服务」**：
- 从 GA4 / ClickHouse 中拉取带 `skill_trace_id` 的用户行为序列
- 自行计算多触点归因（Shapley Value 算法开源实现成熟）
- 作为过渡方案，等中台具备能力后再迁移

### 5.4 Prompt 版本管理 —— **skill-admin 内置**

Prompt 是 Skill 的核心资产之一，skill-admin 必须内置：

- Prompt 版本历史（Git-like diff）
- Prompt 模板变量管理
- Prompt 效果对比（v1 vs v2 的转化率差异）
- 敏感词/合规检查（自动扫描 Prompt 中是否包含不当内容）

### 5.5 运营反馈工作台 —— **skill-admin 内置**

运营人员是 Skill 迭代的关键参与者，需要可视化工作台：

- **执行抽样审查**：随机抽取 Skill 执行记录，运营可查看输入/输出并打分
- **Bad Case 标注**：标记错误输出，关联到具体优化任务
- **效果看板**：各 Skill 的北极星指标趋势图
- **一键优化**：对 Prompt 进行在线编辑 → 保存为新版本 → 启动 A/B Test

### 5.6 扩展系统总结

| 扩展系统 | 必要性 | 建设方式 | 优先级 |
|---|---|---|---|
| **RAG 知识库平台** | ⭐⭐⭐ 非常高 | 升级现有 DashVector 基础设施 + skill-admin 管理端 | P0 |
| **Skill 级归因能力** | ⭐⭐⭐ 非常高 | 业务中台扩展（优先）或 skill-admin 自建（过渡） | P0 |
| **A/B 测试模块** | ⭐⭐⭐ 高 | skill-admin 内置 | P1 |
| **Prompt 版本管理** | ⭐⭐ 高 | skill-admin 内置 | P1 |
| **运营反馈工作台** | ⭐⭐ 高 | skill-admin 内置 | P1 |
| **自动化知识采集管道** | ⭐⭐ 中 | 独立定时任务服务，接入 SeoContentFactory / CompetitorIntel | P2 |
| **多模态向量索引** | ⭐⭐ 中 | DashVector 或切换至支持多模态的向量库（如 Milvus） | P2 |

---

## 六、数据流与数据模型

### 6.1 核心数据流全景

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         三系统核心数据流                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  [数据源]                    [流转方向]                  [消费方]           │
│  ───────────────────────────────────────────────────────────────────────   │
│                                                                             │
│  商品/订单/库存/用户数据     中台 ──────API──────→   psylos-agent           │
│  （实时查询）                                                               │
│                                                                             │
│  用户行为埋点数据            中台 ──────ETL──────→   skill-admin            │
│  （含 skill_trace_id）                                                      │
│                                                                             │
│  SEO/社媒/竞品外部数据       agent Skill ──采集──→   业务中台/数据仓库      │
│                                                                             │
│  Skill 执行事件              agent ───Event Bus──→   skill-admin            │
│  （traceId / 输入输出 / 成本）                                              │
│                                                                             │
│  Skill 配置（Prompt/参数）   admin ──配置中心──→     psylos-agent           │
│                                                                             │
│  归因结果                    中台 ───定时同步──→     skill-admin            │
│  （traceId → attributed_gmv）                                               │
│                                                                             │
│  RAG 知识库文档              admin ──索引任务──→     DashVector             │
│  （版本化、多集合）                                                         │
│                                                                             │
│  优化任务/工单               admin ───内部系统──→    研发团队               │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 6.2 skill-admin 核心数据模型

```java
// ========== Skill 注册表 ==========
public record SkillRegistry(
    String skillId,              // 唯一标识，如 "product_search"
    String name,                 // 显示名称
    String description,          // 功能描述
    String owner,                // 负责人
    String status,               // DRAFT / GRAYSCALE / ACTIVE / DEPRECATED
    String currentVersion,       // 当前线上版本
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}

// ========== Skill 版本 ==========
public record SkillVersion(
    String skillId,
    String version,              // 语义化版本，如 "2.3.1"
    String promptTemplate,       // Prompt 模板
    Map<String, Object> config,  // 业务参数 JSON
    List<String> ragCollections, // 关联知识库集合
    String changelog,            // 变更说明
    String createdBy,
    LocalDateTime createdAt
) {}

// ========== Skill 执行统计（按小时聚合） ==========
public record SkillExecutionStats(
    String skillId,
    String version,
    LocalDateTime hour,          // 聚合小时
    long executionCount,         // 执行次数
    long successCount,
    long errorCount,
    double avgLatencyMs,
    long totalInputTokens,
    long totalOutputTokens,
    BigDecimal estimatedTokenCost  // USD
) {}

// ========== Skill ROI 归因（按天聚合） ==========
public record SkillRoiAttribution(
    String skillId,
    String version,
    LocalDate date,
    String trafficSource,        // organic_search / social_referral / direct / email
    String attributionModel,     // first_touch / last_touch / linear / shapley
    long attributedSessions,     // 归因会话数
    long attributedOrders,       // 归因订单数
    BigDecimal attributedGmv,    // 归因 GMV
    BigDecimal attributedProfit, // 归因毛利
    BigDecimal tokenCost,        // Token 成本
    BigDecimal infraCost,        // 计算资源成本
    double roiRatio              // (attributedProfit - cost) / cost
) {}

// ========== A/B 实验 ==========
public record SkillExperiment(
    String experimentId,
    String skillId,
    String controlVersion,       // 对照组版本
    String treatmentVersion,     // 实验组版本
    String trafficSplitRule,     // 灰度规则
    int trafficPercent,          // 实验流量百分比
    ExperimentStatus status,     // RUNNING / CONCLUDED / ROLLED_BACK
    LocalDateTime startTime,
    LocalDateTime endTime,
    Map<String, Double> results  // 各指标对比结果
) {}

// ========== 知识库集合 ==========
public record KnowledgeCollection(
    String collectionId,
    String name,
    String description,
    String vectorStoreType,      // DASHVECTOR / MILVUS
    List<String> associatedSkills, // 关联的 Skill ID
    long documentCount,
    String currentVersion,       // 知识库版本
    LocalDateTime lastIndexedAt
) {}
```

---

## 七、实施路线图

### 7.1 Phase 1：基础设施打通（1-2 个月）

**目标**：建立三系统联动的最小可用闭环。

| 任务 | 负责系统 | 说明 |
|---|---|---|
| Skill Registry 上线 | skill-admin | 注册已有 Agent 为 Skill，建立元数据 |
| 执行事件上报链路 | psylos-agent → skill-admin | 每次 Skill 调用后异步上报 `SkillExecutionEvent` |
| Prompt 外置化 | psylos-agent + skill-admin | 将硬编码 Prompt 提取到 skill-admin 配置中心，支持在线修改 |
| traceId 全链路透传 | psylos-agent + 中台 | 在埋点和 API 调用中透传 `skill_trace_id` |
| 基础 ROI 看板 | skill-admin | 先采用「末次触点归因」，展示各 Skill 的 GMV 和 Token 成本 |

**里程碑**：运营人员可以在 skill-admin 后台看到各 Skill 的执行量、成功率、基础 ROI。

### 7.2 Phase 2：闭环能力建设（2-3 个月）

**目标**：实现「执行 → 反馈 → 优化 → 验证」完整闭环。

| 任务 | 负责系统 | 说明 |
|---|---|---|
| A/B Test 模块 | skill-admin | 支持 Skill 版本灰度发布、指标对比、自动回滚 |
| 多触点归因 | 中台 或 skill-admin | 实现 Shapley Value 归因，精确计算 Skill 贡献 |
| RAG 知识库管理端 | skill-admin | 知识库集合 CRUD、覆盖率分析、检索质量监控 |
| 运营反馈工作台 | skill-admin | Bad Case 标注、执行抽样审查、效果趋势图 |
| Prompt 版本管理 | skill-admin | 版本历史、diff 对比、一键回滚 |

**里程碑**：一个 Skill 从发现问题到发布优化版本，可在 1 周内完成闭环。

### 7.3 Phase 3：智能化演进（3-6 个月）

**目标**：让 Skill 治理从「人工驱动」走向「数据智能驱动」。

| 任务 | 负责系统 | 说明 |
|---|---|---|
| 自动诊断 | skill-admin | 基于规则 + LLM，自动分析 Skill 效果下降原因并生成优化建议 |
| 知识库自动更新 | psylos-agent + skill-admin | 商品上新/竞品变化/热点事件 → 自动触发知识库索引更新 |
| 自动参数调优 | skill-admin | 基于贝叶斯优化，自动搜索最优 temperature / topK / fusion weights |
| Skill 组合优化 | skill-admin | 分析用户旅程中多 Skill 组合的效果，推荐最优编排策略 |
| 预测性维护 | skill-admin | 预测 Skill 效果衰退趋势，提前触发优化（而非等效果下降后再行动） |

**里程碑**：80% 的 Skill 优化可由系统自动识别并生成方案，运营人员只需审批。

---

## 八、关键设计决策

### 8.1 为什么 skill-admin 不直接替代 psylos-agent 的编排能力？

skill-admin 是「治理中枢」，不是「执行引擎」。如果让 skill-admin 直接参与请求编排，会引入跨网络调用的延迟和不稳定性。正确的边界是：

- **psylos-agent 自治执行**：从配置中心读取 Skill 配置后，本地完成路由、LLM 调用、RAG 检索、业务 API 调用。
- **skill-admin 离线治理**：通过异步事件和分析数据，驱动 Skill 迭代，不介入实时请求链路。

### 8.2 为什么归因能力建议由中台建设而非 skill-admin 自建？

归因需要访问全量用户行为数据和订单数据，这些数据天然属于业务中台。如果 skill-admin 自建归因，需要：

1. 从多个数据源（GA4、订单系统、埋点系统）拉取海量数据
2. 维护与中台一致的用户身份映射
3. 重复建设归因计算引擎

**推荐方案**：中台扩展 Skill 维度归因（改造成本低，只需在现有归因模型中增加 `skill_trace_id` 字段），skill-admin 消费归因结果。如果中台排期不足，skill-admin 可先行自建过渡方案。

### 8.3 为什么 RAG 知识库版本要与 Skill 版本解耦？

假设 SeoContentFactory v2.3 依赖「潮流文化知识库 v1.2」，如果知识库更新到 v1.3 时发现召回质量下降，需要能独立回滚知识库版本，而不影响 Skill 代码和 Prompt。

**设计**：SkillVersion 中只记录 `ragCollections = ["streetwear_knowledge:v1.2"]`，知识库独立版本化管理。

### 8.4 为什么需要同时支持「隐式反馈」和「显式反馈」？

- **隐式反馈**（转化率、停留时长）是大规模、自动化的，但信号弱——用户没转化不一定是 Skill 问题，可能是商品本身问题。
- **显式反馈**（运营打标、用户评分）是稀疏的、需要人工介入的，但信号强——能直接定位到具体错误。

两者互补：隐式反馈用于「发现问题」，显式反馈用于「定位根因」。

---

## 九、总结

本文档围绕「Skill 沉淀迭代」和「Skill 自然流量收益衡量」两大目标，设计了三系统联动方案：

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              三系统联动总览                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   psylos-agent              psylos1-skill-admin         跨境电商业务中台    │
│   ├─ Skill 执行引擎    ←──→ ├─ Skill 治理中枢      ←──→ ├─ 业务数据底座   │
│   ├─ 产生执行数据           ├─ 分析 & 优化              ├─ 提供归因数据     │
│   └─ 上报事件               └─ 下发新版本配置           └─ 提供业务 API     │
│                                                                             │
│   飞轮效应：                                                                │
│   ┌─────────────────────────────────────────────────────────────────────┐   │
│   │  Skill 执行 → 数据上报 → 效果分析 → 优化迭代 → 新版本发布 → 更好的 │   │
│   │  Skill 执行 → 更多自然流量 → 更多数据 → 更精准优化 → ...           │   │
│   └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│   扩展系统优先级：                                                          │
│   P0: RAG 知识库平台升级 + Skill 级归因能力                                │
│   P1: A/B 测试 + Prompt 版本管理 + 运营反馈工作台                          │
│   P2: 自动化知识采集 + 多模态向量索引 + 智能化诊断                         │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**下一步行动建议**：

1. **立即启动**：skill-admin 的 Skill Registry 和 Prompt 外置化，这是所有后续能力的基础。
2. **本月完成**：psylos-agent 执行事件上报链路打通，确保数据可观测。
3. **下月完成**：业务中台埋点接入 `skill_trace_id`，启动基础 ROI 看板建设。
4. **持续迭代**：以「1 个 Skill 的完整优化闭环跑通」为首个验证目标（建议选择 ProductSearch 或 SeoContentFactory），验证后再规模化推广到全部 13 个 Agent。
