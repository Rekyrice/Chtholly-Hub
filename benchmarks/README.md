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

`CACHE_READ_MODE` 必须先由生产缓存配置真实消费，并由 Java 测试证明三种模式行为不同，之后才能采集或引用对照结果。

## 汇总

runner 会自动调用确定性汇总，也可重新执行：

```powershell
./scripts/benchmark/summarize.ps1 -RunDirectory ./.benchmark-results/cache-full-01
```

汇总只生成 `summary.json`、`summary.md` 和 `failures.md`。仓库不生成 ZIP、checksum 台账或证据晋级状态；原始结果与报告由运行者在被忽略目录中保留。
