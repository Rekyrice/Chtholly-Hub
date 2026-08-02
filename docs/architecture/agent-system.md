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
  → requestId 校验 → Redis 单飞/去重 → accepted(requestId, turnId)
  → 会话 ID、心跳、页面上下文与 AgentMemoryStore
  → ChthollyAgent
  → ContextEngine
  → AgentLoopExecutor
       ├─ AgentLlmInvoker：生成 Think / Act / Final 决策
       └─ AgentToolExecutor：校验并限时执行 AgentTool
  → ChthollyAgent 完整生成并校验最终答案
  → think / act / observe / delta / final / error(requestId, turnId)
  → Redis 会话记忆与异步 trace 持久化
```

- [`AgentWsTicketController`](../../apps/server/src/main/java/com/chtholly/agent/api/AgentWsTicketController.java) 为已认证用户签发短期、一次性 WebSocket ticket；[`AgentWebSocketConfig`](../../apps/server/src/main/java/com/chtholly/agent/config/AgentWebSocketConfig.java) 注册 `/api/v1/agent/ws`。
- [`AgentWebSocketHandler`](../../apps/server/src/main/java/com/chtholly/agent/ws/AgentWebSocketHandler.java) 消费 ticket、解析消息与页面上下文，管理心跳、会话限流和连接状态。`chat` 必须携带客户端 `requestId`；取得所有权后服务端生成 `turnId` 并先返回 `accepted`。[`AgentTurnCoordinator`](../../apps/server/src/main/java/com/chtholly/agent/ws/AgentTurnCoordinator.java) 用 Redis Lua 保证同一 `(userId, chatSessionId)` 跨实例单飞与 request 短期去重；Redis 不可用时失败关闭，不会退回无保护的并发执行。`final/error` 终态事件会等 Agent 返回、Memory 收尾且 active lease 释放后再发送，避免客户端收到终态后立即追问却被误判为上一轮仍在执行。[`AgentChatSessionSupport`](../../apps/server/src/main/java/com/chtholly/agent/ws/AgentChatSessionSupport.java) 只负责前端会话 ID 的格式校验，不是独立存储层。
- [`ChthollyAgent`](../../apps/server/src/main/java/com/chtholly/agent/ChthollyAgent.java) 是单轮编排边界：建立 trace、收紧工具集、构造上下文、调用有界循环、完整生成并校验最终答案，最后更新会话记忆。[`AgentTurnControl`](../../apps/server/src/main/java/com/chtholly/agent/runtime/AgentTurnControl.java) 携带轮次身份、取消信号和全轮 deadline；连接断开会取消本连接所有的 turn、中断任务并拒绝迟到事件。
- [`ContextEngine`](../../apps/server/src/main/java/com/chtholly/agent/context/ContextEngine.java) 按稳定顺序合成 system prompt，并拒绝重复名称或重复顺序的贡献者。
- [`AgentLoopExecutor`](../../apps/server/src/main/java/com/chtholly/agent/runtime/AgentLoopExecutor.java) 执行有最大步数的 Think-Act-Observe；[`AgentLlmInvoker`](../../apps/server/src/main/java/com/chtholly/agent/runtime/AgentLlmInvoker.java) 统一模型选项、单次上限与整轮剩余预算。[`AgentTurnBudget`](../../apps/server/src/main/java/com/chtholly/agent/runtime/AgentTurnBudget.java) 基于单调时钟，Skill 使用 `min(全局上限, timeoutBudgetMs)` 收紧轮次截止时间。
- [`AgentToolExecutor`](../../apps/server/src/main/java/com/chtholly/agent/runtime/AgentToolExecutor.java) 负责参数校验、用户上下文传播、工具超时、稳定错误码和结果归一化；实际超时取工具上限与整轮剩余预算的较小值。工具契约是 [`AgentTool`](../../apps/server/src/main/java/com/chtholly/agent/AgentTool.java)，当前实现覆盖站内检索、RAG、Bangumi 与受控公网研究，统一位于 [`agent/tools`](../../apps/server/src/main/java/com/chtholly/agent/tools)。旧工具只需返回文本；会发现新资料的工具通过 `executeDetailed` 同时返回 Observation 与 Evidence。

循环返回 `FINAL_READY` 后，最终答案仍由 `ChthollyAgent` 单独生成。它会先缓冲完整候选答案，再通过 Evidence 引用与 Skill 输出校验；只有安全答案才会发送一次完整 `delta` 和 `final`，服务端不再按字符人工延时。因此修改决策协议与修改最终表达风格是两个不同入口，不应把两者重新耦合回一个巨型循环。

## Skill 路由、输入规划与证据策略

WebSocket `chat` 消息可以在顶层携带 `taskType`。[`SkillSelector`](../../apps/server/src/main/java/com/chtholly/agent/skill/SkillSelector.java) 优先使用显式 `taskType`，未提供时才保留关键词规则作为兼容入口；显式任务不能扩大当前用户允许的工具集合。选中 Skill 后，[`SkillRequestPlanner`](../../apps/server/src/main/java/com/chtholly/agent/skill/SkillRequestPlanner.java) 在检索和 Agent loop 之前完成输入预检：

- `evidence-outline` 缺少明确主题、`page-explain` 同时缺少页面与解释目标、`draft-fact-check` 缺少草稿或主张时，直接进入澄清边界，不发起检索；
- 检索查询只使用提取后的主题、页面标题/slug、解释目标或可核查主张，不把“请根据站内资料生成……”一类任务包装文本原样送入混合检索；
- `EvidencePolicy.REQUIRED` 表示必须有可验证证据且事实引用必须通过校验；`OPTIONAL` 允许检索增强，也允许在证据为空时用通用知识完成任务，但不得伪装成站内结论；`NOT_NEEDED` 不为任务主动检索；
- 页面/站内范围的大纲与基于当前页面的解释会从模板默认 `OPTIONAL` 提升为 `REQUIRED`，草稿事实核查始终为 `REQUIRED`，草稿编辑为 `NOT_NEEDED`。

未提供 `taskType` 时，隐式路由按“事实核查 → 证据大纲 → 页面解释”的固定优先级选择，单独的“是什么”不会触发页面解释。Skill 及 Evidence 计划确定后，[`AgentToolPlanner`](../../apps/server/src/main/java/com/chtholly/agent/runtime/AgentToolPlanner.java) 再从已授权集合中做确定性收紧：Evidence Skill 移除重复的 `article_rag` / `fulltext_search`，评分等作品信息仅保留 `bangumi_search`，角色问题再加 `bangumi_characters`，人物作品问题使用 `bangumi_person_works`。规划器只能收紧、不能扩大权限，并按以下规则处理公网能力：

- 明确要求“只依据当前文章/站内资料”或“不要/别/不/无需联网”时排除 `web_search` 与 `web_fetch`，否定约束优先于问题中同时出现的“联网/网页”等字样；
- 问题含明确 HTTP(S) URL 时允许 `web_fetch`，明确要求先联网检索时才组合 `web_search → web_fetch`；
- 只有用户明确要求联网、外部资料或时效信息时，通用对话才启用公网工具；普通闲聊不会隐式访问网络；
- `web_search` 只负责发现候选页面，搜索摘要不是最终证据；模型必须再用 `web_fetch` 抓取少量目标页面，且抓取请求必须命中本轮任一次成功搜索已展示的累计候选 URL，事实才能获得 citation。无关网页或失败抓取不会解除“待核验”状态；候选集合的天然边界来自本轮最大步骤数、单次搜索结果上限和整轮 deadline，不会丢弃仍对模型可见的候选。

当前工具集合共七个：`article_rag`、`fulltext_search`、`bangumi_search`、`bangumi_characters`、`bangumi_person_works`、`web_search` 与 `web_fetch`。公网研究边界由 [`agent/web`](../../apps/server/src/main/java/com/chtholly/agent/web) 实现：只允许 HTTP(S) 默认端口、拒绝 userinfo 与非公网 DNS 结果，并把校验通过的 DNS 地址绑定到真实连接；每个 redirect hop 在出站前重新做 URL/DNS、目标 host 配额与 `robots.txt` 检查，同时禁止 HTTPS 降级。响应体、内容类型、重定向次数和超时均有硬上限；robots 按 origin 有界缓存并失败关闭。正文抽取尊重合法的响应 charset，HTML 在未声明时允许 BOM/meta 探测，Trace 记录实际采用的编码。搜索与抓取同时受用户级和 provider/host 级 Redis 限流，不新增密钥、端口或必需配置。

[`ParamDef`](../../apps/server/src/main/java/com/chtholly/agent/ParamDef.java) 的 schema 现在可声明字符串长度、数值范围和枚举约束；[`AgentToolParamValidator`](../../apps/server/src/main/java/com/chtholly/agent/AgentToolParamValidator.java) 与注入 prompt 的工具协议共用这一契约。运行时校验仍是权威边界，不依赖模型自觉遵守提示。

证据状态由 [`EvidenceSet`](../../apps/server/src/main/java/com/chtholly/agent/evidence/EvidenceSet.java) 和校验状态表达，不再依赖固定中文答案字符串作为机器哨兵。`web_fetch` 成功后会生成绑定真实 final URL 与正文 SHA-256 的 PUBLIC Evidence；HTML 声明的 canonical 只作为诊断元数据，不改变证据权威地址。工具 Observation 不预先声称临时 `E1`，[`AgentLoopExecutor`](../../apps/server/src/main/java/com/chtholly/agent/runtime/AgentLoopExecutor.java) 保持既有 `[E#]` 不变、为新资料续号，并把唯一权威的真实 citation 随 Observation 注入下一步上下文。同一 URL 若正文哈希变化，会在原 citation 位置替换版本并把更新后的证据重新注入；哈希相同的重复抓取才会忽略。最终生成、缺失引用修复、Skill 校验和答案校验统一使用同一份最终 Evidence 快照；进入最终生成前，loop transcript 会保留工具元数据，但把历史动态 Evidence 部分替换为未附加证据的 canonical Observation，避免同一 `[E#]` 的旧版本与最终快照同时对答案模型可见。失败、超时或无变化的重复抓取不会污染证据集。

`ChthollyAgent` 对澄清、无证据和无效引用统一使用角色魂生成自然语言边界回复，并在 trace 顶层记录稳定的 `outcomeReason`：`NEEDS_CLARIFICATION`、`NO_EVIDENCE`、`INVALID_CITATION` 或 `MODEL_FAILURE`。结构化 Skill 的状态值、字段与 citation 格式仍由模板和 [`SkillOutputValidator`](../../apps/server/src/main/java/com/chtholly/agent/skill/SkillOutputValidator.java) 约束，角色表达只作用于自然语言部分。

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

- [`AgentMemoryStore`](../../apps/server/src/main/java/com/chtholly/agent/memory/AgentMemoryStore.java) 以 `userId + chatSessionId` 为键，Redis List 是跨进程会话数据，Caffeine 只加速热会话。WebSocket 单轮写入由一个 Redis Lua 同时校验 active lease、使用 Redis `TIME` 校验绝对 deadline，并完成 user/assistant 成对追加、`LTRIM` 与 `PEXPIRE`；只有返回 `COMMITTED` 后答案才允许发往客户端。lease 已换轮或 deadline 已过会返回 `REJECTED`，网络异常等无法确认是否提交的情况记为 `UNKNOWN` 并失效本地缓存。Caffeine 热读只刷新本地 `expireAfterAccess`，不访问 Redis，因此持续命中本地缓存时 Redis key 仍可能过期。它仅在 `llm.enabled=true` 时注册，并直接依赖 Redis，没有另一套内存持久化降级实现。
- [`AgentConversationMemory`](../../apps/server/src/main/java/com/chtholly/agent/memory/AgentConversationMemory.java) 是单轮使用的会话视图；长期程序性知识由 [`ProceduralMemoryService`](../../apps/server/src/main/java/com/chtholly/agent/memory/ProceduralMemoryService.java) 承担并受 Learning 扩展控制。
- Experience 是可选的长期经历域，入口包括 [`ExperienceGenerator`](../../apps/server/src/main/java/com/chtholly/agent/experience/ExperienceGenerator.java)、[`ExperienceService`](../../apps/server/src/main/java/com/chtholly/agent/cognitive/ExperienceService.java) 与 [`AgentExperienceController`](../../apps/server/src/main/java/com/chtholly/agent/api/AgentExperienceController.java)。它与聊天历史不是同一存储概念。

### Knowledge Graph、Mood 与 Proactive

- Knowledge Graph 由 [`KnowledgeGraphService`](../../apps/server/src/main/java/com/chtholly/agent/graph/KnowledgeGraphService.java) 与 [`KnowledgeGraphRepository`](../../apps/server/src/main/java/com/chtholly/agent/graph/KnowledgeGraphRepository.java) 管理；`GraphContextContributor` 只把相关邻域投影进 prompt。
- Mood 由 [`MoodEngine`](../../apps/server/src/main/java/com/chtholly/agent/mood/MoodEngine.java)、[`SeasonService`](../../apps/server/src/main/java/com/chtholly/agent/mood/SeasonService.java) 和季节上下文协作，不属于 Core 必需链路。[`CharacterStateService`](../../apps/server/src/main/java/com/chtholly/agent/state/CharacterStateService.java) 使用 Redis Lua 原子递增互动次数与亲密度，并用交互发生时间阻止多实例中的迟到情绪更新覆盖较新状态。
- Proactive 的调度门面是 [`ProactiveTriggerEngine`](../../apps/server/src/main/java/com/chtholly/agent/proactive/ProactiveTriggerEngine.java)，情绪、内容、社交决策拆分到同包服务；消息通过 [`NotificationService`](../../apps/server/src/main/java/com/chtholly/agent/notification/NotificationService.java) 与 WebSocket 待发通知协作。

### Trace 与 Quality

- `ChthollyAgent` 为一次运行建立 [`AgentExecutionTrace`](../../apps/server/src/main/java/com/chtholly/agent/observability/AgentExecutionTrace.java)，[`AgentObservationService`](../../apps/server/src/main/java/com/chtholly/agent/observability/AgentObservationService.java) 建立 Agent 父 Span，以及 LLM、Tool、Skill 选择、三路检索和草稿预览/应用子 Span；[`TracePersistenceService`](../../apps/server/src/main/java/com/chtholly/agent/trace/TracePersistenceService.java) 异步落库并定时挖掘失败模式。
- `trace_payload` 使用 `agent-trace-v4`。`turn` 组记录 `requestId` / `turnId` / 逻辑会话 / 连接 ID、有效预算、超时阶段、取消状态，以及客户端终态交付状态、事件类型和固定错误码；`memory` 组记录写入状态与低基数失败码。Trace 不在 Agent worker 内等待 Handler，而是在客户端终态决议回调中统一完成 Span、日志、指标和异步持久化，因此 final 写入失败或 lease 释放失败不会留下伪成功 Trace，也不会因线程池回退到调用线程而自等待。`toolPlan` 记录收紧原因和有效工具；`answerTiming` 区分模型首内容、安全答案就绪和客户端首次可见时延。
- Trace 详情接口由 [`TraceController`](../../apps/server/src/main/java/com/chtholly/agent/trace/TraceController.java) 的 ADMIN 角色边界保护，前端管理页使用类型化投影展示真实时间线与循环 Step 摘要。管理员归档级捕获会记录原始问题、页面上下文、每次 LLM 的 system/user prompt 与原始输出、实际工具输入、最终 Observe、工具外部条件、最终交付答案和完整失败因果链；网页工具还记录搜索 provider 的真实 HTTP 元数据，以及抓取各 hop 的 URL、robots 决策、缓存状态、响应、编码与抽取信息。每段内容携带字符数、SHA-256 与截断状态；独立完整度字段显示事件上限、丢弃数和工具预览截断数，管理员不会把不完整 Trace 误判为完整链路。单字段和整轮有固定容量上限，只遮蔽基础设施凭证类键值，不因 URL、普通上下文或 token budget 降低诊断信息。旧 `agent-trace-v3` 仍按兼容投影可读，接口不会把无法识别的整段数据库 JSON 直接透传给浏览器。
- [`trace-replay.ps1`](../../scripts/benchmark/trace-replay.ps1) 从固定历史提交创建归档，只注入同一测试观察层，并实际执行历史 `HybridSearchService`、`ChthollyAgent`、MySQL Trace 持久化与查询回读。检索上游、LLM 和 Observation 使用确定性替身且外部模型调用为 0；manifest 绑定 subject tree、生产源码摘要、harness/dataset blob、回归测试日志和输入指纹。四次观测全部满足约束时证据等级为 `REAL_TRACE`，但样本仍保持 `CANDIDATE_REQUIRES_OWNER_REVIEW / COLLECTED_UNREVIEWED`。具体边界和命令见[最小基准与评测入口](../../benchmarks/README.md)。
- Quality 不是聊天循环的一步。[`LlmQualityEvaluationService`](../../apps/server/src/main/java/com/chtholly/agent/quality/LlmQualityEvaluationService.java) 优先使用可用 `ChatClient`，不可用或失败时退回 [`HeuristicQualityEvaluationService`](../../apps/server/src/main/java/com/chtholly/agent/quality/HeuristicQualityEvaluationService.java)，所以调用者不应假设一定发生 LLM 请求。

## 配置来源与启用边界

| 来源 | 负责内容 |
|------|----------|
| [`application.yml`](../../apps/server/src/main/resources/application.yml) | `LLM_ENABLED` 同时绑定 `llm.enabled` 与 `rag.enabled`；`agent.model`、整轮/LLM/工具超时、最大步数、响应长度、memory 上限/TTL |
| [`agent-domain.yml`](../../apps/server/src/main/resources/agent-domain.yml) | `agent.domain.*` 的系统提示词、错误消息、Bangumi 文案与上下文标签 |
| [`AgentDomainConfig`](../../apps/server/src/main/java/com/chtholly/agent/config/AgentDomainConfig.java) | 对 `agent.domain.*` 的类型化绑定和占位符渲染 |
| [`AgentExtensionProperties`](../../apps/server/src/main/java/com/chtholly/agent/config/AgentExtensionProperties.java) | `agent.extensions.<group>.enabled`，七组缺省均为 `true` |

`LLM_ENABLED=false` 时，`ChthollyAgent`、运行时、WebSocket handler/config/ticket store、会话 memory 和 Agent 工具等交互主链 bean 不注册，博客与社区主链仍可运行。扩展开关与 `LLM_ENABLED` 是两个维度：多个扩展组件只看 `agent.extensions.*`，有些通过可选 `ChatClient` 或确定性回退工作；不要把“关闭聊天入口”误写成“所有扩展 bean 都关闭”。若需要最小 Agent Core Spring 上下文，应显式关闭七个扩展开关。

## 修改路由

| 修改场景 | 先看实现 | 代表性测试 |
|----------|----------|------------|
| WebSocket 鉴权、轮次协议或单飞 | [`AgentWebSocketHandler`](../../apps/server/src/main/java/com/chtholly/agent/ws/AgentWebSocketHandler.java)、[`AgentWsTicketStore`](../../apps/server/src/main/java/com/chtholly/agent/ws/AgentWsTicketStore.java)、[`AgentTurnCoordinator`](../../apps/server/src/main/java/com/chtholly/agent/ws/AgentTurnCoordinator.java) | [`AgentWebSocketHandlerTest`](../../apps/server/src/test/java/com/chtholly/agent/ws/AgentWebSocketHandlerTest.java)、[`AgentChatSessionSupportTest`](../../apps/server/src/test/java/com/chtholly/agent/ws/AgentChatSessionSupportTest.java)、[`AgentTurnCoordinatorTest`](../../apps/server/src/test/java/com/chtholly/agent/ws/AgentTurnCoordinatorTest.java) |
| 单轮编排与最终流式回答 | [`ChthollyAgent`](../../apps/server/src/main/java/com/chtholly/agent/ChthollyAgent.java) | [`ChthollyAgentTest`](../../apps/server/src/test/java/com/chtholly/agent/ChthollyAgentTest.java) |
| Think-Act-Observe 协议 | [`AgentLoopExecutor`](../../apps/server/src/main/java/com/chtholly/agent/runtime/AgentLoopExecutor.java) | [`AgentLoopExecutorTest`](../../apps/server/src/test/java/com/chtholly/agent/runtime/AgentLoopExecutorTest.java) |
| 整轮、LLM 或工具超时/参数 | [`AgentTurnBudget`](../../apps/server/src/main/java/com/chtholly/agent/runtime/AgentTurnBudget.java)、[`AgentLlmInvoker`](../../apps/server/src/main/java/com/chtholly/agent/runtime/AgentLlmInvoker.java)、[`AgentToolExecutor`](../../apps/server/src/main/java/com/chtholly/agent/runtime/AgentToolExecutor.java) | [`AgentTurnBudgetTest`](../../apps/server/src/test/java/com/chtholly/agent/runtime/AgentTurnBudgetTest.java)、[`AgentLlmInvokerTest`](../../apps/server/src/test/java/com/chtholly/agent/runtime/AgentLlmInvokerTest.java)、[`AgentToolExecutorTest`](../../apps/server/src/test/java/com/chtholly/agent/runtime/AgentToolExecutorTest.java) |
| Prompt 顺序或贡献者 | [`ContextEngine`](../../apps/server/src/main/java/com/chtholly/agent/context/ContextEngine.java)、[`ContextOrder`](../../apps/server/src/main/java/com/chtholly/agent/context/ContextOrder.java) | [`ContextEngineTest`](../../apps/server/src/test/java/com/chtholly/agent/context/ContextEngineTest.java)、[`ContextContributorContractTest`](../../apps/server/src/test/java/com/chtholly/agent/context/ContextContributorContractTest.java) |
| Core/扩展开关边界 | [`AgentExtensionProperties`](../../apps/server/src/main/java/com/chtholly/agent/config/AgentExtensionProperties.java)、[`ConditionalOnAgentExtensions`](../../apps/server/src/main/java/com/chtholly/agent/config/ConditionalOnAgentExtensions.java) | [`AgentExtensionPropertiesTest`](../../apps/server/src/test/java/com/chtholly/agent/config/AgentExtensionPropertiesTest.java)、[`AgentExtensionBoundaryArchitectureTest`](../../apps/server/src/test/java/com/chtholly/agent/config/AgentExtensionBoundaryArchitectureTest.java) |
| Redis 会话记忆 | [`AgentMemoryStore`](../../apps/server/src/main/java/com/chtholly/agent/memory/AgentMemoryStore.java) | [`AgentMemoryStoreTest`](../../apps/server/src/test/java/com/chtholly/agent/memory/AgentMemoryStoreTest.java) |
| 主动行为 | [`ProactiveTriggerEngine`](../../apps/server/src/main/java/com/chtholly/agent/proactive/ProactiveTriggerEngine.java) | [`ProactiveTriggerEngineTest`](../../apps/server/src/test/java/com/chtholly/agent/proactive/ProactiveTriggerEngineTest.java)、[`AgentExtensionConditionTest`](../../apps/server/src/test/java/com/chtholly/agent/proactive/AgentExtensionConditionTest.java) |
| Skill 路由、输入、证据与工具计划 | [`SkillSelector`](../../apps/server/src/main/java/com/chtholly/agent/skill/SkillSelector.java)、[`SkillRequestPlanner`](../../apps/server/src/main/java/com/chtholly/agent/skill/SkillRequestPlanner.java)、[`AgentToolPlanner`](../../apps/server/src/main/java/com/chtholly/agent/runtime/AgentToolPlanner.java)、[`SkillOutputValidator`](../../apps/server/src/main/java/com/chtholly/agent/skill/SkillOutputValidator.java) | [`SkillSelectorTest`](../../apps/server/src/test/java/com/chtholly/agent/skill/SkillSelectorTest.java)、[`SkillRequestPlannerTest`](../../apps/server/src/test/java/com/chtholly/agent/skill/SkillRequestPlannerTest.java)、[`AgentToolPlannerTest`](../../apps/server/src/test/java/com/chtholly/agent/runtime/AgentToolPlannerTest.java)、[`SkillOutputValidatorTest`](../../apps/server/src/test/java/com/chtholly/agent/skill/SkillOutputValidatorTest.java) |
| 公网搜索、抓取与 SSRF/robots 边界 | [`WebSearchTool`](../../apps/server/src/main/java/com/chtholly/agent/tools/WebSearchTool.java)、[`WebFetchTool`](../../apps/server/src/main/java/com/chtholly/agent/tools/WebFetchTool.java)、[`SafeWebHttpClient`](../../apps/server/src/main/java/com/chtholly/agent/web/SafeWebHttpClient.java) | [`WebSearchToolTest`](../../apps/server/src/test/java/com/chtholly/agent/tools/WebSearchToolTest.java)、[`WebFetchToolTest`](../../apps/server/src/test/java/com/chtholly/agent/tools/WebFetchToolTest.java)、[`SafeWebHttpClientTest`](../../apps/server/src/test/java/com/chtholly/agent/web/SafeWebHttpClientTest.java) |
| Trace 或质量回退 | [`TracePersistenceService`](../../apps/server/src/main/java/com/chtholly/agent/trace/TracePersistenceService.java)、[`TraceDetailProjector`](../../apps/server/src/main/java/com/chtholly/agent/trace/dto/TraceDetailProjector.java)、[`LlmQualityEvaluationService`](../../apps/server/src/main/java/com/chtholly/agent/quality/LlmQualityEvaluationService.java) | [`TracePersistenceServiceTest`](../../apps/server/src/test/java/com/chtholly/agent/trace/TracePersistenceServiceTest.java)、[`TraceQueryServiceTest`](../../apps/server/src/test/java/com/chtholly/agent/trace/TraceQueryServiceTest.java)、[`HeuristicQualityEvaluationServiceTest`](../../apps/server/src/test/java/com/chtholly/agent/quality/HeuristicQualityEvaluationServiceTest.java) |

跨端事件格式还应同时核对[前端架构的 Agent 路径](frontend.md#agent-路径)与[核心请求链路](request-flows.md#8-agent-websocket上下文工具与记忆)。
