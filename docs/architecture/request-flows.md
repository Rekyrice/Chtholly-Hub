# 核心请求链路

## 阅读时机

排查跨控制器、服务、缓存、事件和外部依赖的问题，或修改会影响多个领域的行为时阅读本章。每条链路只描述当前源码已实现的路径。

## 读完能回答的问题

- 十条高价值链路从哪个入口触发，在哪里提交同步结果？
- 哪些工作异步执行或经过缓存，状态最终落在哪里？
- 依赖失败时返回错误、保留主事务，还是返回降级结果？
- 哪些测试最接近该链路？

## 1. 登录、刷新与鉴权

- **触发入口**：[AuthController](../../apps/server/src/main/java/com/chtholly/auth/api/AuthController.java) 的 `/api/v1/auth/login`、`/token/refresh`；受保护请求进入 [SecurityConfig](../../apps/server/src/main/java/com/chtholly/auth/config/SecurityConfig.java) 配置的 Resource Server。
- **同步主链**：`AuthService.login` 校验登录失败锁、账号与密码/验证码，检查封禁，调用 [JwtService](../../apps/server/src/main/java/com/chtholly/auth/token/JwtService.java) 签发 RS256 access/refresh token；刷新时验证 token 类型、Redis 白名单和用户状态，撤销旧 token ID 后轮换新 token 对。Bearer access token 经 Spring Security 解码，随后 [BannedUserFilter](../../apps/server/src/main/java/com/chtholly/admin/security/BannedUserFilter.java) 再检查用户封禁。
- **异步/缓存**：登录日志同步记录；验证码、失败窗口和 refresh token 白名单位于 Redis，无消息队列。
- **状态**：用户与登录日志在 MySQL；短期安全状态和 refresh token 有效性在 Redis；access token 本身无服务端 session。
- **失败/降级**：凭据、token、封禁或 Redis 安全状态失败时拒绝请求；没有把 refresh token 白名单降级为纯 JWT 校验。
- **代表性测试**：[AuthServiceHandleTest](../../apps/server/src/test/java/com/chtholly/auth/service/AuthServiceHandleTest.java)、[LoginFailureGuardTest](../../apps/server/src/test/java/com/chtholly/auth/security/LoginFailureGuardTest.java)、[JwtServiceTest](../../apps/server/src/test/java/com/chtholly/auth/token/JwtServiceTest.java)。

## 2. 文章发布与缓存失效

- **触发入口**：[PostController](../../apps/server/src/main/java/com/chtholly/post/api/PostController.java) 的 `/api/v1/posts/{id}/publish` → [PostServiceImpl](../../apps/server/src/main/java/com/chtholly/post/service/impl/PostServiceImpl.java)。
- **同步主链**：校验草稿所有者并发布，补齐唯一 slug，同步标签与作者文章数；写 `PostPublished` Outbox，尽力同步 ES、预索引 RAG，最后发布 `PostPublishedEvent`。
- **异步/缓存**：发布前的正文确认、元数据等变更通过 [PostCacheInvalidator](../../apps/server/src/main/java/com/chtholly/post/service/impl/PostCacheInvalidator.java) 失效详情/Feed缓存；`publish` 方法本身不再次调用该失效器。发布后的本地事件由 [FeedTimelineListener](../../apps/server/src/main/java/com/chtholly/post/listener/FeedTimelineListener.java) 异步维护关注 timeline；[FeedCacheInvalidationListener](../../apps/server/src/main/java/com/chtholly/post/listener/FeedCacheInvalidationListener.java) 则监听点赞/收藏事件更新已有 Feed 页计数。Kafka/Canal 启用时 Outbox 再驱动 ES。
- **状态**：文章、标签和 Outbox 在 MySQL；正文对象已在发布前确认；Feed/详情/RAG/ES 都是派生状态。
- **失败/降级**：草稿不存在或无权限时事务失败；作者计数、ES、RAG 或本地发布事件失败会记录日志但不撤销已发布文章，后续依赖回填或再次失效恢复。
- **代表性测试**：[PostServiceImplTest](../../apps/server/src/test/java/com/chtholly/post/service/impl/PostServiceImplTest.java)、[FeedTimelineListenerTest](../../apps/server/src/test/java/com/chtholly/post/listener/FeedTimelineListenerTest.java)、[PostPublishingGoldenPathIT](../../apps/server/src/test/java/com/chtholly/integration/PostPublishingGoldenPathIT.java)。

