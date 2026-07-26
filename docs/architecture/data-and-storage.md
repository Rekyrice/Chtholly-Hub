# 数据与存储

## 阅读时机

修改表结构、Mapper、缓存键、事件通道、搜索索引、正文或媒体存储前阅读本章。数据库初始化和 migration 操作见[数据库操作入口](../../apps/server/db/README.md)。

## 读完能回答的问题

- 每类状态的权威来源在哪里，哪些副本可以重建？
- 对应代码入口和配置来源是什么？
- 依赖不可用或特性关闭时，业务是失败、返回降级结果，还是切换本地通道？
- 一致性窗口由本地事务、缓存失效、事件还是回填任务维护？

## MySQL

- **权威数据**：用户、文章元数据、标签、评论、关注写模型、点赞/收藏成员关系、通知、管理员审计、Outbox、Seed marker，以及可重建索引所需的源数据。`counter_reaction` 是点赞/收藏成员关系的唯一业务事实；`counter_event_inbox` 是事件幂等记录，`counter_snapshot` 是派生计数快照。
- **用途与入口**：MyBatis Mapper 接口分散在各领域，例如 [PostMapper](../../apps/server/src/main/java/com/chtholly/post/mapper/PostMapper.java)、[RelationMapper](../../apps/server/src/main/java/com/chtholly/relation/mapper/RelationMapper.java)、[NotificationMapper](../../apps/server/src/main/java/com/chtholly/notification/mapper/NotificationMapper.java)；XML 位于 [`src/main/resources/mapper`](../../apps/server/src/main/resources/mapper)。Schema、migration 与 seed 位于 [`apps/server/db`](../../apps/server/db/README.md)。
- **配置来源**：[`application.yml`](../../apps/server/src/main/resources/application.yml) 的 `spring.datasource`，值由根目录 `.env` 对应环境变量注入；本文不记录实际凭据。
- **一致性与降级**：点赞/收藏只在目标关系真实变化时把关系与一条 Outbox 同事务提交；重复目标状态不产生 Outbox。Redis/ES/Kafka 不能覆盖已经落在 MySQL 的事实。数据库不可用时核心写入失败，不提供把缓存当权威的降级。

## Redis

- **权威数据**：Redis 不保存点赞/收藏业务事实。分片 Bitmap 是在线成员读投影，SDS 是计数投影；每个实体的完整性标记只在一次 MySQL 全量重建完成后发布。标记缺失、版本不符或投影结构异常时，单条与批量成员读取回退 `counter_reaction`，不能把缺失 bit 解释成“未互动”。
- **用途与入口**：[AuthService](../../apps/server/src/main/java/com/chtholly/auth/service/AuthService.java) 与 auth store 管理验证码/Token；[PostFeedServiceImpl](../../apps/server/src/main/java/com/chtholly/post/service/impl/PostFeedServiceImpl.java) 使用 Feed 缓存；[CounterServiceImpl](../../apps/server/src/main/java/com/chtholly/counter/service/impl/CounterServiceImpl.java) 使用分片位图和 SDS；[RelationServiceImpl](../../apps/server/src/main/java/com/chtholly/relation/service/impl/RelationServiceImpl.java) 使用关系 ZSet；[AgentMemoryStore](../../apps/server/src/main/java/com/chtholly/agent/memory/AgentMemoryStore.java) 以 Redis List 配合 Caffeine 保存会话 turn。
- **配置来源**：[`application.yml`](../../apps/server/src/main/resources/application.yml) 的 `spring.data.redis`，Redisson Bean 见 [RedissonConfig](../../apps/server/src/main/java/com/chtholly/config/RedissonConfig.java)，缓存 TTL/热 key/feed 参数也在同一配置文件。
- **一致性与降级**：缓存未命中通常回源 MySQL并回填；文章写入主动失效相关缓存。互动事件处理器先回查 MySQL 终态，再用 Lua 幂等设置目标 bit 并维护 SDS；投影可能短暂落后于已提交关系。完整重建以 Redisson 锁和 token fence 隔离并发恢复，按 MySQL 关系分页构造 staging shard，只在全部批次成功后有界切换索引、SDS、epoch 与完整性标记；失败时保持标记不完整，读侧继续回源 MySQL。Redis 数据丢失可由 [CounterCalibrationService](../../apps/server/src/main/java/com/chtholly/counter/service/impl/CounterCalibrationService.java) 从 MySQL 恢复。

