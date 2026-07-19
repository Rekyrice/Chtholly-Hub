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

`-TraceResponsePath` 只用于被忽略目录中的合同 fixture，并固定标记为 `OFFLINE_UNVERIFIED`；当前 API 导出缺少独立 runtime manifest 锚点，一律标记为 `API_UNVERIFIED`。两组历史提交早于当前固定 Trace schema，在受控插桩、实际运行以及外部 runtime manifest 校验完成前不得晋级 `REAL_TRACE`。提交对、命令和输出边界见 [`benchmarks/README.md`](../../benchmarks/README.md)。

缓存正式数据仅运行 12 次固定对照：`stable-hot` 下 `db-only/full` 各 3 次，`expiry-spike` 下 `full-no-singleflight/full` 各 3 次。每次使用独立环境并保留 p95、错误率、MySQL 查询次数和同 key 真实加载次数；缺少任一原始指标的结果为 `INCOMPLETE`，不得用于比较。环境和命令详见 [`benchmarks/README.md`](../../benchmarks/README.md)。

## 互动计数恢复验证

以下两组集成测试分别固定 Redis 5 Lua 语义，以及 Redis/Kafka/MySQL 的批次重试、重启幂等、消息遗漏和周期校准。它们必须使用 `integration-test` profile；普通 `mvn test` 不会执行。

```powershell
cd apps/server
mvn -q -Pintegration-test '-Dit.test=CounterFactMaintenanceLuaIT' verify
mvn -q -Pintegration-test '-Dit.test=CounterGoldenPathIT' verify
```

`CounterFactMaintenanceLuaIT` 覆盖重复与并发状态切换、无过期维护 fence 的活跃拒绝与崩溃接管、epoch、索引与候选同时丢失、单个 shard 成员丢失、非零 cursor 跨实例续扫、持久候选轮转和 Redis 5 兼容性。`CounterGoldenPathIT` 分别验证 MySQL 快照失败会回滚 inbox 并允许同 event ID 重试、Kafka listener 对瞬时处理失败执行整批自动重试、消费者重启、DLT broker 确认，以及“Lua 已更新 Bitmap/SDS 但事件未投递”后由周期校准恢复 Bitmap、Redis SDS 与 MySQL 快照一致；前两项是独立故障路径，不将其合并宣称为一次端到端故障。该链路只承诺最终收敛，不宣称 Redis 与 Kafka 原子提交或 exactly-once。