## 3. Feed 与个性化推荐

- **触发入口**：公开/关注 Feed 从 [PostController](../../apps/server/src/main/java/com/chtholly/post/api/PostController.java) 的 `/feed`、`/feed/following` 进入 [PostFeedServiceImpl](../../apps/server/src/main/java/com/chtholly/post/service/impl/PostFeedServiceImpl.java)；个性推荐从 [RecommendationController](../../apps/server/src/main/java/com/chtholly/recommendation/api/RecommendationController.java) 进入 [RecommendationService](../../apps/server/src/main/java/com/chtholly/recommendation/RecommendationService.java)。
- **同步主链**：公开 Feed 读取分页/游标列表并批量补齐作者、计数和当前用户点赞收藏；关注 Feed 从 [FeedTimelineService](../../apps/server/src/main/java/com/chtholly/post/feed/FeedTimelineService.java) 获取 timeline。推荐先构建 [UserInterestProfile](../../apps/server/src/main/java/com/chtholly/recommendation/UserInterestProfile.java)，融合兴趣标签、内容相似和可选协同过滤候选。
- **异步/缓存**：公开 Feed 使用 Caffeine L1、Redis L2、SingleFlight 与热 key 检测；发布/关注事件维护 Redis timeline 与画像。
- **状态**：文章事实在 MySQL；列表片段、用户交互、timeline 和画像在 Redis/进程缓存；推荐候选依赖 ES 与内容分析读模型。
- **失败/降级**：无用户或画像无信号时推荐热门；内容理解异常被跳过。ES 推荐查询失败会限制候选能力；公开 Feed 缓存未命中回源 MySQL。
- **代表性测试**：[PostFeedServiceImplEnrichTest](../../apps/server/src/test/java/com/chtholly/post/service/impl/PostFeedServiceImplEnrichTest.java)、[FeedTimelineServiceTest](../../apps/server/src/test/java/com/chtholly/post/feed/FeedTimelineServiceTest.java)、[RecommendationServiceTest](../../apps/server/src/test/java/com/chtholly/recommendation/RecommendationServiceTest.java)。

## 4. 点赞/收藏与异步聚合

- **触发入口**：[ActionController](../../apps/server/src/main/java/com/chtholly/counter/api/ActionController.java) 的 `/like`、`/unlike`、`/fav`、`/unfav` → [CounterServiceImpl](../../apps/server/src/main/java/com/chtholly/counter/service/impl/CounterServiceImpl.java)。
- **同步主链**：Redis Lua 原子切换按用户分片的位图；只有 bit 状态实际变化才构造带 event ID 的 `CounterEvent` 并发布。
- **异步/缓存**：`KAFKA_ENABLED=true` 时 [KafkaCounterPublisher](../../apps/server/src/main/java/com/chtholly/counter/event/KafkaCounterPublisher.java) 投递 Kafka，由 [CounterAggregationKafkaConsumer](../../apps/server/src/main/java/com/chtholly/counter/event/CounterAggregationKafkaConsumer.java) 处理；`false` 时由 [CounterAggregationSpringConsumer](../../apps/server/src/main/java/com/chtholly/counter/event/CounterAggregationSpringConsumer.java) 进程内处理。[CounterAggregationProcessor](../../apps/server/src/main/java/com/chtholly/counter/event/CounterAggregationProcessor.java) 去重、累加 Redis hash，并每秒刷入 SDS。两种模式都会发布本地事件供点赞通知。Spring 属性缺省值为 `false`，但推荐从 `.env.example` 复制的 `.env` 显式为 `true`。
- **状态**：用户点赞/收藏成员关系只存在 Redis bitmap，没有 MySQL 落点；聚合桶和 SDS 计数也在 Redis，SDS 缺失时可由 bitmap 分片计数重建。
- **失败/降级**：Redis bitmap 操作失败则请求失败，其持久化与备份决定成员关系可靠性。Kafka 关闭有进程内聚合通道，但 Spring 事件不可重放；可选 [CounterRebuildConsumer](../../apps/server/src/main/java/com/chtholly/counter/event/CounterRebuildConsumer.java) 只对浏览量等非成员计数提供受限的人工回放，并明确跳过点赞/收藏。点赞/收藏恢复必须使用完整 Redis RDB，SDS 可从 bitmap 重建；恢复 RDB 后不得再叠加 earliest Kafka 回放。Kafka 序列化失败只记录警告，本地通知事件仍发布；聚合消费者依赖 event ID 去重。
- **代表性测试**：[CounterServiceImplBatchTest](../../apps/server/src/test/java/com/chtholly/counter/service/impl/CounterServiceImplBatchTest.java)、[CounterAggregationProcessorTest](../../apps/server/src/test/java/com/chtholly/counter/event/CounterAggregationProcessorTest.java)、[SpringEventCounterPublisherConditionTest](../../apps/server/src/test/java/com/chtholly/counter/event/SpringEventCounterPublisherConditionTest.java)、[CounterGoldenPathIT](../../apps/server/src/test/java/com/chtholly/integration/CounterGoldenPathIT.java)。

