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

- **权威数据**：Redis 不保存点赞/收藏业务事实。分片 Bitmap 是在线成员读投影，SDS 是计数投影；每个实体的完整性标记只在一次 MySQL 全量重建完成后发布。标记缺失、版本不符或投影结构异常时，单条与批量成员读取按本次请求的关系键分组、分块回退 `counter_reaction`，不能把缺失 bit 解释成“未互动”，也不会为每个结果逐条查询。
- **用途与入口**：[AuthService](../../apps/server/src/main/java/com/chtholly/auth/service/AuthService.java) 与 auth store 管理验证码/Token；[PostFeedServiceImpl](../../apps/server/src/main/java/com/chtholly/post/service/impl/PostFeedServiceImpl.java) 使用 Feed 缓存；[CounterServiceImpl](../../apps/server/src/main/java/com/chtholly/counter/service/impl/CounterServiceImpl.java) 使用分片位图和 SDS；[RelationServiceImpl](../../apps/server/src/main/java/com/chtholly/relation/service/impl/RelationServiceImpl.java) 使用关系 ZSet；[AgentMemoryStore](../../apps/server/src/main/java/com/chtholly/agent/memory/AgentMemoryStore.java) 以 Redis List 配合 Caffeine 保存会话 turn。
- **配置来源**：[`application.yml`](../../apps/server/src/main/resources/application.yml) 的 `spring.data.redis`，Redisson Bean 见 [RedissonConfig](../../apps/server/src/main/java/com/chtholly/config/RedissonConfig.java)，缓存 TTL/热 key/feed 参数也在同一配置文件。
- **一致性与降级**：缓存未命中通常回源 MySQL并回填；文章写入主动失效相关缓存。互动事件处理器在 snapshot 行锁事务内批量回查 MySQL 终态，再用 Lua 幂等设置目标 bit 并维护 SDS；投影可能短暂落后于已提交关系。完整重建以实体级 Redisson 锁和 token fence 隔离并发，开始时删除完整性标记，直接有界清理并分页重写 live shard；Redis 先写 `@prepared`，MySQL snapshot epoch/绝对计数提交后再发布 `@mysql-v1` 完整标记并删除 fence。并发事件会把 fence 标脏并使重建重试，任一步失败都保持投影不完整。周期校准按固定 MySQL 字典序高水位有限扫描实体。完整性缺失或结构异常会触发批量 MySQL 读取；Redis 连接、管道或 Lua 执行异常则直接失败，不伪装成 MySQL 降级，也不在请求链路触发校准。当前 Redisson 与多 key Lua 仅按 standalone single-server 配置验证，未提供 Redis Cluster/Sentinel 拓扑合同。

## Kafka 与进程内事件

