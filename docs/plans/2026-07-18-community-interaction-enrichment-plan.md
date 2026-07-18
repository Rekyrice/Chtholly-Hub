# 社区互动真实性增强实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** 将现有 44 篇公开文章扩充为覆盖完整、时间自然、人物关系可信的社区互动数据，并保证评论、关系、Redis bitmap、SDS、用户计数、缓存和搜索索引可重复收敛。

**Architecture:** `content-v3` 继续作为完整互动权威清单；评论和关注通过 MySQL identity 幂等管理，历史点赞/收藏通过专用静默对账服务精确设置受管理账号位并从 bitmap 重建计数。正式导入前同时备份 MySQL 与 Redis，导入后执行运行态审计和二次幂等复跑。

**Tech Stack:** Java 21、Spring Boot 3.2.4、MyBatis、MySQL、Redis、Elasticsearch、PowerShell、Node.js、YAML、JUnit 5、Mockito、Vitest/Node Test。

---

## Task 1：把互动规模和分布写进可执行合同

**Files:**

- Modify: `content/seed/content-v3/manifest.yml`
- Modify: `apps/server/src/main/java/com/chtholly/seed/contentpack/model/ContentPackManifest.java`
- Modify: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackValidator.java`
- Modify: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackValidatorTest.java`
- Modify: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackV3CommunityContractTest.java`

- [ ] 先写失败测试，要求 manifest 能声明评论、根评论、回复、点赞、收藏、关注、浏览和评论目标数量，并拒绝与当前清单不一致的值；本任务先填入当前实际值，Task 6 再与完整互动清单一起原子升级为 96/72/24、168/80、28、44、44。
- [ ] 增加 manifest 字段并让 Loader 正常反序列化。
- [ ] Validator 检查数量、评论层级、目标覆盖、每帖上限、reaction/follow 唯一性和时间顺序。
- [ ] 真实包合同增加账号参与范围、禁用模板话术和评论时间跨度断言。
- [ ] 运行 `mvn -q '-Dtest=ContentPackValidatorTest,ContentPackV3CommunityContractTest' test`。
- [ ] 提交：`test: 固化社区互动内容包合同`

## Task 2：实现历史反应事实精确对账

**Files:**

- Create: `apps/server/src/main/java/com/chtholly/counter/service/CounterFactMaintenanceService.java`
- Create: `apps/server/src/main/java/com/chtholly/counter/service/impl/CounterFactMaintenanceServiceImpl.java`
- Create: `apps/server/src/test/java/com/chtholly/counter/service/impl/CounterFactMaintenanceServiceImplTest.java`
- Modify: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackReactionApplier.java`
- Modify: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackReactionApplierTest.java`

- [ ] 先写失败测试：设置缺失受管理位、清除多余受管理位、保留有效非管理用户、清除不存在用户位。
- [ ] 写失败测试：按所有 bitmap shard 重算 like/fav，并原子保留 view 段、覆盖 SDS 对应段和清空 pending like/fav。
- [ ] 定义受限维护接口，只接受解析后的目标文章、受管理用户和期望事实，不暴露普通 Controller。
- [ ] 实现 SCAN/bitmap 枚举、用户存在性批量查询和目标级维护锁。
- [ ] `ContentPackReactionApplier` 改用精确对账，不再调用会发布通知的普通 like/fav 路径；view 保持 minimum 语义。
- [ ] 运行计数维护与 reaction applier 定向测试。
- [ ] 提交：`fix: 精确对账内容包互动计数`

## Task 3：修正导入后处理顺序和外部目标范围

**Files:**

- Modify: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackImportService.java`
- Modify: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackDatabaseWriter.java`
- Modify: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackImportServiceTest.java`

- [ ] 写失败测试，断言反应事实对账先于用户计数重建、缓存失效和搜索 upsert。
- [ ] 将外部文章 ID 加入缓存失效与搜索重建集合。
- [ ] 将内容包作者以及关注边新增或停用所影响的全部端点加入用户计数重建集合。
- [ ] 失败继续返回可重跑的 `partial`，不得在 bitmap 已收敛时重复累计。
- [ ] 运行 `ContentPackImportServiceTest` 与相关计数测试。
- [ ] 提交：`fix: 调整内容包运行态收敛顺序`

## Task 4：允许社区账号关注站长

**Files:**

- Modify: `apps/server/src/main/java/com/chtholly/seed/contentpack/model/SeedFollowDefinition.java`
- Modify: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackValidator.java`
- Modify: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackDatabaseWriter.java`
- Modify: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackValidatorTest.java`
- Modify: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackDatabaseWriterTest.java`

- [ ] 写失败测试，覆盖 `toAccountSeedKey`/`toHandle` 恰好一个、只允许站长 handle、禁止站长作为来源、自关注和重复边。
- [ ] 解析站长用户并复用现有稳定 follow identity，同时写两侧关系镜像；写入结果必须返回新旧受管理边的全部端点，使删除外部边时也会重建站长计数。
- [ ] 断言删除 manifest 边时只停用对应受管理关系，真实关系不受影响。
- [ ] 运行 Validator、DatabaseWriter 和真实包合同测试。
- [ ] 提交：`feat: 支持内容包账号关注站长`

