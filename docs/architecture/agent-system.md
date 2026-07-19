# Agent 系统

本文描述当前 Agent 的实现结构与修改入口。产品定位、三层能力模型、单 Agent 决策和多 Agent 演进条件见 [Agent 产品定位与演进设计](../design/2026-07-13-agent-product-positioning-and-evolution-design.md)。

## 阅读时机

修改角色对话、上下文拼装、工具执行、会话记忆、认知扩展、主动行为或执行追踪前阅读本章。只改前端 Agent 界面时，先读[前端架构](frontend.md)，再用本章确认 WebSocket 协议和后端能力边界。

## 读完能回答的问题

- 一次 Agent 消息如何从短期票据和 WebSocket 进入 Core，并流式返回结果？
- Core 中上下文、推理循环、LLM 和工具分别由哪个组件负责？
- 哪些上下文贡献者始终属于 Core，哪些由扩展开关加入？
- Memory、Experience、Knowledge Graph、Mood、Proactive、Trace 和 Quality 的状态与条件边界是什么？
- `LLM_ENABLED`、`agent.*` 与 `agent.extensions.*` 分别控制什么？
- 修改某类行为时应从哪个类和测试开始？

## 一次对话的主链

当前实现不是单个类中的简化 ReAct 循环，而是分层的 Core 运行时：

```text
POST /api/v1/agent/ws-ticket
  → 一次性短期 ticket
GET /api/v1/agent/ws?ticket=...
  → AgentWebSocketHandler
  → 会话 ID 校验、限流、心跳、页面上下文与 AgentMemoryStore
  → ChthollyAgent
  → ContextEngine
  → AgentLoopExecutor
       ├─ AgentLlmInvoker：生成 Think / Act / Final 决策
       └─ AgentToolExecutor：校验并限时执行 AgentTool
  → ChthollyAgent 流式生成最终自然语言答案
  → think / act / observe / delta / final / error 事件
  → Redis 会话记忆与异步 trace 持久化
```

- [`AgentWsTicketController`](../../apps/server/src/main/java/com/chtholly/agent/api/AgentWsTicketController.java) 为已认证用户签发短期、一次性 WebSocket ticket；[`AgentWebSocketConfig`](../../apps/server/src/main/java/com/chtholly/agent/config/AgentWebSocketConfig.java) 注册 `/api/v1/agent/ws`。
- [`AgentWebSocketHandler`](../../apps/server/src/main/java/com/chtholly/agent/ws/AgentWebSocketHandler.java) 消费 ticket、解析消息与页面上下文，管理心跳、会话限流和连接状态；[`AgentChatSessionSupport`](../../apps/server/src/main/java/com/chtholly/agent/ws/AgentChatSessionSupport.java) 只负责前端会话 ID 的格式校验，不是独立存储层。
- [`ChthollyAgent`](../../apps/server/src/main/java/com/chtholly/agent/ChthollyAgent.java) 是单轮编排边界：建立 trace、收集工具、构造上下文、调用有界循环、流式生成最终答案并更新会话记忆。
- [`ContextEngine`](../../apps/server/src/main/java/com/chtholly/agent/context/ContextEngine.java) 按稳定顺序合成 system prompt，并拒绝重复名称或重复顺序的贡献者。
- [`AgentLoopExecutor`](../../apps/server/src/main/java/com/chtholly/agent/runtime/AgentLoopExecutor.java) 执行有最大步数的 Think-Act-Observe；[`AgentLlmInvoker`](../../apps/server/src/main/java/com/chtholly/agent/runtime/AgentLlmInvoker.java) 统一模型选项、超时与同步/流式调用。
- [`AgentToolExecutor`](../../apps/server/src/main/java/com/chtholly/agent/runtime/AgentToolExecutor.java) 负责参数校验、用户上下文传播、工具超时和结果归一化；工具契约是 [`AgentTool`](../../apps/server/src/main/java/com/chtholly/agent/AgentTool.java)，当前站内/RAG/Bangumi 实现在 [`agent/tools`](../../apps/server/src/main/java/com/chtholly/agent/tools)。

