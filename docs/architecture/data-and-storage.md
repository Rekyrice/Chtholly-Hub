# 数据与存储

## 阅读时机

修改表结构、Mapper、缓存键、事件通道、搜索索引、正文或媒体存储前阅读本章。数据库初始化和 migration 操作见[数据库操作入口](../../apps/server/db/README.md)。

## 读完能回答的问题

- 每类状态的权威来源在哪里，哪些副本可以重建？
- 对应代码入口和配置来源是什么？
- 依赖不可用或特性关闭时，业务是失败、返回降级结果，还是切换本地通道？
- 一致性窗口由本地事务、缓存失效、事件还是回填任务维护？

## MySQL

- **权威数据**：用户、文章元数据、标签、评论、关注写模型、通知、管理员审计、Outbox、Seed marker，以及可重建索引所需的源数据。MySQL 是最终业务事实来源。
- **用途与入口**：MyBatis Mapper 接口分散在各领域，例如 [PostMapper](../../apps/server/src/main/java/com/chtholly/post/mapper/PostMapper.java)、[RelationMapper](../../apps/server/src/main/java/com/chtholly/relation/mapper/RelationMapper.java)、[NotificationMapper](../../apps/server/src/main/java/com/chtholly/notification/mapper/NotificationMapper.java)；XML 位于 [`src/main/resources/mapper`](../../apps/server/src/main/resources/mapper)。Schema、migration 与 seed 位于 [`apps/server/db`](../../apps/server/db/README.md)。
- **配置来源**：[`application.yml`](../../apps/server/src/main/resources/application.yml) 的 `spring.datasource`，值由根目录 `.env` 对应环境变量注入；本文不记录实际凭据。
- **一致性与降级**：领域写入与 Outbox 使用本地事务；Redis/ES/Kafka 都不能覆盖 MySQL 事实。数据库不可用时核心写入失败，不提供把缓存当权威的降级。

## Redis

- **权威数据**：不保存最终业务事实；但刷新令牌白名单、验证码、登录失败窗口、限流窗口、会话记忆等运行态在有效期内由 Redis 判定。缓存、关系 ZSet、兴趣画像、点赞/收藏位图与 SDS 计数可以由源数据或事件重建。
- **用途与入口**：[AuthService](../../apps/server/src/main/java/com/chtholly/auth/service/AuthService.java) 与 auth store 管理验证码/Token；[PostFeedServiceImpl](../../apps/server/src/main/java/com/chtholly/post/service/impl/PostFeedServiceImpl.java) 使用 Feed 缓存；[CounterServiceImpl](../../apps/server/src/main/java/com/chtholly/counter/service/impl/CounterServiceImpl.java) 使用分片位图和 SDS；[RelationServiceImpl](../../apps/server/src/main/java/com/chtholly/relation/service/impl/RelationServiceImpl.java) 使用关系 ZSet；[AgentMemoryStore](../../apps/server/src/main/java/com/chtholly/agent/memory/AgentMemoryStore.java) 以 Redis List 配合 Caffeine 保存会话 turn。
- **配置来源**：[`application.yml`](../../apps/server/src/main/resources/application.yml) 的 `spring.data.redis`，Redisson Bean 见 [RedissonConfig](../../apps/server/src/main/java/com/chtholly/config/RedissonConfig.java)，缓存 TTL/热 key/feed 参数也在同一配置文件。
- **一致性与降级**：缓存未命中通常回源 MySQL并回填；文章写入主动失效相关缓存。身份验证、幂等位图、限流或聚合依赖 Redis 的路径在 Redis 不可用时可能直接失败，不能统一假设“自动回源”。Agent 记忆另有进程内 Caffeine 热副本，但持久会话仍依赖 Redis。

## Kafka 与进程内事件

- **权威数据**：Kafka 消息不是业务权威；计数事件可重放，Outbox 行保存在 MySQL。`kafka.enabled` 默认 `false`。
- **用途与入口**：启用 Kafka 时，[KafkaCounterPublisher](../../apps/server/src/main/java/com/chtholly/counter/event/KafkaCounterPublisher.java) 投递计数事件，[CounterAggregationKafkaConsumer](../../apps/server/src/main/java/com/chtholly/counter/event/CounterAggregationKafkaConsumer.java) 聚合；[CanalKafkaBridge](../../apps/server/src/main/java/com/chtholly/relation/outbox/CanalKafkaBridge.java) 将 Outbox CDC 结果转发到 Kafka，[CanalOutboxConsumer](../../apps/server/src/main/java/com/chtholly/relation/outbox/CanalOutboxConsumer.java) 更新关系 fan 侧，[CanalOutboxConsumerSearch](../../apps/server/src/main/java/com/chtholly/search/outbox/CanalOutboxConsumerSearch.java) 更新搜索索引。
- **配置来源**：[`application.yml`](../../apps/server/src/main/resources/application.yml) 的 `kafka.enabled`、`spring.kafka` 与 `canal`；重试、死信和幂等基础见 [AbstractKafkaConsumer](../../apps/server/src/main/java/com/chtholly/common/kafka/AbstractKafkaConsumer.java)。
- **一致性与降级**：Kafka 关闭或未配置时，计数明确切到 [SpringEventCounterPublisher](../../apps/server/src/main/java/com/chtholly/counter/event/SpringEventCounterPublisher.java) 与 [CounterAggregationSpringConsumer](../../apps/server/src/main/java/com/chtholly/counter/event/CounterAggregationSpringConsumer.java)，仍在进程内聚合；Kafka 模式也同步发布本地计数事件供通知使用。评论、关注通知和 Feed timeline 一直使用 Spring ApplicationEvent。Outbox 行仍会写入 MySQL，但关系 fan 表/ZSet 的 Outbox 消费没有等价的本地消费者；只有文章搜索写路径会额外尝试同步 ES。因此关闭 Kafka/Canal 不等于完整模拟 Outbox 下游。

