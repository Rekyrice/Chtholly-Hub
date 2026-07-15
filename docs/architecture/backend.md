# 后端领域地图

## 阅读时机

修改 Spring Boot API、领域服务、缓存、事件、后台任务或可选 AI 能力前阅读本章。进入代码后继续遵守[后端局部规则](../../apps/server/AGENTS.md)。

## 读完能回答的问题

- 当前改动属于哪个领域，入口类、权威状态和下游依赖在哪里？
- 22 个顶级 Java 包如何归入稳定领域边界？
- 哪些能力依赖 Redis、Kafka、Elasticsearch、外部 API 或 LLM，关闭后如何收缩？
- 应先运行哪些代表性测试，改动还要联动哪些章节？

## 领域边界

源码根目录为 `apps/server/src/main/java/com/chtholly`。当前 22 个顶级包全部归入以下七个领域；领域是维护视角，不要求目录物理合并。

| 领域 | 顶级包 | 职责边界 |
|------|--------|----------|
| 账号治理 | `auth`、`user`、`profile`、`admin` | 登录注册、JWT 与刷新令牌、公开资料、本人资料、角色、封禁和管理员审计 |
| 内容生产 | `post`、`tag`、`comment`、`storage`、`content` | 草稿到发布、正文对象、标签、评论，以及供推荐/Agent 共享的内容智能契约 |
| 社区互动 | `counter`、`relation`、`notification`、`recommendation` | 点赞收藏、关注关系、站内通知和个性化推荐 |
| 内容发现 | `search`、`bangumi`、`cache` | 全文索引与 Hub 查询、Bangumi 外部数据、多级缓存基础件 |
| Agent 平台 | `agent`、`llm` | WebSocket Agent、上下文/工具/记忆/观测，以及模型、Embedding 和 RAG 适配 |
| 内容初始化 | `seed` | 可重复执行的账号、文章、互动和 Bangumi 初始化数据生成 |
| 平台基础 | `common`、`config`、`health` | 通用异常/分页/限流/Kafka 基础、全局 Bean 配置和健康检查 |

### 账号治理

- **关键入口**：[AuthController](../../apps/server/src/main/java/com/chtholly/auth/api/AuthController.java) → [AuthService](../../apps/server/src/main/java/com/chtholly/auth/service/AuthService.java)；公开用户与本人资料分别从 [UserController](../../apps/server/src/main/java/com/chtholly/user/api/UserController.java) 和 [ProfileController](../../apps/server/src/main/java/com/chtholly/profile/api/ProfileController.java) 进入；后台治理从 [AdminUserController](../../apps/server/src/main/java/com/chtholly/admin/api/AdminUserController.java) 与 [AdminPostController](../../apps/server/src/main/java/com/chtholly/admin/api/AdminPostController.java) 进入。
- **依赖与状态**：用户、登录日志和审计记录以 MySQL 为准；验证码、登录失败防护和刷新令牌白名单使用 Redis；[SecurityConfig](../../apps/server/src/main/java/com/chtholly/auth/config/SecurityConfig.java) 配置无状态 OAuth2 Resource Server，[BannedUserFilter](../../apps/server/src/main/java/com/chtholly/admin/security/BannedUserFilter.java) 在 JWT 后检查封禁。
- **代表性测试**：[AuthServiceHandleTest](../../apps/server/src/test/java/com/chtholly/auth/service/AuthServiceHandleTest.java)、[JwtServiceTest](../../apps/server/src/test/java/com/chtholly/auth/token/JwtServiceTest.java)、[ProfileControllerTest](../../apps/server/src/test/java/com/chtholly/profile/api/ProfileControllerTest.java)、[RequireRoleAspectTest](../../apps/server/src/test/java/com/chtholly/admin/role/RequireRoleAspectTest.java)。
- **修改联动**：鉴权公开路径要同步检查 `SecurityConfig` 与控制器测试；封禁/角色变更要检查刷新令牌撤销、过滤器和管理员审计。

### 内容生产