循环返回 `FINAL_READY` 后，最终答案仍由 `ChthollyAgent` 单独流式生成。因此修改决策协议与修改最终表达风格是两个不同入口，不应把两者重新耦合回一个巨型循环。

## ContextContributor 顺序与职责

顺序常量以 [`ContextOrder`](../../apps/server/src/main/java/com/chtholly/agent/context/ContextOrder.java) 为准，`ContextEngine` 会按 `order()` 排序：

| 顺序 | 贡献者 | 边界 |
|------|--------|------|
| 100 | [`IdentityContextContributor`](../../apps/server/src/main/java/com/chtholly/agent/context/contributor/IdentityContextContributor.java) | 固定角色灵魂与身份约束 |
| 200 | [`RelationshipContextContributor`](../../apps/server/src/main/java/com/chtholly/agent/context/contributor/RelationshipContextContributor.java) | 用户关系、情绪与角色状态；失败时可降级为空 |
| 250 | [`SeasonalContextContributor`](../../apps/server/src/main/java/com/chtholly/agent/mood/SeasonalContextContributor.java) | 季节感受；仅 Mood 扩展启用时存在 |
| 300 | [`PageContextContributor`](../../apps/server/src/main/java/com/chtholly/agent/context/contributor/PageContextContributor.java) | 前端传入的当前页面上下文 |
| 350 | [`GraphContextContributor`](../../apps/server/src/main/java/com/chtholly/agent/graph/GraphContextContributor.java) | 知识图谱邻域；仅 Graph 扩展启用时存在 |
| 400 | [`KnowledgeContextContributor`](../../apps/server/src/main/java/com/chtholly/agent/context/contributor/KnowledgeContextContributor.java) | Anchor/长期相关知识 |
| 500 | [`ProceduralContextContributor`](../../apps/server/src/main/java/com/chtholly/agent/context/contributor/ProceduralContextContributor.java) | 学到的程序性规则；无内容时为空 |
| 600 | [`ToolsContextContributor`](../../apps/server/src/main/java/com/chtholly/agent/context/contributor/ToolsContextContributor.java) | 可用工具及参数协议 |
| 700 | [`HistoryContextContributor`](../../apps/server/src/main/java/com/chtholly/agent/context/contributor/HistoryContextContributor.java) | 当前会话历史 |
| 800 | [`QuestionContextContributor`](../../apps/server/src/main/java/com/chtholly/agent/context/contributor/QuestionContextContributor.java) | 本轮用户问题，保持在 prompt 尾部 |

关闭全部扩展后仍保留 Identity、Relationship、Page、Knowledge、Procedural、Tools、History、Question 八个 Core 贡献者；这一最小上下文由 [`AgentCoreOnlyContextTest`](../../apps/server/src/test/java/com/chtholly/agent/context/AgentCoreOnlyContextTest.java) 固定。

## Core 与扩展边界

Core 包括交互入口、上下文合同、运行时、工具合同、会话记忆和可观测性编排。扩展 Spring 组件由 [`AgentExtensionComponent`](../../apps/server/src/main/java/com/chtholly/agent/config/AgentExtensionComponent.java) 标记；[`AgentExtensionBoundaryArchitectureTest`](../../apps/server/src/test/java/com/chtholly/agent/config/AgentExtensionBoundaryArchitectureTest.java) 防止 Core 上下文反向依赖扩展实现，并要求组合条件使用类型化开关。

[`AgentExtensionProperties`](../../apps/server/src/main/java/com/chtholly/agent/config/AgentExtensionProperties.java) 定义七组默认启用的开关；[`AgentDomainConfiguration`](../../apps/server/src/main/java/com/chtholly/agent/config/AgentDomainConfiguration.java) 注册领域配置与扩展配置：