## Elasticsearch

- **权威数据**：`posts` 等索引是从 MySQL与正文对象生成的可重建读模型，不是文章事实来源。
- **用途与入口**：[SearchIndexInitializer](../../apps/server/src/main/java/com/chtholly/search/index/SearchIndexInitializer.java) 建立 mapping，[SearchIndexService](../../apps/server/src/main/java/com/chtholly/search/index/SearchIndexService.java) 回填/upsert/软删，[SearchServiceImpl](../../apps/server/src/main/java/com/chtholly/search/service/impl/SearchServiceImpl.java) 执行全文查询与建议，[HubFeedSearchService](../../apps/server/src/main/java/com/chtholly/search/service/impl/HubFeedSearchService.java) 执行多区域查询。
- **配置来源**：[`application.yml`](../../apps/server/src/main/resources/application.yml) 的 `spring.elasticsearch.uris`，客户端 Bean 见 [ElasticsearchConfig](../../apps/server/src/main/java/com/chtholly/config/ElasticsearchConfig.java)。
- **一致性与降级**：文章发布/修改写 Outbox，并在请求内尽力同步索引；Kafka Outbox 消费和启动回填负责恢复。同步写失败只记录日志，业务提交仍以 MySQL 为准。搜索失败返回空页且 `degraded=true`，建议返回空列表，Hub 各区域返回 `degraded`；这不是 MySQL LIKE 替代查询。

## 默认本地文件存储

- **权威数据**：`storage.type=local` 时，Markdown 与上传媒体的对象字节位于配置的本地目录；MySQL只保存对象键、URL、大小和校验信息等元数据。
- **用途与入口**：[LocalFileStorageService](../../apps/server/src/main/java/com/chtholly/storage/LocalFileStorageService.java) 是 `matchIfMissing=true` 的默认 [StorageService](../../apps/server/src/main/java/com/chtholly/storage/StorageService.java) 实现，[LocalStorageWebConfig](../../apps/server/src/main/java/com/chtholly/storage/config/LocalStorageWebConfig.java) 暴露只读资源路径。
- **配置来源**：[`application.yml`](../../apps/server/src/main/resources/application.yml) 的 `storage.type`、`storage.local.base-path` 与 `public-url-prefix`，属性模型见 [StorageProperties](../../apps/server/src/main/java/com/chtholly/storage/config/StorageProperties.java)。
- **一致性与降级**：对象写入与 MySQL 元数据不是跨资源事务；发布前通过正文确认记录对象信息。目录不可创建或文件写失败时请求失败；本地模式不会自动切换 OSS，容器部署必须显式持久化挂载。

## 可选 OSS

- **权威数据**：`storage.type=oss` 时，对象字节由 OSS 保存，MySQL仍只保存业务元数据和对象定位信息。
- **用途与入口**：[OssStorageService](../../apps/server/src/main/java/com/chtholly/storage/OssStorageService.java) 在 `storage.type=oss` 时条件装配，支持预签名 PUT、服务端上传、删除和公开 URL；配置模型为 [OssProperties](../../apps/server/src/main/java/com/chtholly/storage/config/OssProperties.java)。
- **配置来源**：[`application.yml`](../../apps/server/src/main/resources/application.yml) 的 `oss` 与 `storage.type`，敏感值只通过环境变量提供，不在日志或文档中展开。
- **一致性与降级**：配置缺失或 OSS 操作失败会使当前存储操作失败，不自动回退本地；切换实现也不会搬迁已有对象。健康检查仅在 OSS 模式装配，见 [OssHealthIndicator](../../apps/server/src/main/java/com/chtholly/health/OssHealthIndicator.java)。

## 修改联动

- 表或字段：同步 schema/migration、Mapper 接口/XML、模型、Seed 与集成测试。
- 缓存键或事件契约：同步所有生产者、两种计数消费者、失效/回填和幂等逻辑。
- ES mapping：同步初始化、写文档、查询字段、回填与降级响应测试。
- 存储接口：同时验证本地与 OSS 条件实现、控制器、对象键校验和公开 URL。

## 继续阅读

- 领域归属与测试入口：[后端领域地图](backend.md)
- 状态如何贯穿真实请求：[核心请求链路](request-flows.md)
- 数据库本地操作：[数据库操作入口](../../apps/server/db/README.md)
- 运行配置入口：[后端运行入口](../../apps/server/README.md)
