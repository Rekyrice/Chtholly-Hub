# 内容包退役与备份实施计划

> 日期：2026-07-15
> 设计依据：`docs/design/2026-07-15-content-pack-retirement-backup-design.md`

## 目标

把内容包导入流程扩展为可审计、可重复的正式初始化机制：导入 40 篇内容包文章和 8 个内容包账号，同时只按严格白名单软删除 34 篇已确认的低质量旧文章，始终保护 Rekyrice 账号的文章；在修改正式库前完成独立数据库演练，并为 MySQL 建立 D 盘备份入口。

互动数据不属于本轮实施范围，继续保持 `interactions.yml` 为空，后续单独设计和导入。

## 验收口径

- `content-v3` 必须包含 34 条精确 slug 的退役清单；旧内容包版本可没有该文件。
- 退役只匹配完整 slug，不允许正则、前缀或按作者批量删除。
- 命中 Rekyrice 或本次内容包文章时，整个数据库事务回滚。
- 已删除文章再次导入时不重复扣减标签和计数；不存在的白名单项只报告，不失败。
- 文件写入、数据库写入、缓存、搜索索引与报告共同覆盖新增和退役文章。
- 隔离数据库连续导入两次：第二次没有新增文章，也没有新增退役操作。
- 正式数据库操作前，在项目外的 D 盘目录生成压缩 SQL、SHA-256 和元数据。
- 正式数据库最终保留 40 篇内容包文章及导入前已有的全部 Rekyrice 文章。

## 实施步骤

### 1. 扩展内容包模型与加载器

涉及文件：

- `apps/server/src/main/java/com/chtholly/seed/contentpack/model/SeedPostRetirementDefinition.java`
- `apps/server/src/main/java/com/chtholly/seed/contentpack/model/ContentPack.java`
- `apps/server/src/main/java/com/chtholly/seed/contentpack/model/ContentPackManifest.java`
- `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackLoader.java`
- `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackLoaderTest.java`

先写失败测试，再实现：

1. `content-v3` 缺少 `retirements.yml` 时加载失败。
2. 旧版本缺少该文件时得到空清单，保持兼容。
3. 加载后的退役列表保持声明顺序且不可变。
4. 在 `ContentPack` 中增加 `retirements`，保留测试和旧调用所需的兼容构造器。
5. 在 manifest 中增加 `expectedRetirements`，旧构造器默认值为 0。

验证：

```powershell
cd apps/server
mvn -q '-Dtest=ContentPackLoaderTest' test
```

### 2. 校验严格白名单并补齐内容包配置

涉及文件：

- `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackValidator.java`
- `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackValidatorTest.java`
- `content/seed/content-v3/manifest.yml`
- `content/seed/content-v3/retirements.yml`

先写失败测试，再实现以下规则：

1. 实际数量必须等于 `expectedRetirements`。
2. 只有 `complete` 阶段允许非空退役清单。
3. slug 不能为空，Unicode 码点长度不能超过数据库上限 128。
4. slug 按不区分大小写去重。
5. 退役清单不能与内容包自身文章 slug 重叠。
6. `retirements.yml` 只放入已审核的 34 个精确 slug：八组 `seed-*-1..4`，以及 `你好`、`诗音-测试`。

验证：

```powershell
cd apps/server
mvn -q '-Dtest=ContentPackLoaderTest,ContentPackValidatorTest' test
```

### 3. 在同一事务中执行文章退役

涉及文件：

- `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackMapper.java`
- `apps/server/src/main/resources/mapper/ContentPackMapper.xml`
- `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackDatabaseWriter.java`
- `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackDatabaseWriterTest.java`

先写失败测试，再实现：

1. Mapper 使用参数化 `IN` 精确查询所有候选项并加行锁，返回文章 ID、作者 ID、slug、状态和标签快照。
2. 数据写入器完成账号和文章写入后处理退役清单。
3. 候选文章作者是 Rekyrice 时抛出异常并回滚。
4. 候选文章 ID 属于本次内容包时抛出异常并回滚。
5. 仅把 `published` 更新为 `deleted`，更新行数必须是 1，然后释放标签计数。
6. 已是 `deleted` 的文章视为幂等命中，不再释放标签。
7. 其他状态拒绝处理；没有查到的 slug 进入 unmatched 报告。
8. `WriteResult` 增加退役文章 ID、受影响作者 ID和 unmatched slug，所有集合不可变，并保留兼容构造器。

验证：

```powershell
cd apps/server
mvn -q '-Dtest=ContentPackDatabaseWriterTest' test
```

### 4. 收口运行时状态和导入报告

涉及文件：

