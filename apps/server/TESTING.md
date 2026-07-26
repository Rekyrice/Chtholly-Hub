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
- `CounterServiceImplBatchTest`：完整投影快路径和不完整投影的 MySQL 批量回源。
- `CounterReactionFactsIT`：真实 MySQL 关系/Outbox 原子性与并发。
- `CounterFactMaintenanceLuaIT`：Redis 5 staging shard、token fence、完整性与跨 shard 重建。
- `CounterGoldenPathIT`：Canal-compatible Outbox、Kafka 重放/乱序、epoch 与最终收敛。
- `DegradationGoldenPathIT`：Redis 网络故障下 MySQL 提交边界与恢复。

定向命令示例：

```powershell
mvn -q '-Dtest=CounterReactionCommandServiceTest,CounterReactionEventProcessorTest,CounterServiceImplBatchTest' test
mvn -q -Pintegration-test '-Dit.test=CounterReactionFactsIT,CounterFactMaintenanceLuaIT,CounterGoldenPathIT,DegradationGoldenPathIT' verify
```

完整命令与证据采集入口见[测试与验证](../../docs/development/testing.md)。

## 提交前验收

1. 先运行改动职责对应的定向测试，再运行 `mvn test`。
2. 触及数据库、Redis、Kafka、Outbox、恢复或网络故障时运行 `mvn verify -Pintegration-test`。
3. 检查 Surefire/Failsafe 报告、`git diff --check`、`git status --short` 和新增文件忽略审计。
4. 不以未运行、跳过或只有 Mock 的结果宣称真实链路完成。