- **关键入口**：[PostController](../../apps/server/src/main/java/com/chtholly/post/api/PostController.java) 调用 [PostServiceImpl](../../apps/server/src/main/java/com/chtholly/post/service/impl/PostServiceImpl.java) 完成草稿、正文确认、元数据和发布；[TagServiceImpl](../../apps/server/src/main/java/com/chtholly/tag/service/impl/TagServiceImpl.java) 同步标签；[CommentController](../../apps/server/src/main/java/com/chtholly/comment/api/CommentController.java) 与 [CommentServiceImpl](../../apps/server/src/main/java/com/chtholly/comment/service/impl/CommentServiceImpl.java) 管理二级评论；[StorageController](../../apps/server/src/main/java/com/chtholly/storage/api/StorageController.java) 面向统一 [StorageService](../../apps/server/src/main/java/com/chtholly/storage/StorageService.java)。`content` 包的 [ContentIntelligenceReader](../../apps/server/src/main/java/com/chtholly/content/ContentIntelligenceReader.java) 是推荐与 Agent 读取内容分析的中立契约。
- **依赖与状态**：文章元数据、标签和评论在 MySQL；Markdown 与媒体写本地文件系统或 OSS；文章读模型使用 Caffeine/Redis，正文确认、元数据、可见性等变更会失效缓存；发布写 Outbox、尝试同步 ES 与 RAG，并发布本地 `PostPublishedEvent` 维护 timeline。
- **代表性测试**：[PostServiceImplTest](../../apps/server/src/test/java/com/chtholly/post/service/impl/PostServiceImplTest.java)、[PostPublishingGoldenPathIT](../../apps/server/src/test/java/com/chtholly/integration/PostPublishingGoldenPathIT.java)、[CommentServiceImplTest](../../apps/server/src/test/java/com/chtholly/comment/service/impl/CommentServiceImplTest.java)、[LocalFileStorageServiceTest](../../apps/server/src/test/java/com/chtholly/storage/LocalFileStorageServiceTest.java)。
- **修改联动**：发布状态、可见性或标签变化要检查 Feed/详情缓存、搜索文档、RAG 与事件监听器；对象键规则要同时检查本地和 OSS 实现。

### 社区互动

- **关键入口**：[ActionController](../../apps/server/src/main/java/com/chtholly/counter/api/ActionController.java) → [CounterServiceImpl](../../apps/server/src/main/java/com/chtholly/counter/service/impl/CounterServiceImpl.java)；[RelationController](../../apps/server/src/main/java/com/chtholly/relation/api/RelationController.java) → [RelationServiceImpl](../../apps/server/src/main/java/com/chtholly/relation/service/impl/RelationServiceImpl.java)；[NotificationController](../../apps/server/src/main/java/com/chtholly/notification/api/NotificationController.java) 查询通知；[RecommendationController](../../apps/server/src/main/java/com/chtholly/recommendation/api/RecommendationController.java) → [RecommendationService](../../apps/server/src/main/java/com/chtholly/recommendation/RecommendationService.java)。
- **依赖与状态**：点赞/收藏幂等位图、SDS 计数、关系读缓存与兴趣画像主要在 Redis；关注写模型、通知和 Outbox 在 MySQL。计数通过 Kafka 或本地 Spring 事件聚合；关注 fan 侧在 Kafka/Canal 启用时最终一致更新；通知监听器使用异步 Spring 事件。
- **代表性测试**：[CounterAggregationProcessorTest](../../apps/server/src/test/java/com/chtholly/counter/event/CounterAggregationProcessorTest.java)、[RelationServiceImplTest](../../apps/server/src/test/java/com/chtholly/relation/service/impl/RelationServiceImplTest.java)、[NotificationEventListenerTest](../../apps/server/src/test/java/com/chtholly/notification/listener/NotificationEventListenerTest.java)、[RecommendationServiceTest](../../apps/server/src/test/java/com/chtholly/recommendation/RecommendationServiceTest.java)。
- **修改联动**：计数事件契约要同步两种消费者；关系写路径要检查 Outbox、Redis ZSet、粉丝表、Feed timeline 与通知；推荐策略要保留无画像和依赖失败时的热门降级。

### 内容发现