- **权威数据**：Kafka 消息不是业务权威；Outbox 行保存在 MySQL。仅 Kafka 模式保留可供消费组回放的计数事件。`application.yml` 中 `kafka.enabled` 的 Spring 属性缺省值是 `false`；仓库示例显式设置 `KAFKA_ENABLED=true`、`CANAL_ENABLED=false`，因此通用计数使用 Kafka，而互动 Outbox 使用本地提交后路径。
- **用途与入口**：点赞/收藏关系变化总是写 MySQL Outbox。仅当 Kafka 与 Canal 同时启用时，[CanalKafkaBridge](../../apps/server/src/main/java/com/chtholly/relation/outbox/CanalKafkaBridge.java) 才将 CDC 结果转发到 `canal-outbox`，[CounterReactionOutboxConsumer](../../apps/server/src/main/java/com/chtholly/counter/event/CounterReactionOutboxConsumer.java) 处理互动行；任一关闭时，[CounterReactionLocalAdapter](../../apps/server/src/main/java/com/chtholly/counter/event/CounterReactionLocalAdapter.java) 在事务提交后调用同一处理核心。浏览量等通用计数仍由 [KafkaCounterPublisher](../../apps/server/src/main/java/com/chtholly/counter/event/KafkaCounterPublisher.java) 与 [CounterAggregationKafkaConsumer](../../apps/server/src/main/java/com/chtholly/counter/event/CounterAggregationKafkaConsumer.java) 处理；关系 fan 侧和搜索索引继续使用各自 Outbox 消费者。
- **配置来源**：[`application.yml`](../../apps/server/src/main/resources/application.yml) 的 `kafka.enabled`、`spring.kafka`、`counter.kafka.*`、`counter.calibration.*` 与 `canal`。旧 `counter-events` 使用专用批量容器、有限重试和 DLT 确认；Outbox 消费者复用修正后的 `AbstractKafkaConsumer`。处理失败先写 `dead_letter_messages`，再把含 `deliverAfterEpochMs` 的信封发送到 `canal-outbox-retry`，第 1/2/3 次重试分别延迟 5/30/120 秒；broker 确认转发后才 ACK 当前记录。未到期信封通过 `nack` 保留原记录并可能形成分区头阻塞，第 3 次重试仍失败后转入 `canal-outbox-dlq`。
- **一致性与降级**：互动的 Kafka 与本地路径共用 MySQL 终态查询、Bitmap 投影和聚合组件；`CounterReactionEventProcessor` 以 `REQUIRES_NEW` 开启处理事务，`CounterAggregationProcessor` 以 `REQUIRED` 加入它。相同 `eventId` 由 `counter_event_inbox` 幂等，但这不等于 Kafka exactly-once。旧 epoch 的延迟事件可登记 Inbox并发布其真实关系变化副作用，却不能修改新 epoch 快照；投影失败会回滚当前 Inbox/快照事务，但不能回滚已经提交的关系与 Outbox。完整 Kafka 模式自动恢复止于三次持久 retry 与 DLQ：普通自动重试记录使用 `RETRYING`；进入 `DEAD` 后不会同时由本地任务扫描 MySQL，必须在修复根因后通过管理员死信接口显式重放。人工重放用唯一 attempt token 抢占 `REPLAYING`，并按数据库时钟记录开始时间和 `max.block.ms + delivery.timeout.ms + 30 秒` 的恢复截止时间；HTTP 只等待 10 秒，超时或中断不会取消 producer future，也不会提前开放重试。broker 终态回调只允许同 token 把行收敛到 `PENDING` 或 `UNCERTAIN`；状态写入失败时保留带 token 的 `REPLAYING`。管理员 API 响应返回当前 token 作为并发代际标识，过期恢复和人工 resolve 必须原样携带它；它不是授权凭证。只有超过截止时间且 token 匹配的 `REPLAYING` 才可先转为 `UNCERTAIN`，旧回调和旧人工请求都不能完成之后的新 claim，避免超时重放和 ABA。本地模式的定时任务使用固定高水位和 keyset 分页，只扫描缺少 Inbox 或事件级副作用回执为空且已提交至少 5 秒的行，失败页不会阻塞同一轮后续页，并在下一轮重新发现。健康页始终整批处理；Redis Lua 对维护 fence、投影结构错误或 UInt32 溢出返回精确失败关系键，原事务回滚后只把这些键留待恢复，其余健康事件仍以一个批次提交。无法归因的代码、MySQL、Redis、事务、连接或超时错误只执行整批一次，不做猜测式二分，也不会退化为逐事件 SQL/Redis 风暴。同步 Spring 事件调用全部返回后才写 `side_effects_published_at`；它不是逐监听器回执，部分非事务副作用仍可能在重放时重复，监听器若自行吞掉内部异常也可能留下缺失副作用而仍写回执。24 小时 Redis 标记只在回执提交后尽力写入，不压住待处理事件。校准只恢复 Bitmap、SDS 与快照，不补发副作用。`kafka.enabled=false` 时，浏览量等通用计数仍切到 [SpringEventCounterPublisher](../../apps/server/src/main/java/com/chtholly/counter/event/SpringEventCounterPublisher.java) 与 [CounterAggregationSpringConsumer](../../apps/server/src/main/java/com/chtholly/counter/event/CounterAggregationSpringConsumer.java)。浏览量的可选旧回放与 reaction 成员事实恢复严格分离。

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