| 枚举/属性段 | 主要包/能力 | 组合依赖 |
|-------------|-------------|----------|
| `CONTENT` / `content` | `content`、主题聚类与内容理解 API | 单独启停 |
| `GRAPH` / `graph` | `graph`、知识抽取/图谱查询/图上下文 | 单独启停 |
| `LEARNING` / `learning` | `learning` 与程序性记忆 | 单独启停 |
| `EXPERIENCE` / `experience` | `experience`、经验生成/时间线 | 单独启停 |
| `MOOD` / `mood` | `mood`、季节上下文与互动状态 | 单独启停 |
| `COMMUNITY_ACTIONS` / `community-actions` | `comment`、`notification` 等社区动作 | 单独启停 |
| `PROACTIVE` / `proactive` | `proactive` 主动触达 | 实际主动服务同时要求 `proactive + experience + community-actions` |

此外，[`CognitiveEngine`](../../apps/server/src/main/java/com/chtholly/agent/cognitive/CognitiveEngine.java) 同时要求 `learning + experience`。组合条件由 [`ConditionalOnAgentExtensions`](../../apps/server/src/main/java/com/chtholly/agent/config/ConditionalOnAgentExtensions.java) 与 [`OnAgentExtensionsCondition`](../../apps/server/src/main/java/com/chtholly/agent/config/OnAgentExtensionsCondition.java) 执行“全部满足”语义，缺失属性沿用默认启用。

## 状态、扩展与运维面

### Memory 与 Experience

- [`AgentMemoryStore`](../../apps/server/src/main/java/com/chtholly/agent/memory/AgentMemoryStore.java) 以 `userId + chatSessionId` 为键，Redis List 是跨进程会话数据，Caffeine 只加速热会话；写入用 `RPUSH + LTRIM`，写入和 Redis 冷读会刷新 Redis TTL。Caffeine 热读只刷新本地 `expireAfterAccess`，不访问 Redis，因此持续命中本地缓存时 Redis key 仍可能过期。它仅在 `llm.enabled=true` 时注册，并直接依赖 Redis，没有另一套内存持久化降级实现。
- [`AgentConversationMemory`](../../apps/server/src/main/java/com/chtholly/agent/memory/AgentConversationMemory.java) 是单轮使用的会话视图；长期程序性知识由 [`ProceduralMemoryService`](../../apps/server/src/main/java/com/chtholly/agent/memory/ProceduralMemoryService.java) 承担并受 Learning 扩展控制。
- Experience 是可选的长期经历域，入口包括 [`ExperienceGenerator`](../../apps/server/src/main/java/com/chtholly/agent/experience/ExperienceGenerator.java)、[`ExperienceService`](../../apps/server/src/main/java/com/chtholly/agent/cognitive/ExperienceService.java) 与 [`AgentExperienceController`](../../apps/server/src/main/java/com/chtholly/agent/api/AgentExperienceController.java)。它与聊天历史不是同一存储概念。

### Knowledge Graph、Mood 与 Proactive

- Knowledge Graph 由 [`KnowledgeGraphService`](../../apps/server/src/main/java/com/chtholly/agent/graph/KnowledgeGraphService.java) 与 [`KnowledgeGraphRepository`](../../apps/server/src/main/java/com/chtholly/agent/graph/KnowledgeGraphRepository.java) 管理；`GraphContextContributor` 只把相关邻域投影进 prompt。
- Mood 由 [`MoodEngine`](../../apps/server/src/main/java/com/chtholly/agent/mood/MoodEngine.java)、[`SeasonService`](../../apps/server/src/main/java/com/chtholly/agent/mood/SeasonService.java) 和季节上下文协作，不属于 Core 必需链路。
- Proactive 的调度门面是 [`ProactiveTriggerEngine`](../../apps/server/src/main/java/com/chtholly/agent/proactive/ProactiveTriggerEngine.java)，情绪、内容、社交决策拆分到同包服务；消息通过 [`NotificationService`](../../apps/server/src/main/java/com/chtholly/agent/notification/NotificationService.java) 与 WebSocket 待发通知协作。

### Trace 与 Quality