## Kafka 与进程内事件

- **权威数据**：Kafka 消息不是业务权威；Outbox 行保存在 MySQL。仅 Kafka 模式保留可供消费组回放的计数事件。`application.yml` 中 `kafka.enabled` 的 Spring 属性缺省值是 `false`；仓库推荐从 [`.env.example`](../../.env.example) 复制 `.env`，示例显式设置 `KAFKA_ENABLED=true`，因此按推荐本地启动流程运行时需要 Kafka，除非维护者将其改为 `false`。
- **用途与入口**：点赞/收藏关系变化总是写 MySQL Outbox。启用 Kafka/Canal 时，[CanalKafkaBridge](../../apps/server/src/main/java/com/chtholly/relation/outbox/CanalKafkaBridge.java) 将 CDC 结果转发到 `canal-outbox`，[CounterReactionOutboxConsumer](../../apps/server/src/main/java/com/chtholly/counter/event/CounterReactionOutboxConsumer.java) 处理互动行；关闭 Kafka 时，[CounterReactionLocalAdapter](../../apps/server/src/main/java/com/chtholly/counter/event/CounterReactionLocalAdapter.java) 在事务提交后调用同一处理核心。浏览量等通用计数仍由 [KafkaCounterPublisher](../../apps/server/src/main/java/com/chtholly/counter/event/KafkaCounterPublisher.java) 与 [CounterAggregationKafkaConsumer](../../apps/server/src/main/java/com/chtholly/counter/event/CounterAggregationKafkaConsumer.java) 处理；关系 fan 侧和搜索索引继续使用各自 Outbox 消费者。
- **配置来源**：[`application.yml`](../../apps/server/src/main/resources/application.yml) 的 `kafka.enabled`、`spring.kafka`、`counter.kafka.*`、`counter.calibration.*` 与 `canal`。计数消费者使用专用批量容器、有限重试和 DLT 确认，不复用会在 `finally` 中确认的通用消费者基类。
- **一致性与降级**：互动的 Kafka 与本地路径共用 MySQL 终态查询、Bitmap 投影和聚合组件；相同 `eventId` 由 `counter_event_inbox` 幂等，但这不等于 Kafka exactly-once。旧 epoch 的延迟事件可登记 Inbox，却不能修改新 epoch 快照；投影失败不回滚已提交关系，Outbox 重放或 MySQL 全量校准负责恢复。`kafka.enabled=false` 时，浏览量等通用计数仍切到 [SpringEventCounterPublisher](../../apps/server/src/main/java/com/chtholly/counter/event/SpringEventCounterPublisher.java) 与 [CounterAggregationSpringConsumer](../../apps/server/src/main/java/com/chtholly/counter/event/CounterAggregationSpringConsumer.java)。浏览量的可选旧回放与 reaction 成员事实恢复严格分离。

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
- 缓存键或事件契约：同步互动 Outbox 的 Kafka/本地路径、通用计数消费者、投影完整性、恢复与幂等逻辑。
- ES mapping：同步初始化、写文档、查询字段、回填与降级响应测试。
- 存储接口：同时验证本地与 OSS 条件实现、控制器、对象键校验和公开 URL。

## 继续阅读

- 领域归属与测试入口：[后端领域地图](backend.md)
- 状态如何贯穿真实请求：[核心请求链路](request-flows.md)
- 数据库本地操作：[数据库操作入口](../../apps/server/db/README.md)
- 运行配置入口：[后端运行入口](../../apps/server/README.md)
