# 系统基准入口

本目录保存可版本化的 workload、manifest schema 和确定性 seed 逻辑。原始运行结果只写入仓库根目录下已忽略的 `.benchmark-results/`，不得强制加入 Git。

## 版本合同

每次运行必须分别记录：

- `subjectCommit`：被测业务实现；
- `executionCommit`：实际构建并启动服务的检出提交；首次仅增加 harness/data 的基线允许它晚于 `subjectCommit`；
- `harnessCommit`：本目录与 `scripts/benchmark/` 的版本；
- `datasetCommit`：seed 或评测数据定义的版本。

不同 harness 或 dataset 的结果不可直接比较。工作树不干净的运行只能用于调试，不能升级为 `REPRODUCED`。

## 入口

```powershell
# 先验证并启动本次运行独占的 MySQL、Redis、Kafka、Elasticsearch 和服务容器
./scripts/benchmark/environment.ps1 -Action Validate -RunId env-smoke -Profile smoke -Variant full
./scripts/benchmark/environment.ps1 -Action Start -RunId env-smoke -Profile smoke -Variant full

# 先写入确定性 MySQL 权威数据与 Redis Bitmap 成员状态；不直接伪造 SDS 等派生结果
./scripts/benchmark/seed.ps1 -Profile smoke -MysqlContainer mysql -RedisContainer redis

# 只验证参数、manifest 和环境快照，不启动服务或产生性能结论
./scripts/benchmark/run.ps1 -Profile smoke -Scenario all -RunId validate-local -Repetition 1 -ValidateOnly

# Dockerized k6 smoke；EnvironmentRunId 将 k6 接入同一隔离网络
./scripts/benchmark/run.ps1 -Profile smoke -Scenario all -RunId smoke-local -EnvironmentRunId env-smoke

# 运行结束后只停止并删除该 runId 拥有的容器和卷
./scripts/benchmark/environment.ps1 -Action Stop -RunId env-smoke

# 标准场景需要在看到结果前固定并发档位；默认取 profile 的第一档
./scripts/benchmark/run.ps1 -Profile standard -Scenario cache -Variant full -RunId cache-full-01 -Concurrency 16 -Repetition 1

# 确定性汇总；可选导出只读证据包到仓库外目录
./scripts/benchmark/summarize.ps1 -RunDirectory ./.benchmark-results/cache-full-01
./scripts/benchmark/summarize.ps1 -RunDirectory ./.benchmark-results/cache-full-01 -ArchiveDirectory $env:EVIDENCE_ARCHIVE_DIR
```

`counter` 与 `relation` 写场景需要由调用者在进程环境中提供 `BENCHMARK_TOKEN`。可用 `new-benchmark-token.ps1` 为确定性种子用户签发本地短期 token；脚本只记录 token 是否存在，不把内容写入 manifest。隔离环境内 k6 通过 `http://server:8888` 访问服务。

runId 是不可变证据身份，目录已存在时 runner 会失败关闭，不能覆盖旧结果。正式 standard 运行还会验证干净 worktree、`subjectCommit..executionCommit` 之间没有业务改动、harness/dataset 文件树、隔离环境源码提交及 JAR SHA-256。

## 数据状态

`smoke` 与 `-ValidateOnly` 只产生 `CONFIG` 级运行记录。正式结果至少需要 standard profile、冻结的提交与数据、三次独立运行、完整原始产物和人工复核；脚本不会自动把结果标记为 `REPRODUCED`。
