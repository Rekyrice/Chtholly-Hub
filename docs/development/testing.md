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
powershell -NoProfile -ExecutionPolicy Bypass -File benchmarks/tests/verify-harness.ps1
```

前者固定 27 条 Skill、45 条检索、5 条草稿流程与 2 条 Trace 回放候选，并拒绝旧任务租约、双审和 signoff 语义；所有候选在项目本人复核前保持 `CANDIDATE_REQUIRES_OWNER_REVIEW`。后者验证两个缓存场景、三个实际变体、最小 manifest、隔离环境与原始汇总入口。静态合同通过不等于已经产生真实性能数字。

缓存正式数据仅运行 12 次固定对照：`stable-hot` 下 `db-only/full` 各 3 次，`expiry-spike` 下 `full-no-singleflight/full` 各 3 次。每次使用独立环境并保留 p95、错误率、MySQL 查询次数和同 key 真实加载次数；缺少任一原始指标的结果为 `INCOMPLETE`，不得用于比较。环境和命令详见 [`benchmarks/README.md`](../../benchmarks/README.md)。