- `ChthollyAgent` 为一次运行建立 [`AgentExecutionTrace`](../../apps/server/src/main/java/com/chtholly/agent/observability/AgentExecutionTrace.java)，[`AgentObservationService`](../../apps/server/src/main/java/com/chtholly/agent/observability/AgentObservationService.java) 建立 Agent 父 Span，以及 LLM、Tool、Skill 选择、三路检索和草稿预览/应用子 Span；[`TracePersistenceService`](../../apps/server/src/main/java/com/chtholly/agent/trace/TracePersistenceService.java) 异步落库并定时挖掘失败模式。
- `trace_payload` 保存组件版本、Skill 选择/校验、三路检索状态、Evidence 标识、引用校验、固定失败类型、运行模式和脱敏输入指纹。完整问题、页面上下文、回答、草稿正文和工具原始输入/输出不进入新增字段；工具摘要只保存 SHA-256 与字符数。
- [`trace-replay.ps1`](../../scripts/benchmark/trace-replay.ps1) 从固定历史提交创建归档，只注入同一测试观察层，并实际执行历史 `HybridSearchService`、`ChthollyAgent`、MySQL Trace 持久化与查询回读。检索上游、LLM 和 Observation 使用确定性替身且外部模型调用为 0；manifest 绑定 subject tree、生产源码摘要、harness/dataset blob、回归测试日志和输入指纹。四次观测全部满足约束时证据等级为 `REAL_TRACE`，但样本仍保持 `CANDIDATE_REQUIRES_OWNER_REVIEW / COLLECTED_UNREVIEWED`。具体边界和命令见[最小基准与评测入口](../../benchmarks/README.md)。
- Quality 不是聊天循环的一步。[`LlmQualityEvaluationService`](../../apps/server/src/main/java/com/chtholly/agent/quality/LlmQualityEvaluationService.java) 优先使用可用 `ChatClient`，不可用或失败时退回 [`HeuristicQualityEvaluationService`](../../apps/server/src/main/java/com/chtholly/agent/quality/HeuristicQualityEvaluationService.java)，所以调用者不应假设一定发生 LLM 请求。

## 配置来源与启用边界

| 来源 | 负责内容 |
|------|----------|
| [`application.yml`](../../apps/server/src/main/resources/application.yml) | `LLM_ENABLED` 同时绑定 `llm.enabled` 与 `rag.enabled`；`agent.model`、超时、最大步数、响应长度、memory 上限/TTL、流式节流、工具超时 |
| [`agent-domain.yml`](../../apps/server/src/main/resources/agent-domain.yml) | `agent.domain.*` 的系统提示词、错误消息、Bangumi 文案与上下文标签 |
| [`AgentDomainConfig`](../../apps/server/src/main/java/com/chtholly/agent/config/AgentDomainConfig.java) | 对 `agent.domain.*` 的类型化绑定和占位符渲染 |
| [`AgentExtensionProperties`](../../apps/server/src/main/java/com/chtholly/agent/config/AgentExtensionProperties.java) | `agent.extensions.<group>.enabled`，七组缺省均为 `true` |

`LLM_ENABLED=false` 时，`ChthollyAgent`、运行时、WebSocket handler/config/ticket store、会话 memory 和 Agent 工具等交互主链 bean 不注册，博客与社区主链仍可运行。扩展开关与 `LLM_ENABLED` 是两个维度：多个扩展组件只看 `agent.extensions.*`，有些通过可选 `ChatClient` 或确定性回退工作；不要把“关闭聊天入口”误写成“所有扩展 bean 都关闭”。若需要最小 Agent Core Spring 上下文，应显式关闭七个扩展开关。

## 修改路由

