# 后端测试套件维护指南

## 执行边界

- 普通单元、Spring MVC 切片和应用上下文测试由 Maven Surefire 执行：`mvn test -Dspring.profiles.active=test`。
- `*IT.java` 只由 `integration-test` Profile 下的 Maven Failsafe 执行：`mvn verify -Pintegration-test`。
- Surefire 通过不能替代 MySQL、Redis、Kafka、Elasticsearch 或 Toxiproxy 的真实集成验证；两类报告分别位于 `target/surefire-reports` 与 `target/failsafe-reports`。
- 测试数量会随功能增长，不在文档中固化容易漂移的总数；验收以命令退出码和报告中的失败/错误数为准。

## 测试设计约束

1. 功能或修复先写能够因目标行为缺失而失败的测试，再做最小实现。
2. 并发、重试和异步链路使用 latch、Awaitility、消费组 offset 或业务终态等待，不以固定 `sleep` 证明正确性。
3. 只有三个及以上测试共享稳定构造时才提取夹具；优先使用业务语义命名的局部 helper。
4. Testcontainers 用例验证真实基础设施边界，不用全 Mock 结果替代事务、Lua、broker ACK 或网络故障证据。
5. 测试主动制造的预期异常可以定向降噪，但非预期 WARN/ERROR 必须保留可见。

## 互动关系验证地图

- `CounterReactionCommandServiceTest`：目标状态幂等、同事务 Outbox、输入与行数约束。
- `CounterReactionEventProcessorTest`：MySQL 终态回查、投影顺序、Inbox/快照与副作用边界。
- `CounterReactionLocalOutboxReplayTest`：固定高水位/keyset 扫描、精确失败关系键隔离、健康 peer 单批提交与未归因错误不放大。
- `CounterReactionSideEffectReceiptServiceTest`：事件级发布行锁、成功回执和失败保持待处理。
- `CounterReactionProjectionStoreTest`：Redis pipeline 结果、精确失败键顺序与健康键幂等重试。
- `CounterServiceImplBatchTest`：完整投影快路径和不完整投影的 MySQL 批量回源。
- `CounterReactionLuaContractTest`：Redis 5 脚本加载、原子去重和类型校验合同。
- `CounterReactionFactsIT`：真实 MySQL 关系/Outbox 原子性与并发。
- `CounterReactionLocalModeIT`：真实 Spring 多播、提交/回滚边界、本地 Outbox 恢复和有限高水位校准。
- `CounterReactionMigrationIT` / `CounterReactionSideEffectReceiptMigrationIT` / `DeadLetterReplayStateMigrationIT`：V25/V26/V27 迁移、重入、索引、历史回执 checkpoint，以及人工重放状态、token 和时间列。
- `DeadLetterReplayRecoveryIT`：真实 MySQL 单 claim、数据库截止恢复、三轮 token fencing，以及旧回调和旧人工请求隔离。
- `DataCleanupJobOutboxIT`：清理任务只删除已有副作用回执的 reaction Outbox。
- `CounterReactionRebuildConcurrencyIT`：MySQL 行锁、epoch fence 与重建期间并发写收敛。
- `CounterFactMaintenanceLuaIT`：Redis 5 live shard、token fence、prepared/dirty/complete、跨 shard 重建与五键 pipeline 失败顺序。
- `CounterGoldenPathIT`：Canal-compatible Outbox、真实 Kafka old→new→old 重放/乱序、epoch 与最终收敛。
- `DegradationGoldenPathIT`：Redis 网络故障下 MySQL 提交边界与恢复。
- `CounterReactionOutboxConsumerTest`：完整 Canal/Kafka 条件装配、失败关系键隔离和健康 peer 单批提交。
- `AbstractKafkaConsumerTest` / `OutboxKafkaTopicConfigTest`：broker 确认前不 ACK、retry `nack`、来源 DLQ 与主题分区合同。
- `DeadLetterControllerTest` / `DeadLetterMessageServiceTest`：token 化 `DEAD → REPLAYING → PENDING` CAS、响应代际 token、HTTP 超时保持 claim、同 token `UNCERTAIN` 结果核对、过期恢复、自动 `RETRYING` 隔离与显式解决。
- `NotificationEventListenerTest` / `FeedCacheInvalidationListenerTest` / `UserInterestProfileListenerTest`：三个下游监听器的独立行为。

定向命令示例：

```powershell
mvn -q '-Dtest=CounterReactionCommandServiceTest,CounterReactionEventProcessorTest,CounterReactionProjectionStoreTest,CounterReactionOutboxConsumerTest,CounterReactionLocalOutboxReplayTest,CounterReactionSideEffectReceiptServiceTest,CounterServiceImplBatchTest,CounterReactionLuaContractTest,AbstractKafkaConsumerTest,OutboxKafkaTopicConfigTest,DeadLetterControllerTest,DeadLetterMessageServiceTest' test
mvn -q -Pintegration-test '-Dit.test=CounterReactionFactsIT,CounterReactionLocalModeIT,CounterReactionMigrationIT,CounterReactionSideEffectReceiptMigrationIT,DeadLetterReplayStateMigrationIT,DeadLetterReplayRecoveryIT,DataCleanupJobOutboxIT,CounterReactionRebuildConcurrencyIT,CounterFactMaintenanceLuaIT,CounterGoldenPathIT,DegradationGoldenPathIT' verify
```

本地 Outbox 回放和事件级 `side_effects_published_at` 能恢复进程崩溃后仍未完成的事件，但不记录每个监听器的独立完成状态；某个非事务监听器成功、后续监听器失败时，下一次整事件重放仍可能重复前者。完整命令与证据采集入口见[测试与验证](../../docs/development/testing.md)。

## 提交前验收

1. 先运行改动职责对应的定向测试，再运行 `mvn test`。
2. 触及数据库、Redis、Kafka、Outbox、恢复或网络故障时运行 `mvn verify -Pintegration-test`。
3. 检查 Surefire/Failsafe 报告、`git diff --check`、`git status --short` 和新增文件忽略审计。
4. 不以未运行、跳过或只有 Mock 的结果宣称真实链路完成。