## Task 5：升级审阅页和自动测试

**Files:**

- Modify: `scripts/seed-content/render-community-interaction-review.mjs`
- Create: `scripts/seed-content/render-community-interaction-review.test.mjs`
- Modify: `apps/web/package.json`
- Modify: `scripts/README.md`

- [ ] 先写 Node 失败测试，覆盖 HTML 转义、仓库内忽略路径门禁和确定性输出。
- [ ] 增加总量、账号分布、文章覆盖、零评论目标、热度和月份时间分布。
- [ ] 将新测试纳入 `npm run test:run`。
- [ ] 运行单文件 Node 测试并生成 `.codex-tmp/community-interactions/review/index.html`。
- [ ] 提交：`feat: 完善社区互动审阅报告`

## Task 6：重写完整互动清单

**Files:**

- Modify: `content/seed/content-v3/interactions.yml`
- Modify: `content/seed/content-v3/manifest.yml`
- Modify: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackV3CommunityContractTest.java`

- [ ] 保留既有稳定 seedKey；对既有评论也重排时间并按需修文，避免导入产生第二套重复线程。
- [ ] 补齐到 96 条评论，覆盖 44 个目标，按 8 个账号兴趣和语气分配。
- [ ] 补齐到 168 个点赞、80 个收藏；所有 44 个目标至少一个点赞，收藏保持长尾。
- [ ] 补齐到 28 条关注，其中 6 条指向 `Rekyrice`。
- [ ] 调整 44 个浏览最低值到 48 至 420 的非均匀长尾。
- [ ] 运行真实包合同、Validator 和审阅页测试，人工通读所有评论与线程。
- [ ] 提交：`feat: 丰富社区互动内容包`

## Task 7：补 Redis 正式备份入口

**Files:**

- Create: `scripts/backup/backup-redis.ps1`
- Create: `scripts/backup/test-backup-redis.ps1`
- Modify: `scripts/backup/README.md`
- Modify: `scripts/README.md`
- Modify: `docs/architecture/data-and-storage.md`

- [ ] 先写 PowerShell 合同测试，覆盖必填参数、绝对 D 盘仓库外路径、容器名安全、RDB 复制、SHA-256 metadata 和失败清理。
- [ ] 实现 `redis-cli --rdb`、`docker cp` 和宿主文件原子落盘，不打印密码。
- [ ] 更正文档中“Redis 互动可由 MySQL 重建”的错误描述。
- [ ] 运行 MySQL/Redis 两套备份脚本合同测试。
- [ ] 提交：`feat: 增加 Redis 互动状态备份`

## Task 8：完整自动化验证

**Files:**

- All task-scoped files.

- [ ] 运行后端所有定向测试。
- [ ] 运行 `mvn test`。
- [ ] 如维护服务使用真实 Redis 行为，运行相应 Testcontainers 集成测试。
- [ ] 运行 `npm run test:run` 和 `npm run build`。
- [ ] 运行 `git diff --check` 和任务范围检查。
- [ ] 请独立代码审查代理检查规范符合性和实现质量，修复所有高/中风险问题。

## Task 9：备份、正式导入与运行态验收

**Files:**

- Runtime evidence only: `.codex-tmp/community-interactions/`（Git ignored）

- [ ] 确认主站当前容器和配置，生成 MySQL 与 Redis 仓库外备份并校验 metadata/SHA-256。
- [ ] 执行 `run-seed.ps1 -Mode content-pack -DryRun`，保存摘要。
- [ ] 精确匹配并软删除两条旧测试评论，保存受影响行证据。
- [ ] 执行正式导入，保存 completed/partial 状态与各阶段摘要。
- [ ] 查询 96/72/24 评论、44 目标覆盖、168/80 受管理位、28 关注和 44 浏览下限。
- [ ] 核对无孤儿 bitmap、SDS 等于 bitmap、两侧关注镜像和用户计数一致。
- [ ] 第二次执行正式导入，比较前后快照，要求无重复、无新增通知、无计数漂移。
- [ ] 启动/复用本地主站，浏览器验收 Hub、代表性文章、八个主页和关注列表。

## Task 10：提交与交付

- [ ] 分职责审阅所有 diff，确认没有临时文件、凭据、备份或运行证据进入 Git。
- [ ] 暂存任务文件后执行新增忽略文件硬审计；任何输出都先修正。
- [ ] 按真实职责边界提交中文 Conventional Commits。
- [ ] 比较 `origin/main...HEAD`，确认只含本任务提交和文件。
- [ ] 按仓库规则只保留本地分支和 worktree，不主动 push；向维护者报告分支、提交、备份位置、运行态验收结果和下一步发布命令。