- **关键入口**：[SearchController](../../apps/server/src/main/java/com/chtholly/search/api/SearchController.java) → [SearchServiceImpl](../../apps/server/src/main/java/com/chtholly/search/service/impl/SearchServiceImpl.java)，索引写入由 [SearchIndexService](../../apps/server/src/main/java/com/chtholly/search/index/SearchIndexService.java) 承担；Bangumi 访问从 [BangumiClient](../../apps/server/src/main/java/com/chtholly/bangumi/client/BangumiClient.java) 与 [BangumiServiceImpl](../../apps/server/src/main/java/com/chtholly/bangumi/service/impl/BangumiServiceImpl.java) 进入；`cache` 包提供 [HotKeyDetector](../../apps/server/src/main/java/com/chtholly/cache/hotkey/HotKeyDetector.java) 和 [SingleFlightLockRegistry](../../apps/server/src/main/java/com/chtholly/cache/singleflight/SingleFlightLockRegistry.java)。
- **依赖与状态**：ES 文档是可重建索引，不是业务权威；搜索、建议和 Hub 区域失败时返回空结果/`degraded` 状态。Bangumi 依赖外部 API 并缓存本地结果；缓存组件本身不拥有业务事实。
- **代表性测试**：[SearchServiceImplTest](../../apps/server/src/test/java/com/chtholly/search/service/impl/SearchServiceImplTest.java)、[SearchIndexServiceTest](../../apps/server/src/test/java/com/chtholly/search/index/SearchIndexServiceTest.java)、[BangumiClientTest](../../apps/server/src/test/java/com/chtholly/bangumi/client/BangumiClientTest.java)、[HotKeyDetectorTest](../../apps/server/src/test/java/com/chtholly/cache/hotkey/HotKeyDetectorTest.java)。
- **修改联动**：索引 mapping、写入文档和查询字段必须同步；搜索降级协议要与前端消费者一致；缓存键、TTL 或回填策略改变时检查失效与击穿保护。

### Agent 平台

- **关键入口**：[AgentWsTicketController](../../apps/server/src/main/java/com/chtholly/agent/api/AgentWsTicketController.java) 签发短期票据，[AgentWebSocketHandler](../../apps/server/src/main/java/com/chtholly/agent/ws/AgentWebSocketHandler.java) 建立会话并调用 [ChthollyAgent](../../apps/server/src/main/java/com/chtholly/agent/ChthollyAgent.java)；[ContextEngine](../../apps/server/src/main/java/com/chtholly/agent/context/ContextEngine.java) 组装上下文，[AgentTool](../../apps/server/src/main/java/com/chtholly/agent/AgentTool.java) 定义工具契约，[AgentMemoryStore](../../apps/server/src/main/java/com/chtholly/agent/memory/AgentMemoryStore.java) 保存会话记忆。`llm` 包以 [LlmConfig](../../apps/server/src/main/java/com/chtholly/llm/LlmConfig.java)、[RagIndexService](../../apps/server/src/main/java/com/chtholly/llm/rag/RagIndexService.java) 和 [RagQueryService](../../apps/server/src/main/java/com/chtholly/llm/rag/RagQueryService.java) 适配模型与 RAG。
- **依赖与状态**：核心 Agent、WebSocket、工具执行和 Redis/Caffeine 会话记忆受 `llm.enabled` 控制；内容、图谱、学习、经验、情绪、社区动作和主动行为再由 `agent.extensions.*.enabled` 分组控制。`LLM_ENABLED=false` 时 `NoOpPostRagIndexer` 保留文章发布主链，博客与社区不依赖 Agent 启动。
- **代表性测试**：[AgentWebSocketHandlerTest](../../apps/server/src/test/java/com/chtholly/agent/ws/AgentWebSocketHandlerTest.java)、[ChthollyAgentTest](../../apps/server/src/test/java/com/chtholly/agent/ChthollyAgentTest.java)、[ContextEngineTest](../../apps/server/src/test/java/com/chtholly/agent/context/ContextEngineTest.java)、[AgentMemoryStoreTest](../../apps/server/src/test/java/com/chtholly/agent/memory/AgentMemoryStoreTest.java)。
- **修改联动**：消息协议要同步 WebSocket 前端；上下文贡献者需保持顺序和 Core-only 契约；工具参数、超时、Trace 与记忆键/TTL 改动要成组验证。

