# 最小基准与评测入口

本目录只保存六条技术主线直接使用的确定性 seed、缓存 k6 场景、运行 manifest 和小规模 Agent 候选集。原始运行结果统一写入已忽略的 `.benchmark-results/`，不得使用 `git add -f`。

## 静态合同

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File benchmarks/tests/verify-datasets.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File benchmarks/tests/verify-trace-replay.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File benchmarks/tests/verify-harness.ps1
```

Agent 候选集位于 `benchmarks/datasets/agent-evaluation/`：27 条 Skill、45 条检索、5 条草稿流程和 2 条 Trace 回放。它们都标记为 `CANDIDATE_REQUIRES_OWNER_REVIEW`，在项目本人复核前不能作为人工 gold。

## 检索候选诊断

检索采集器把 45 行候选按完全相同的 query 合并为 42 个 observation，并对三组重复 query 的建议相关文档取并集。它使用真实 Testcontainers MySQL/Elasticsearch 和本地文件 HTTP 正文，但语料由候选 `scenarioContext` 确定性构造，embedding 是本地 hash 适配器；因此结果固定标记为 `COLLECTED_UNREVIEWED`、`formalGold=false`、`semanticQualityEvidence=false`。

先提交 harness，再以生产检索提交、harness 提交和数据集提交三个独立身份运行：

```powershell
./scripts/benchmark/collect-retrieval-evidence.ps1 `
  -RunId retrieval-candidate-001 `
  -SubjectCommit 70ea7da5 `
  -HarnessCommit HEAD `
  -DatasetCommit 9f057df6
```

结果写入 `.benchmark-results/<runId>/`，包含 manifest、环境快照、42 条原始排名、四项指标汇总、失败样本和 SHA-256 清单。只比较 `keyword-only`、`vector-only` 与 `three-way-document-rrf` 的 Recall@5、MRR、引用合法率和无答案正确率。未运行回答生成时，引用合法率必须保持 `null / NOT_OBSERVED_NO_GENERATION`；不得把检索结果自动包装成引用，也不得把这些数值称为真实模型或生产语义质量。

## 历史 Trace 前后回放

`trace-replay.ps1` 从数据集固定的三个历史 subject commit 创建 `git archive`，只向归档的 `src/test` 注入同一观察探针，再用 Testcontainers 执行真实 `HybridSearchService`、`ChthollyAgent`、`TracePersistenceService` 和 `TraceQueryService`。生产源码在运行前后计算摘要且不得改变；模型与检索上游使用确定性本地适配器，`externalModelCalls` 固定为 0，不读取 API Key，也不会产生模型费用。

先提交 harness，再绑定 harness 与数据集提交运行：

```powershell
./scripts/benchmark/trace-replay.ps1 `
  -RunId trace-real-001 `
  -HarnessCommit HEAD `
  -DatasetCommit 282dc7e0
```

两组固定提交对分别为 `2d613e81 → 6c8e694c`（实体候选没有映射为文章 Evidence）和 `6c8e694c → 314700cc`（未知引用在输出前被 Evidence 门阻断）。采集阶段只向探针传递 `sampleId`、角色、问题和页面上下文，不传递 `expected`、根因或改动说明；全部真实观测落盘后，runner 才使用数据集预期进行比较。

结果写入 `.benchmark-results/<runId>/`，包含四份原始安全投影、历史 subject tree 与生产源码摘要、实际执行的回归测试、环境快照、比较结果、失败清单和 SHA-256 清单。旧 Trace schema 中没有的检索与引用字段由测试观察层从真实策略返回、客户端事件和 Memory 写入结果规范化；数据库原始 Trace payload 只保留 SHA-256，不修改历史生产 Trace。满足所有身份、MySQL Trace 行、查询回读、输入指纹和外部调用约束时结果标记为 `REAL_TRACE`，但候选标签仍是 `CANDIDATE_REQUIRES_OWNER_REVIEW / COLLECTED_UNREVIEWED`，不能作为人工 gold。

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

## 互动计数正确性采集

互动计数不做吞吐矩阵。下面的入口使用真实 Redis、Kafka 与 MySQL 执行固定序列，并将结果写入已忽略的 `.benchmark-results/<runId>/counter-evidence.json`：

```powershell
./scripts/benchmark/collect-counter-evidence.ps1 -RunId counter-correctness-001
```

固定序列包含 8 次点赞/取消请求，其中 4 次真实改变 Bitmap 与 Redis 即时计数并产生 Kafka 事件；随后原样重放第一条 `eventId`，因此 Kafka 记录数为 5、去重命中为 1。采集器显式执行两个聚合批次并遗漏最后一条真实事件，校准前差异为 1；Bitmap 校准后 Redis 与 MySQL 差异归零。`mysqlUpdateCount=2` 只统计成功执行的 `incrementSnapshots` 与 `replaceReactionSnapshots`，不包含 inbox 幂等写入。

采集入口要求工作树干净、三个提交身份都等于实际执行提交，拒绝覆盖已有 runId。结果只记录八项固定正确性指标以及运行环境和提交身份，不据此宣称 QPS 或 exactly-once。

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
