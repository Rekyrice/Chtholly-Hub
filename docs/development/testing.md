# 测试与验证

命令以 [`apps/server/pom.xml`](../../apps/server/pom.xml)、[`apps/web/package.json`](../../apps/web/package.json)、[CI](../../.github/workflows/ci.yml) 和后端 [`TESTING.md`](../../apps/server/TESTING.md) 为准。除特别说明外，命令从表中标出的目录执行。

| 验证层级 | 命令 | 适用场景 | 外部依赖 | 预期结果 |
|----------|------|----------|----------|----------|
| 后端定向测试 | `cd apps/server` 后 `mvn -q '-Dtest=ClassATest,ClassBTest' test` | 单个领域或少量类的快速反馈；PowerShell 中多个类名参数整体加引号 | 测试声明的依赖；涉及 Redis 的测试按测试配置准备 | 指定 Surefire 测试通过，报告在 `target/surefire-reports` |
| 后端全量快速测试 | `cd apps/server` 后 `mvn test -Dspring.profiles.active=test` | 后端逻辑、配置或事件改动的常规回归；与 CI `backend-test` 一致 | CI 提供 Redis；本地按测试配置准备 | Surefire 测试通过并生成 JaCoCo 报告 |
| Testcontainers 集成测试 | `cd apps/server` 后 `mvn verify -Pintegration-test` | MySQL、Kafka、Elasticsearch、Redis/网络故障等真实基础设施链路 | 可用的 Docker Engine；首次运行需拉取镜像 | Failsafe 执行 `**/*IT.java`，报告在 `target/failsafe-reports` |
| 前端 Vitest | `cd apps/web` 后 `npm run test:run` | 组件、service、hook 和交互行为回归 | 已执行 `npm ci` 或 `npm install`；通常不需要运行后端 | `vitest run` 一次性结束且全部测试通过 |
| 前端生产构建 | `cd apps/web` 后 `npm run build` | 验证类型、路由、Server/Client 边界和 standalone 输出 | 已安装依赖；所需 Next 变量由 `apps/web/.env.local` 或进程注入 | `next build` 成功并生成 `.next` |
| 文档与 Git | 根目录执行 `git diff --check`、`git status --short`；文档任务再检查本地链接 | 所有提交前，尤其是文档导航、路径和命令变更 | Git；链接检查不访问网络 | 无空白错误；状态与任务范围一致；仓库内链接目标存在 |

## Surefire 与 Failsafe 的边界

普通 `mvn test` 只执行 Surefire 的快速测试，**不会执行** `*IT.java`。`integration-test` profile 会跳过 Surefire，并通过 Maven Failsafe 在 `integration-test`/`verify` 阶段只包含 `**/*IT.java`。因此后端全量快速测试通过，不能替代 Testcontainers 集成测试；是否运行集成测试应由改动风险决定。

CI 也保持相同隔离：`backend-test` 运行 `mvn test -Dspring.profiles.active=test`，`integration-test` 单独运行 `mvn verify -Pintegration-test`，前端 Job 运行 `npm ci` 与 `npm run build`。当前 CI 没有单独运行前端 Vitest，涉及前端行为时必须在本地补跑 `npm run test:run`。

## 选择验证范围

- 纯文档导航：链接目标存在、命令与脚本一致、`git diff --check`。
- 后端单领域：先定向测试，再跑全量快速测试；触及外部系统边界时加集成测试。
- 前端行为：Vitest 与生产构建都运行；只有单测不能证明 Next.js 生产边界可构建。
- 数据库或部署：除静态检查外，在隔离环境验证 schema/增量顺序和健康检查，避免对生产数据试跑。

## 最小基准合同