## 5. 关注关系与 Outbox

- **触发入口**：[RelationController](../../apps/server/src/main/java/com/chtholly/relation/api/RelationController.java) 的 `/follow`、`/unfollow` → [RelationServiceImpl](../../apps/server/src/main/java/com/chtholly/relation/service/impl/RelationServiceImpl.java)。
- **同步主链**：关注先执行 Redis 令牌桶，再在同一 MySQL 事务写 `following` 与 Outbox；取关更新 `following` 并写 Outbox。同时发布本地关注/取关事件，用于通知和 Feed timeline。
- **异步/缓存**：Kafka/Canal 启用时 [CanalKafkaBridge](../../apps/server/src/main/java/com/chtholly/relation/outbox/CanalKafkaBridge.java) 转发 Outbox，[CanalOutboxConsumer](../../apps/server/src/main/java/com/chtholly/relation/outbox/CanalOutboxConsumer.java) 调用 [RelationEventProcessor](../../apps/server/src/main/java/com/chtholly/relation/processor/RelationEventProcessor.java) 幂等维护 `follower`、Redis ZSet 和用户计数。
- **状态**：`following` 是同步写模型，Outbox 与它同事务；`follower`、关系 ZSet 和关注/粉丝数是异步派生状态。
- **失败/降级**：限流返回未关注；重复写不产生新关系。Kafka/Canal 关闭时 Outbox 仍积存在 MySQL，本地事件只覆盖通知/timeline，不会执行 `RelationEventProcessor`，因此不能把它视为 fan 侧完整 fallback。
- **代表性测试**：[RelationServiceImplTest](../../apps/server/src/test/java/com/chtholly/relation/service/impl/RelationServiceImplTest.java)、[CanalOutboxRowMapperTest](../../apps/server/src/test/java/com/chtholly/relation/outbox/CanalOutboxRowMapperTest.java)、[RelationGoldenPathIT](../../apps/server/src/test/java/com/chtholly/integration/RelationGoldenPathIT.java)。

## 6. 搜索索引与查询

- **触发入口**：写侧由 [PostServiceImpl](../../apps/server/src/main/java/com/chtholly/post/service/impl/PostServiceImpl.java) 发布/修改/删除触发 [SearchIndexService](../../apps/server/src/main/java/com/chtholly/search/index/SearchIndexService.java)；读侧由 [SearchController](../../apps/server/src/main/java/com/chtholly/search/api/SearchController.java) 的 `/api/v1/search`、`/hub-feed`、`/suggest` 进入 [SearchServiceImpl](../../apps/server/src/main/java/com/chtholly/search/service/impl/SearchServiceImpl.java)。
- **同步主链**：索引服务从 MySQL读取文章元数据并安全抓取正文，写 ES 文档；查询使用全文相关性、互动权重、标签过滤、高亮和 `search_after` 游标，Hub 使用 msearch。
- **异步/缓存**：文章写入同时落 Outbox；Kafka/Canal 启用时 [CanalOutboxConsumerSearch](../../apps/server/src/main/java/com/chtholly/search/outbox/CanalOutboxConsumerSearch.java) 幂等补写索引，启动回填恢复缺失文档。
- **状态**：MySQL与正文对象是源；ES 是可重建索引。
- **失败/降级**：同步索引失败不回滚文章；全文搜索返回空页和 `degraded=true`，建议返回空列表，Hub 区域返回 `degraded`。当前实现不是 MySQL LIKE fallback。
- **代表性测试**：[SearchIndexServiceTest](../../apps/server/src/test/java/com/chtholly/search/index/SearchIndexServiceTest.java)、[SearchServiceImplTest](../../apps/server/src/test/java/com/chtholly/search/service/impl/SearchServiceImplTest.java)、[DegradationGoldenPathIT](../../apps/server/src/test/java/com/chtholly/integration/DegradationGoldenPathIT.java)。

