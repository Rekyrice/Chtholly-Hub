# 最小基准与评测入口

本目录只保存六条技术主线直接使用的确定性 seed、缓存 k6 场景、运行 manifest 和小规模 Agent 候选集。原始运行结果统一写入已忽略的 `.benchmark-results/`，不得使用 `git add -f`。

## 静态合同

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File benchmarks/tests/verify-datasets.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File benchmarks/tests/verify-trace-replay.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File benchmarks/tests/verify-harness.ps1
```

Agent 候选集位于 `benchmarks/datasets/agent-evaluation/`：27 条 Skill、45 条检索、5 条草稿流程和 2 条 Trace 回放。它们都标记为 `CANDIDATE_REQUIRES_OWNER_REVIEW`，在项目本人复核前不能作为人工 gold。

## Trace 导出与前后回放

`trace-replay.ps1` 的 `TraceId` 对应现有 Trace API 的 `correlationId`。API 只允许访问 loopback 地址，Admin Token 固定从 `CHOLLY_TRACE_ADMIN_TOKEN` 取得，不写入 manifest、日志或结果文件，也不会随重定向发送。固定问题与页面上下文来自仓库内的合成 fixture，并与 `trace_payload.input` 的三个 SHA-256 指纹逐一核对。导出仅保留组件版本、Skill、三路检索状态、Evidence 数量与快照哈希、引用校验、固定失败类型和汇总数值，不保留问题、页面正文、回答、逐调用明细或 Evidence 元数据。

```powershell
$env:CHOLLY_TRACE_ADMIN_TOKEN = '<local-admin-jwt>'
./scripts/benchmark/trace-replay.ps1 -Action Export `
  -RunId trace-loop-001-baseline -SampleId trace-replay-001 -SubjectRole baseline `
  -SubjectCommit 2d613e81 -HarnessCommit HEAD -DatasetCommit HEAD `
  -TraceId '<correlationId>'

./scripts/benchmark/trace-replay.ps1 -Action Export `
  -RunId trace-loop-001-candidate -SampleId trace-replay-001 -SubjectRole candidate `
  -SubjectCommit 6c8e694c -HarnessCommit HEAD -DatasetCommit HEAD `
  -TraceId '<correlationId>'

./scripts/benchmark/trace-replay.ps1 -Action Compare -RunId trace-loop-001-compare `
  -BaselineRunDirectory ./.benchmark-results/trace-loop-001-baseline `
  -CandidateRunDirectory ./.benchmark-results/trace-loop-001-candidate
```

第二组固定提交对是 `6c8e694c → 314700cc`，用于验证未知引用在发送前被 Evidence 校验阻断。Compare 只接受相同 `sampleId`、harness、dataset、输入、数据快照与环境指纹，并生成 `manifest.json`、`replay.json`、`diff.csv`、中文 `summary.md` 和 `failures.md`。

合同测试可把模拟响应放入 `.benchmark-results/` 或 `.codex-tmp/`，通过 `-TraceResponsePath -AllowUncommittedHarness` 离线执行；这类结果固定标记为 `OFFLINE_UNVERIFIED`，且 manifest 明示 `WORKTREE_UNCOMMITTED`，只能证明脱敏与比较合同。当前入口没有外部 runtime manifest 作为制品、数据和环境身份的独立锚点，因此 API 结果一律标记为 `API_UNVERIFIED`，不会根据 Trace 内的自声明自行晋级 `REAL_TRACE`。

两组历史 subject commit 都早于当前固定 Trace schema，本身无法直接生成脚本要求的 payload；因此仓库内的两组确定性结果保持 `OFFLINE_UNVERIFIED`，不得冒充真实前后 Trace。要晋级真实证据，必须运行对应 subject 的受控插桩构建，并增加对独立 runtime manifest 的校验，以绑定实际执行提交、制品哈希、数据指纹和环境指纹。

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