根目录依次执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File benchmarks/tests/verify-datasets.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File benchmarks/tests/verify-trace-replay.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File benchmarks/tests/verify-harness.ps1
```

数据集合同固定 27 条 Skill、45 条检索、5 条草稿流程与 2 条 Trace 回放候选，并拒绝旧任务租约、双审和 signoff 语义；所有候选在项目本人复核前保持 `CANDIDATE_REQUIRES_OWNER_REVIEW`。Trace 合同验证白名单脱敏、输入指纹、准确提交对、角色顺序和相同数据/环境约束；缓存 harness 合同验证两个缓存场景、三个实际变体、最小 manifest、隔离环境与原始汇总入口。静态合同通过不等于已经产生真实性能数字或真实 Trace 证据。

历史 Trace 回放要求工作树干净，并分别绑定执行/harness 提交与数据集提交。runner 对三个历史 subject 执行 `git archive`，先单独运行候选提交声明的方法级回归测试，再用 `-Pintegration-test` 执行 Testcontainers 探针；MySQL 原始 `trace_payload` 必须与 `TraceQueryService` 回读一致，生产源码摘要在探针前后不得变化。Maven 日志、Surefire/Failsafe XML、安全观测投影、环境与 SHA-256 清单统一写入被忽略的 `.benchmark-results/<runId>/`。提交对、命令和确定性替身边界见 [`benchmarks/README.md`](../../benchmarks/README.md)。

缓存正式数据仅运行 12 次固定对照：`stable-hot` 下 `db-only/full` 各 3 次，`expiry-spike` 下 `full-no-singleflight/full` 各 3 次。每次使用独立环境并保留 p95、错误率、MySQL 查询次数和同 key 真实加载次数；缺少任一原始指标的结果为 `INCOMPLETE`，不得用于比较。环境和命令详见 [`benchmarks/README.md`](../../benchmarks/README.md)。

## 互动关系与投影恢复验证

以下集成测试分别固定 Redis 5 重建语义、MySQL 事务事实，以及 Outbox/Kafka/Redis 的重复、乱序、故障与校准边界。它们必须使用 `integration-test` profile；普通 `mvn test` 不会执行。

```powershell
cd apps/server
mvn -q -Pintegration-test '-Dit.test=CounterFactMaintenanceLuaIT' verify
mvn -q -Pintegration-test '-Dit.test=CounterReactionFactsIT' verify
mvn -q -Pintegration-test '-Dit.test=CounterReactionLocalModeIT,CounterReactionRebuildConcurrencyIT,CounterReactionMigrationIT,CounterReactionSideEffectReceiptMigrationIT,DeadLetterReplayStateMigrationIT,DeadLetterReplayRecoveryIT' verify
mvn -q -Pintegration-test '-Dit.test=DataCleanupJobOutboxIT' verify
mvn -q -Pintegration-test '-Dit.test=CounterGoldenPathIT' verify
mvn -q -Pintegration-test '-Dit.test=DegradationGoldenPathIT' verify
```

`CounterFactMaintenanceLuaIT` 使用 Redis 5 覆盖 MySQL 关系分页重建、跨 shard、空关系、缺失物理 shard、token 所有权丢失、失败不发布完整标记、五键 pipeline 的失败键顺序与健康键幂等重试，以及 prepared→MySQL commit→complete 的发布顺序。`CounterReactionFactsIT` 使用真实 MySQL 验证首次/重复目标状态、fav/unfav、关系或 Outbox 失败全事务回滚，以及同目标并发只有一次真实变化。

`CounterReactionLocalModeIT` 经真实 Spring 多播固定提交后处理、回滚不触发、有限高水位校准与本地回放恢复；`CounterReactionRebuildConcurrencyIT` 使用真实 MySQL/Redis 固定重建锁、epoch fence 与并发写最终收敛。`CounterReactionMigrationIT` 验证 V25 关系表迁移，`CounterReactionSideEffectReceiptMigrationIT` 验证 V26 重入、索引修复、历史回执 checkpoint 与新行待发布状态；`DeadLetterReplayStateMigrationIT` 验证 V27 保留旧状态、增加 token/时间列并可重入，`DeadLetterReplayRecoveryIT` 在真实 MySQL 上验证单 claim、数据库截止恢复、三轮 token fencing，以及旧 broker 回调、旧人工 recover/resolve 都不能完成新 claim。`DataCleanupJobOutboxIT` 验证待处理 reaction Outbox 不会被保留期清理。`CounterGoldenPathIT` 使用 Canal-compatible envelope 验证 Outbox 重放幂等、old→new→old 的真实 Kafka 终态收敛、旧 epoch 事件隔离、Inbox/快照失败重试与从 MySQL 恢复 Redis 投影；`DegradationGoldenPathIT` 验证 Redis 故障不阻断 MySQL 关系与 Outbox 提交，恢复后可重放收敛。

快速测试中的 `CounterReactionProjectionStoreTest` 固定 pipeline 结果与精确失败键映射，`CounterReactionLocalOutboxReplayTest` 固定高水位/keyset、失败页推进、以 relation key 为单位的失败隔离、健康 peer 单批重试一次和未归因错误不放大，`CounterReactionSideEffectReceiptServiceTest` 固定行锁和事件级回执，`CounterReactionLuaContractTest` 固定 Redis 5 Lua 合同；`CounterReactionOutboxConsumerTest` 同时固定完整 Canal/Kafka 才装配消费者及相同的失败键语义，`AbstractKafkaConsumerTest` 固定 broker 确认前不 ACK、未到期 envelope 使用 `nack` 与损坏 retry 信封回到来源 DLQ，`DeadLetterControllerTest` / `DeadLetterMessageServiceTest` 固定 token 化 `DEAD → REPLAYING → PENDING` CAS、响应代际 token、同步失败恢复、HTTP 超时保持 claim、异步不确定结果、带 token 的过期恢复、普通 `RETRYING` 隔离和仅对同 token `UNCERTAIN` 的显式解决；`OutboxKafkaTopicConfigTest` 固定 retry topic 的 3 分区合同。完整投影的 Bitmap 快路径、结构不完整时的分块 MySQL 批量回源由 `CounterServiceImplBatchTest` 固定。核心投影允许异步延迟并可最终收敛，但不宣称 Redis、MySQL 与 Kafka 原子提交或 exactly-once；事件级回执也不保证多个监听器分别 exactly-once，部分非事务副作用在失败重放时仍可能重复，监听器内部吞错也可能让单项副作用缺失而回执仍成功。

互动正确性证据使用独立、干净 worktree 执行：

```powershell
cd ../..
./scripts/benchmark/collect-counter-evidence.ps1 -RunId counter-correctness-001
```

结果写入被忽略的 `.benchmark-results/<runId>/counter-evidence.json`，并强制 `subjectCommit`、`harnessCommit`、`datasetCommit` 等于实际执行提交。它验证 8 个固定正确性指标，不生成或推断性能数字。