### 内容初始化

- **关键入口**：命令行 [SeedRunner](../../apps/server/src/main/java/com/chtholly/seed/SeedRunner.java) 解析 mode/dry-run，[SeedOrchestrator](../../apps/server/src/main/java/com/chtholly/seed/SeedOrchestrator.java) 构建并持久化计划；[AdminSeedAuditController](../../apps/server/src/main/java/com/chtholly/admin/api/AdminSeedAuditController.java) 只暴露审计结果。
- **依赖与状态**：幂等 marker、账号、文章、评论、关注和推荐数据写入 MySQL；正文可经 `SeedContentPublisher` 写存储；可选互动使用 Redis 状态与异步调度；已发布种子文章在 ES 可用时立即索引。
- **代表性测试**：[SeedRunModeTest](../../apps/server/src/test/java/com/chtholly/seed/SeedRunModeTest.java)、[SeedOrchestratorTest](../../apps/server/src/test/java/com/chtholly/seed/SeedOrchestratorTest.java)、[SeedContentAuditorTest](../../apps/server/src/test/java/com/chtholly/seed/SeedContentAuditorTest.java)。
- **修改联动**：新增模式或字段要同步 `SeedMapper` SQL、幂等 marker、dry-run 摘要和审计；生成内容不得绕过正式发布所依赖的数据约束。

### 平台基础

- **关键入口**：启动类 [ChthollyApplication](../../apps/server/src/main/java/com/chtholly/ChthollyApplication.java)；`common` 包的 [GlobalExceptionHandler](../../apps/server/src/main/java/com/chtholly/common/web/GlobalExceptionHandler.java)、[AbstractKafkaConsumer](../../apps/server/src/main/java/com/chtholly/common/kafka/AbstractKafkaConsumer.java) 和限流/调度基础件；`config` 包的 [ElasticsearchConfig](../../apps/server/src/main/java/com/chtholly/config/ElasticsearchConfig.java)、[RedissonConfig](../../apps/server/src/main/java/com/chtholly/config/RedissonConfig.java) 与 [ThreadPoolConfig](../../apps/server/src/main/java/com/chtholly/config/ThreadPoolConfig.java)；`health` 包的 [ElasticsearchHealthIndicator](../../apps/server/src/main/java/com/chtholly/health/ElasticsearchHealthIndicator.java)、[BangumiHealthIndicator](../../apps/server/src/main/java/com/chtholly/health/BangumiHealthIndicator.java) 和 [OssHealthIndicator](../../apps/server/src/main/java/com/chtholly/health/OssHealthIndicator.java)。
- **依赖与状态**：本领域不拥有业务数据；它定义跨领域的错误格式、配置 Bean、线程池、重试/死信、可观测性与健康探针。健康指标反映依赖状态，不替代业务降级策略。
- **代表性测试**：[GlobalExceptionHandlerTest](../../apps/server/src/test/java/com/chtholly/common/web/GlobalExceptionHandlerTest.java)、[AbstractKafkaConsumerTest](../../apps/server/src/test/java/com/chtholly/common/kafka/AbstractKafkaConsumerTest.java)、[ElasticsearchHealthIndicatorTest](../../apps/server/src/test/java/com/chtholly/health/ElasticsearchHealthIndicatorTest.java)、[DegradationGoldenPathIT](../../apps/server/src/test/java/com/chtholly/integration/DegradationGoldenPathIT.java)。
- **修改联动**：通用错误、线程池、Kafka 重试、配置默认值或健康语义变化会跨领域影响调用方，必须搜索所有消费者并补充集成验证。

## 继续阅读

- 状态权威性、配置来源与降级边界：[数据与存储](data-and-storage.md)
- 十条端到端调用链：[核心请求链路](request-flows.md)
- 启动、编译与测试命令：[后端运行入口](../../apps/server/README.md)
- Schema、migration 与种子操作：[数据库操作入口](../../apps/server/db/README.md)
