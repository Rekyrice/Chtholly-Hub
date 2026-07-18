# 最小基准与评测入口

本目录只保存六条技术主线直接使用的确定性 seed、缓存 k6 场景、运行 manifest 和小规模 Agent 候选集。原始运行结果统一写入已忽略的 `.benchmark-results/`，不得使用 `git add -f`。

## 静态合同

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File benchmarks/tests/verify-datasets.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File benchmarks/tests/verify-harness.ps1
```

Agent 候选集位于 `benchmarks/datasets/agent-evaluation/`：27 条 Skill、45 条检索、5 条草稿流程和 2 条 Trace 回放。它们都标记为 `CANDIDATE_REQUIRES_OWNER_REVIEW`，在项目本人复核前不能作为人工 gold。

## 缓存环境与运行

缓存环境只启动 MySQL、Redis 和 Spring Boot；Kafka、Canal、Elasticsearch 与 LLM 均关闭，因此它不能证明关系 Outbox 或搜索链路。

```powershell
# 先检查计划，再启动一个 runId 独占的环境
./scripts/benchmark/environment.ps1 -Action Validate -RunId cache-env-01 -Profile smoke -Scenario stable-hot -Variant full
./scripts/benchmark/environment.ps1 -Action Start -RunId cache-env-01 -Profile smoke -Scenario stable-hot -Variant full

# ValidateOnly 只验证 manifest；真实运行必须绑定上面的 EnvironmentRunId
./scripts/benchmark/run.ps1 -Profile smoke -Scenario stable-hot -Variant full -RunId cache-config-01 -ValidateOnly
./scripts/benchmark/run.ps1 -Profile smoke -Scenario stable-hot -Variant full -RunId cache-full-01 -EnvironmentRunId cache-env-01

./scripts/benchmark/environment.ps1 -Action Stop -RunId cache-env-01
```

正式缓存数据固定为 12 次独立运行：

- `stable-hot`：`db-only` 与 `full` 各 3 次；
- `expiry-spike`：`full-no-singleflight` 与 `full` 各 3 次。

每次正式运行使用独立环境，记录 `subjectCommit`、`executionCommit`、`harnessCommit`、`datasetCommit`、环境身份和 repetition。只比较 p95、错误率、MySQL 查询次数与同 key 真实加载次数；任一原始指标缺失时汇总状态为 `INCOMPLETE`。

runner 会在 k6 启动前读取应用的 `chtholly.cache.runtime` 指标，校验实际读取模式、SingleFlight 状态和缓存指标能力；请求模式与运行态不一致时本次运行直接失败。`expiry-spike` 在测量前显式删除隔离 Redis 中的详情键、重启本次运行独占的 server，并用同 key 加载计数确认冷启动，不以固定等待时间代替失效。

`mysqlQueryCount` 只统计详情与公共 Feed 读取服务显式执行的 Mapper 查询，不代表进程内所有 JDBC 查询；`sameKeyLoadCount` 只在同 key 真正进入权威数据加载器时递增。

## 汇总

runner 会自动调用确定性汇总，也可重新执行：

```powershell
./scripts/benchmark/summarize.ps1 -RunDirectory ./.benchmark-results/cache-full-01

# 12 个 runId 必须显式列出；脚本拒绝缺失、重复、版本或环境不一致的矩阵
./scripts/benchmark/verify-matrix.ps1 -ResultsRoot ./.benchmark-results -RunIds @(
  'stable-db-1','stable-db-2','stable-db-3','stable-full-1','stable-full-2','stable-full-3',
  'expiry-nosf-1','expiry-nosf-2','expiry-nosf-3','expiry-full-1','expiry-full-2','expiry-full-3'
)
```

单次汇总只生成 `summary.json`、`summary.md` 和 `failures.md`；矩阵校验器在同一被忽略目录中生成 `cache-standard-matrix.json`。仓库不生成 ZIP、checksum 台账或证据晋级状态；原始结果与报告由运行者在被忽略目录中保留。