## 7. 评论与通知

- **触发入口**：[CommentController](../../apps/server/src/main/java/com/chtholly/comment/api/CommentController.java) 的文章评论 POST → [CommentServiceImpl](../../apps/server/src/main/java/com/chtholly/comment/service/impl/CommentServiceImpl.java)。
- **同步主链**：校验文章可评论、正文清洗、父评论存在且是顶级评论，使用 Snowflake ID 写 MySQL，读取展示行后发布 `CommentCreatedEvent`。
- **异步/缓存**：[NotificationEventListener](../../apps/server/src/main/java/com/chtholly/notification/listener/NotificationEventListener.java) 在 `notificationExecutor` 异步识别回复接收者或文章作者，调用 [NotificationServiceImpl](../../apps/server/src/main/java/com/chtholly/notification/service/impl/NotificationServiceImpl.java) 写通知。
- **状态**：评论和通知均在 MySQL；异步 executor 只承载进程内事件，不是持久消息队列。
- **失败/降级**：非法层级、已删父评论、权限或限流失败时拒绝评论；通知监听异常记录错误但不回滚已提交评论，进程退出时未消费事件没有持久重放保证。
- **代表性测试**：[CommentServiceImplTest](../../apps/server/src/test/java/com/chtholly/comment/service/impl/CommentServiceImplTest.java)、[NotificationEventListenerTest](../../apps/server/src/test/java/com/chtholly/notification/listener/NotificationEventListenerTest.java)、[NotificationServiceImplTest](../../apps/server/src/test/java/com/chtholly/notification/service/impl/NotificationServiceImplTest.java)。

## 8. Agent WebSocket、上下文、工具与记忆

- **触发入口**：[AgentWsTicketController](../../apps/server/src/main/java/com/chtholly/agent/api/AgentWsTicketController.java) 签发票据，客户端连接 `/api/v1/agent/ws`，由 [AgentWebSocketHandler](../../apps/server/src/main/java/com/chtholly/agent/ws/AgentWebSocketHandler.java) 认证和处理 `chat`/`clear` 消息。
- **同步主链**：校验 ticket、session ID、消息与会话限流，加载 [AgentMemoryStore](../../apps/server/src/main/java/com/chtholly/agent/memory/AgentMemoryStore.java)；[ChthollyAgent](../../apps/server/src/main/java/com/chtholly/agent/ChthollyAgent.java) 让 [ContextEngine](../../apps/server/src/main/java/com/chtholly/agent/context/ContextEngine.java) 合成页面/身份/关系/历史/工具上下文，经有界 ReAct loop 执行 [AgentTool](../../apps/server/src/main/java/com/chtholly/agent/AgentTool.java)，最后流式发送 delta/final。
- **异步/缓存**：消息在 Agent executor 执行；会话 turn 先命中 Caffeine，再读写 Redis List 并按 TTL/最大条数裁剪；关闭连接后可触发反思与认知周期，Trace 持久化独立记录。
- **状态**：会话记忆在 Redis与进程缓存；工具读取各自领域状态；Trace/经验/图谱按已启用扩展写 MySQL；模型状态在外部 LLM。
- **失败/降级**：`llm.enabled=false` 时 WebSocket/Agent Bean 不装配，主站仍运行；未授权、限流、无效消息返回 WS error。工具失败作为 observation 受 loop 约束，LLM 超时/流错误发送错误事件；关闭某个扩展时由中立契约返回空能力，不应产生半装配链路。
- **代表性测试**：[AgentWebSocketHandlerTest](../../apps/server/src/test/java/com/chtholly/agent/ws/AgentWebSocketHandlerTest.java)、[ContextEngineTest](../../apps/server/src/test/java/com/chtholly/agent/context/ContextEngineTest.java)、[AgentToolExecutorLifecycleTest](../../apps/server/src/test/java/com/chtholly/agent/runtime/AgentToolExecutorLifecycleTest.java)、[AgentMemoryStoreTest](../../apps/server/src/test/java/com/chtholly/agent/memory/AgentMemoryStoreTest.java)。