- `apps/server/src/main/java/com/chtholly/seed/contentpack/model/ContentPackImportReport.java`
- `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackImportService.java`
- `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackImportServiceTest.java`
- `apps/server/src/test/java/com/chtholly/seed/SeedRunnerTest.java`

先写失败测试，再实现：

1. 报告增加 `retiredPostIds` 与 `unmatchedRetirementSlugs`。
2. 导入成功后重建内容包作者和退役文章作者的用户计数。
3. 同时清除新增/更新文章与退役文章的缓存。
4. 内容包文章写入或更新搜索索引；退役文章调用软删除索引。
5. 第二次导入报告中退役文章集合为空，缺失项保持可观察。
6. 失败阶段和 dry-run 不得产生退役副作用。

验证：

```powershell
cd apps/server
mvn -q '-Dtest=ContentPackImportServiceTest,SeedRunnerTest' test
```

### 5. 建立 MySQL 备份与独立数据库参数

涉及文件：

- `scripts/backup/backup-mysql.ps1`
- `scripts/backup/test-backup-mysql.ps1`
- `scripts/backup/README.md`
- `scripts/dev/apply-migrations.ps1`
- `scripts/dev/run-seed.ps1`
- `scripts/README.md`

先写脚本契约测试，再实现：

1. 备份目标必须是绝对路径，盘符必须为 D，且不能位于仓库内部。
2. 数据库名只接受安全标识符，禁止把命令片段传给 Docker 或 MySQL。
3. 通过 Docker 容器中的 `mysqldump --single-transaction` 导出，复制到目标目录后压缩。
4. 生成 SHA-256 和 JSON 元数据；密码只从环境变量读取，不写入命令输出和文件。
5. `finally` 只清理已验证的容器临时文件和本次任务文件。
6. `apply-migrations.ps1` 与 `run-seed.ps1` 增加可选 `-Database`，加载 `.env` 后重新应用覆盖值。
7. 数据库覆盖值必须通过安全标识符校验，并传递给 Spring Boot 和迁移脚本。

验证：

```powershell
pwsh -NoProfile -File scripts/backup/test-backup-mysql.ps1
pwsh -NoProfile -File scripts/dev/test-run-seed.ps1
```

如果仓库没有现成的 `test-run-seed.ps1`，则把数据库参数契约测试并入备份脚本测试或新增对应测试文件，不为了命令名称创建无断言的占位测试。

### 6. 备份正式库并在隔离数据库演练

操作顺序：

1. 将当前正式 MySQL 备份到 `D:\1.hhh\backups\Chtholly-Hub\<timestamp>`。
2. 校验压缩文件可读、SHA-256 一致、元数据中的数据库名和时间正确。
3. 创建名称严格匹配 `chtholly_seed_rehearsal_yyyyMMddHHmmss` 的临时数据库。
4. 对临时数据库执行迁移、`phase_a_seed.sql`、旧 full seed、content-only seed，再导入内容包。
5. 第一次导入预期：40 篇内容包文章、8 个内容包账号、32 篇旧模板文章退役、2 个正式库特有 slug 报 unmatched、3 篇随源码创建的 Rekyrice 文章保留，最终 43 篇发布文章。
6. 第二次导入预期：内容包文章和账号数量不变，新增退役数为 0，Rekyrice 文章仍全部保留。
7. 比对数据库、缓存和搜索索引可观察结果。
8. 仅在名称通过上述正则校验后删除该临时数据库。
9. 再次确认正式数据库仍为演练前状态。

### 7. 正式库幂等导入与网站验收

在备份和隔离演练全部通过后：

1. 对正式数据库执行一次内容包导入。
2. 预期正式库最终为 40 篇内容包文章加 4 篇现存 Rekyrice 文章，共 44 篇发布文章。
3. 34 条退役白名单全部为 deleted；Rekyrice 的 4 篇文章状态不变。
4. 再执行一次导入，确认没有新增账号、文章或退役操作。
5. 启动本地网站，抽查首页、文章详情、作者头像、封面/正文图片、分类标签和搜索结果。
6. 停止任务启动的临时服务；保留经用户确认的备份，清除演练数据库和任务临时文件。

## 全量验证与提交边界

每个职责边界单独提交，建议顺序：

1. `docs: 规划内容包退役与备份实施`
2. `feat: 支持内容包精确退役旧文章`
3. `test: 验证内容包退役幂等性`
4. `feat: 增加 MySQL 内容备份脚本`
5. `chore: 验证内容包隔离导入`

提交前执行：

```powershell
cd apps/server
mvn test
cd ../web
npm run test:run
npm run build
cd ../..
git diff --check
git status --short
git diff --cached --name-only --diff-filter=A | git check-ignore -v --no-index --stdin
```

最后一条有任何输出都必须停止提交并取消对应文件暂存。Coding Agent 只提交到当前任务分支，不 push。