| 修改场景 | 先看实现 | 代表性测试 |
|----------|----------|------------|
| WebSocket 鉴权、消息或会话 | [`AgentWebSocketHandler`](../../apps/server/src/main/java/com/chtholly/agent/ws/AgentWebSocketHandler.java)、[`AgentWsTicketStore`](../../apps/server/src/main/java/com/chtholly/agent/ws/AgentWsTicketStore.java) | [`AgentWebSocketHandlerTest`](../../apps/server/src/test/java/com/chtholly/agent/ws/AgentWebSocketHandlerTest.java)、[`AgentChatSessionSupportTest`](../../apps/server/src/test/java/com/chtholly/agent/ws/AgentChatSessionSupportTest.java) |
| 单轮编排与最终流式回答 | [`ChthollyAgent`](../../apps/server/src/main/java/com/chtholly/agent/ChthollyAgent.java) | [`ChthollyAgentTest`](../../apps/server/src/test/java/com/chtholly/agent/ChthollyAgentTest.java) |
| Think-Act-Observe 协议 | [`AgentLoopExecutor`](../../apps/server/src/main/java/com/chtholly/agent/runtime/AgentLoopExecutor.java) | [`AgentLoopExecutorTest`](../../apps/server/src/test/java/com/chtholly/agent/runtime/AgentLoopExecutorTest.java) |
| LLM 或工具超时/参数 | [`AgentLlmInvoker`](../../apps/server/src/main/java/com/chtholly/agent/runtime/AgentLlmInvoker.java)、[`AgentToolExecutor`](../../apps/server/src/main/java/com/chtholly/agent/runtime/AgentToolExecutor.java) | [`AgentLlmInvokerTest`](../../apps/server/src/test/java/com/chtholly/agent/runtime/AgentLlmInvokerTest.java)、[`AgentToolExecutorTest`](../../apps/server/src/test/java/com/chtholly/agent/runtime/AgentToolExecutorTest.java) |
| Prompt 顺序或贡献者 | [`ContextEngine`](../../apps/server/src/main/java/com/chtholly/agent/context/ContextEngine.java)、[`ContextOrder`](../../apps/server/src/main/java/com/chtholly/agent/context/ContextOrder.java) | [`ContextEngineTest`](../../apps/server/src/test/java/com/chtholly/agent/context/ContextEngineTest.java)、[`ContextContributorContractTest`](../../apps/server/src/test/java/com/chtholly/agent/context/ContextContributorContractTest.java) |
| Core/扩展开关边界 | [`AgentExtensionProperties`](../../apps/server/src/main/java/com/chtholly/agent/config/AgentExtensionProperties.java)、[`ConditionalOnAgentExtensions`](../../apps/server/src/main/java/com/chtholly/agent/config/ConditionalOnAgentExtensions.java) | [`AgentExtensionPropertiesTest`](../../apps/server/src/test/java/com/chtholly/agent/config/AgentExtensionPropertiesTest.java)、[`AgentExtensionBoundaryArchitectureTest`](../../apps/server/src/test/java/com/chtholly/agent/config/AgentExtensionBoundaryArchitectureTest.java) |
| Redis 会话记忆 | [`AgentMemoryStore`](../../apps/server/src/main/java/com/chtholly/agent/memory/AgentMemoryStore.java) | [`AgentMemoryStoreTest`](../../apps/server/src/test/java/com/chtholly/agent/memory/AgentMemoryStoreTest.java) |
| 主动行为 | [`ProactiveTriggerEngine`](../../apps/server/src/main/java/com/chtholly/agent/proactive/ProactiveTriggerEngine.java) | [`ProactiveTriggerEngineTest`](../../apps/server/src/test/java/com/chtholly/agent/proactive/ProactiveTriggerEngineTest.java)、[`AgentExtensionConditionTest`](../../apps/server/src/test/java/com/chtholly/agent/proactive/AgentExtensionConditionTest.java) |
| Trace 或质量回退 | [`TracePersistenceService`](../../apps/server/src/main/java/com/chtholly/agent/trace/TracePersistenceService.java)、[`LlmQualityEvaluationService`](../../apps/server/src/main/java/com/chtholly/agent/quality/LlmQualityEvaluationService.java) | [`TracePersistenceServiceTest`](../../apps/server/src/test/java/com/chtholly/agent/trace/TracePersistenceServiceTest.java)、[`HeuristicQualityEvaluationServiceTest`](../../apps/server/src/test/java/com/chtholly/agent/quality/HeuristicQualityEvaluationServiceTest.java) |

跨端事件格式还应同时核对[前端架构的 Agent 路径](frontend.md#agent-路径)与[核心请求链路](request-flows.md#8-agent-websocket上下文工具与记忆)。