## 9. Seed 生成与发布

- **触发入口**：启动参数或 [run-seed.ps1](../../scripts/dev/run-seed.ps1) 触发 [SeedRunner](../../apps/server/src/main/java/com/chtholly/seed/SeedRunner.java)，再调用 [SeedOrchestrator](../../apps/server/src/main/java/com/chtholly/seed/SeedOrchestrator.java)。
- **同步主链**：解析 mode/dry-run，检查 MySQL marker，构建 Bangumi 推荐、账号、文章、评论和关注计划；非 dry-run 在事务内持久化，正文发布器存在时上传 Markdown；搜索索引服务存在时逐篇 upsert ES，最后写 marker。
- **异步/缓存**：可选 [SeedInteractionService](../../apps/server/src/main/java/com/chtholly/seed/SeedInteractionService.java) 调度多轮互动并用 Redis 保存状态；dry-run 只生成摘要，不写状态。
- **状态**：marker 与业务 seed 行在 MySQL；正文位于当前 StorageService；互动运行态在 Redis；ES 是派生索引。
- **失败/降级**：已存在 marker 时幂等跳过；正文发布 IOException、序列化或 ES 索引失败会终止运行并回滚 MySQL 事务，但已经写出的外部对象不受数据库事务回滚。可选正文发布器、搜索索引服务或互动服务不存在时，对应步骤跳过。
- **代表性测试**：[SeedRunModeTest](../../apps/server/src/test/java/com/chtholly/seed/SeedRunModeTest.java)、[SeedOrchestratorTest](../../apps/server/src/test/java/com/chtholly/seed/SeedOrchestratorTest.java)、[SeedInteractionServiceTest](../../apps/server/src/test/java/com/chtholly/seed/SeedInteractionServiceTest.java)。

## 10. 管理员治理与审计

- **触发入口**：[AdminUserController](../../apps/server/src/main/java/com/chtholly/admin/api/AdminUserController.java) 与 [AdminPostController](../../apps/server/src/main/java/com/chtholly/admin/api/AdminPostController.java)，类级 [RequireRole](../../apps/server/src/main/java/com/chtholly/admin/role/RequireRole.java) 要求 ADMIN。
- **同步主链**：[RequireRoleAspect](../../apps/server/src/main/java/com/chtholly/admin/role/RequireRoleAspect.java) 校验角色；[AdminUserService](../../apps/server/src/main/java/com/chtholly/admin/service/AdminUserService.java) 修改角色/封禁，[AdminPostService](../../apps/server/src/main/java/com/chtholly/admin/service/AdminPostService.java) 修改可见性或软删文章/评论；随后调用 [AdminAuditService](../../apps/server/src/main/java/com/chtholly/admin/service/AdminAuditService.java) 尝试写审计。
- **异步/缓存**：封禁同步撤销目标用户全部 refresh token；内容治理复用文章缓存失效、Outbox 与 ES 尽力同步路径，没有单独审计队列。
- **状态**：角色、封禁时间、内容状态和审计日志在 MySQL；refresh token 白名单在 Redis。
- **失败/降级**：非管理员拒绝；不能封禁自己/站长或移除站长角色。当前 `AdminAuditService` 捕获序列化/插入异常并记录警告，因此审计是 best-effort，失败不会回滚已经完成的治理变更；内容治理的 ES/cache 派生状态仍遵循文章链路边界。
- **代表性测试**：[RequireRoleAspectTest](../../apps/server/src/test/java/com/chtholly/admin/role/RequireRoleAspectTest.java) 只覆盖角色切面；当前 `AdminUserController`、`AdminPostController`、`AdminUserService`、`AdminPostService` 与 `AdminAuditService` 没有直接测试覆盖，这是已知测试缺口。

## 修改联动

修改一条链路时，至少同时检查入口契约、同步事务、事件生产者/消费者、缓存失效、状态权威性、降级响应与上述代表性测试；不要只更新调用链的一端。

## 继续阅读

- 领域职责与所有顶级包：[后端领域地图](backend.md)
- 各状态系统的权威性：[数据与存储](data-and-storage.md)
- 后端本地命令：[后端运行入口](../../apps/server/README.md)
