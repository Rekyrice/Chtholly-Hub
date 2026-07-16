# 社区公开资料一致性与互动冷启动实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 让文章、信息流、评论和公开主页始终展示同一份最新公开作者资料，并为八个种子账号补齐可信资料和可审阅、可重复导入的社区互动。

**Architecture:** MySQL `users` 表继续作为公开资料权威源，查询层以批量作者投影覆盖缓存和 Elasticsearch 中可能滞后的快照；资料更新事务内写入 Outbox，由既有 Kafka 重试、幂等和死信链路异步重建该作者的公开文章索引。内容包扩展外部文章引用，并将互动审阅与正式导入分成两个明确阶段。

**Tech Stack:** Java 21、Spring Boot 3.2.4、MyBatis、MySQL、Redis、Kafka、Elasticsearch、Next.js 16、React、TypeScript、Vitest、Playwright、JUnit 5、Testcontainers、YAML。

---

## Task 1：建立公开作者投影

**Files:**

- Create: `apps/server/src/main/java/com/chtholly/user/model/PublicAuthorSnapshot.java`
- Create: `apps/server/src/main/java/com/chtholly/user/service/PublicAuthorQueryService.java`
- Modify: `apps/server/src/main/java/com/chtholly/user/mapper/UserMapper.java`
- Modify: `apps/server/src/main/resources/mapper/UserMapper.xml`
- Test: `apps/server/src/test/java/com/chtholly/user/service/PublicAuthorQueryServiceTest.java`

- [ ] 先写单个、批量、缺失用户与空集合测试，要求批量路径只调用一次 Mapper。
- [ ] 为 Mapper 增加按 ID 集合读取 `id/handle/nickname/avatar/bio/tags_json/created_at` 的查询。
- [ ] 实现不可变的 `PublicAuthorSnapshot` 与单个/批量查询服务，统一缺失用户降级规则。
- [ ] 运行 `mvn -q '-Dtest=PublicAuthorQueryServiceTest' test`，预期全部通过。
- [ ] 提交：`feat: 建立公开作者资料统一投影`

## Task 2：统一公开用户 API

**Files:**

- Modify: `apps/server/src/main/java/com/chtholly/user/api/dto/PublicUserResponse.java`
- Modify: `apps/server/src/main/java/com/chtholly/user/service/impl/PublicUserServiceImpl.java`
- Test: `apps/server/src/test/java/com/chtholly/user/service/impl/PublicUserServiceImplTest.java`

- [ ] 先写公开响应包含标签、简介、加入时间和社区计数的契约测试。
- [ ] 将 `tags_json` 安全解析为字符串列表，格式异常时返回空列表而不是 500。
- [ ] 明确排除性别、生日、学校、电话和邮箱等私有字段。
- [ ] 运行 `mvn -q '-Dtest=PublicUserServiceImplTest' test`。
- [ ] 提交：`feat: 完善公开用户资料响应`

## Task 3：修复文章详情作者资料

**Files:**

- Modify: `apps/server/src/main/java/com/chtholly/post/api/dto/PostDetailResponse.java`
- Modify: `apps/server/src/main/java/com/chtholly/post/service/impl/PostDetailQueryService.java`
- Modify: `apps/server/src/main/java/com/chtholly/post/cache/PostDetailCacheService.java`
- Test: `apps/server/src/test/java/com/chtholly/post/service/impl/PostDetailQueryServiceTest.java`

- [ ] 写失败测试，覆盖缓存命中时仍用最新 MySQL 作者简介、昵称、头像和 handle 覆盖旧快照。
- [ ] 在详情响应增加 `authorHandle`、`authorBio`，并在返回前应用权威作者投影。
- [ ] 提升详情缓存布局版本，确保旧 JSON 不影响部署后的反序列化。
- [ ] 作者查询异常时保留缓存快照；两侧都缺失时使用统一的已注销用户展示。
- [ ] 运行文章详情定向测试。
- [ ] 提交：`fix: 统一文章详情作者资料`

## Task 4：修复信息流作者资料与 N+1

**Files:**

- Modify: `apps/server/src/main/java/com/chtholly/post/api/dto/FeedItemResponse.java`
- Modify: `apps/server/src/main/java/com/chtholly/post/model/PostFeedRow.java`
- Modify: `apps/server/src/main/java/com/chtholly/post/service/impl/PostFeedServiceImpl.java`
- Modify: `apps/server/src/main/java/com/chtholly/search/service/impl/SearchHitMapper.java`
- Modify: `apps/server/src/main/java/com/chtholly/search/service/impl/HubFeedSearchService.java`
- Test: `apps/server/src/test/java/com/chtholly/post/service/impl/PostFeedServiceImplEnrichTest.java`
- Test: `apps/server/src/test/java/com/chtholly/search/service/impl/SearchHitMapperTest.java`

- [ ] 写 DB、Redis 和 Elasticsearch 三条路径的失败测试，断言 `authorId`、`authorHandle` 均完整。
- [ ] 信息流先收集作者 ID，再用一次 `IN` 查询覆盖所有作者快照。
- [ ] Elasticsearch 命中映射保留 `author_id`，禁止把空作者字段写进新的缓存。
- [ ] 测试多个帖子属于同一/不同作者时均无逐条作者查询。
- [ ] 运行信息流与搜索映射定向测试。
- [ ] 提交：`fix: 统一信息流作者资料`

## Task 5：统一评论作者契约

**Files:**

- Modify: `apps/server/src/main/java/com/chtholly/comment/api/dto/CommentResponse.java`
- Modify: `apps/server/src/main/java/com/chtholly/comment/service/impl/CommentServiceImpl.java`
- Test: `apps/server/src/test/java/com/chtholly/comment/service/impl/CommentServiceImplTest.java`

- [ ] 写评论响应包含 `authorHandle` 和头像的测试。
- [ ] 将 Mapper 已读取的 handle 透传到响应；列表结果批量应用公开作者投影。
- [ ] 保留删除评论的匿名化语义，不泄露被删除内容或私有资料。
- [ ] 运行评论定向测试。
- [ ] 提交：`fix: 统一评论作者资料`

## Task 6：发布资料变更 Outbox 事件

**Files:**

- Create: `apps/server/src/main/java/com/chtholly/profile/event/AuthorProfileChangedPayload.java`
- Modify: `apps/server/src/main/java/com/chtholly/profile/service/impl/ProfileServiceImpl.java`
- Modify: `apps/server/src/main/java/com/chtholly/relation/outbox/OutboxMapper.java`
- Test: `apps/server/src/test/java/com/chtholly/profile/service/impl/ProfileServiceImplTest.java`

- [ ] 写测试断言昵称、头像、简介、handle 或标签变化时在同一事务写入一条 `AUTHOR_PROFILE_CHANGED` 事件。
- [ ] 私有字段单独变化时不触发文章索引重建；无实际变化时不写事件。
- [ ] 使用项目 ID 生成器和 `ObjectMapper` 生成稳定 JSON，不手工拼接 JSON。
- [ ] 验证资料更新失败会整体回滚，不能出现孤立事件。
- [ ] 运行资料服务定向测试。
- [ ] 提交：`feat: 发布作者资料变更事件`

## Task 7：消费资料事件并重建搜索文档

**Files:**

- Modify: `apps/server/src/main/java/com/chtholly/search/outbox/CanalOutboxConsumerSearch.java`
- Modify: `apps/server/src/main/java/com/chtholly/search/index/SearchIndexService.java`
- Modify: `apps/server/src/main/java/com/chtholly/search/index/SearchIndexInitializer.java`
- Modify: `apps/server/src/main/resources/mapper/PostMapper.xml`
- Test: `apps/server/src/test/java/com/chtholly/search/outbox/CanalOutboxConsumerSearchTest.java`
- Test: `apps/server/src/test/java/com/chtholly/search/index/SearchIndexServiceTest.java`

- [ ] 写作者事件幂等消费、失败重试和只重建已发布公开文章的测试。
- [ ] 为索引文档增加 `author_handle`，并提供按作者分页重建文章索引的方法。
- [ ] 消费 `AUTHOR_PROFILE_CHANGED` 后按作者 ID 重建；成功后才记录幂等键。
- [ ] 旧索引无新字段时允许增量映射升级；无法兼容时给出明确启动错误。
- [ ] 运行搜索索引与消费者定向测试。
- [ ] 提交：`feat: 同步作者资料到搜索索引`

## Task 8：更新文章、评论和公开主页界面

**Files:**

- Modify: `apps/web/lib/types/post.ts`
- Modify: `apps/web/lib/types/comment.ts`
- Modify: `apps/web/lib/types/profile.ts`
- Modify: `apps/web/app/(site)/post/[slug]/page.tsx`
- Modify: `apps/web/components/site/AuthorCard.tsx`
- Modify: `apps/web/components/site/ArticleReadingSidebar.tsx`
- Modify: `apps/web/components/site/PostCard.tsx`
- Modify: `apps/web/components/site/CommentSection.tsx`
- Modify: `apps/web/app/(site)/user/[handle]/page.tsx`
- Test: `apps/web/components/site/AuthorCard.test.tsx`
- Test: `apps/web/components/site/ArticleReadingSidebar.test.tsx`
- Test: `apps/web/components/site/PostCard.test.tsx`
- Test: `apps/web/components/site/CommentSection.test.tsx`

- [ ] 先写 Rekyrice 简介、作者 canonical 链接、评论头像链接与公开标签展示测试。
- [ ] 所有作者链接统一使用 `/user/{handle}`；handle 缺失时降级为不可点击文本。
- [ ] 公开主页展示简介、标签、加入时间、文章数与社交计数，不展示私有资料。
- [ ] 运行 `npm run test:run -- AuthorCard ArticleReadingSidebar PostCard CommentSection`。
- [ ] 提交：`fix: 完善社区作者资料展示`

## Task 9：扩展内容包账号与外部文章引用模型

**Files:**

- Modify: `apps/server/src/main/java/com/chtholly/seed/contentpack/model/SeedAccountDefinition.java`
- Modify: `apps/server/src/main/java/com/chtholly/seed/contentpack/model/SeedCommentDefinition.java`
- Modify: `apps/server/src/main/java/com/chtholly/seed/contentpack/model/SeedReactionDefinition.java`
- Modify: `apps/server/src/main/java/com/chtholly/seed/contentpack/model/SeedViewDefinition.java`
- Modify: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackValidator.java`
- Modify: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackIdentityResolver.java`
- Modify: `apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackDatabaseWriter.java`
- Test: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackValidatorTest.java`
- Test: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackIdentityResolverTest.java`
- Test: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackDatabaseWriterTest.java`

- [ ] 写 `postSeedKey` 与 `postSlug` 必须且只能存在一个的失败测试。
- [ ] `postSlug` 只能解析到站点所有者的公开已发布文章，解析失败必须在事务前停止。
- [ ] 账号模型支持确定性的 `joinedAt`，重复导入不得漂移。
- [ ] 运行内容包模型、校验、解析和写入测试。
- [ ] 提交：`feat: 扩展内容包互动目标解析`

## Task 10：补齐八个账号资料与互动清单

**Files:**

- Modify: `content/seed/content-v3/accounts.yml`
- Modify: `content/seed/content-v3/interactions.yml`
- Test: `apps/server/src/test/java/com/chtholly/seed/contentpack/ContentPackV3ContractTest.java`

- [ ] 为八个账号分别编写非空简介、2–4 个自然标签与 2026 年 2–6 月间稳定加入时间。
- [ ] 编写 36 条评论（28 条根评论、8 条回复），确保回复具体回应父评论，正文主要为 18–90 个中文字符。
- [ ] 编写 88 条反应（62 点赞、26 收藏）、14 条定向关注与 44 条浏览下限声明。
- [ ] 约束互动 actor 仅为八个种子账号，禁止生成 Rekyrice 或 Chtholly 的动作。
- [ ] 写清单级契约测试，精确校验数量、唯一 seedKey、引用与作者限制。
- [ ] 运行内容包 V3 契约测试和完整校验器测试。
- [ ] 提交：`feat: 补齐社区资料与互动内容包`

## Task 11：生成互动审阅页

**Files:**

- Create: `scripts/seed-content/render-community-interaction-review.mjs`
- Create: `scripts/seed-content/render-community-interaction-review.test.mjs`
- Modify: `scripts/README.md`
- Local output only: `.codex-tmp/community-interactions/review/index.html`

- [ ] 写测试断言 36 条评论逐条展示作者、目标文章、父评论、正文与时间。
- [ ] 点赞、收藏、关注和浏览用可追溯统计表展示，并标明总数与分布。
- [ ] 生成器只读取版本化 YAML，输出目录参数必须位于已忽略的项目目录。
- [ ] 运行 Node 测试并生成本地审阅页；确认 `git status` 不包含审阅 HTML。
- [ ] 提交：`feat: 增加社区互动审阅页生成器`

## Task 12：端到端验证与安全导入准备

**Files:**

- Modify: `apps/web/e2e/post-author-profile.spec.ts`
- Modify: `apps/web/e2e/public-user-profile.spec.ts`
- Modify: `docs/development/database.md`

- [ ] 用 Testcontainers 验证资料读取、缓存覆盖、外部文章解析、互动幂等和事务回滚。
- [ ] 用 Playwright 验证 Rekyrice 文章作者卡显示真实简介、评论头像/链接、公开主页字段。
- [ ] 运行 `mvn test`、`npm run test:run`、`npm run build` 和相关 Playwright 用例。
- [ ] 运行 `git diff --check` 与 `git status --short`，清理不再需要的任务临时文件和服务。
- [ ] 暂存前检查范围；提交前执行新增忽略文件审计，任何输出都停止提交。
- [ ] 提交：`test: 验证社区资料一致性与互动导入`

## Task 13：经用户审阅后导入

- [ ] 将 `.codex-tmp/community-interactions/review/index.html` 交给用户逐条审阅；未通过不得写正式数据库。
- [ ] 审阅通过后先运行既有备份脚本，并确认备份可读取。
- [ ] 执行内容包 `dry-run`，要求计划新增/更新数量与清单一致且无校验错误。
- [ ] 正式导入一次，再重复执行一次验证幂等；第二次不得产生重复评论、反应、关注或异常计数。
- [ ] 核对 MySQL 权威数据、Redis 计数、Elasticsearch 作者快照和浏览器页面。

## 全程约束

- 不操作其他 worktree、分支或未归属本任务的改动。
- 不提交 `.codex-tmp/`、备份、导入快照、审阅页或任何被忽略文件，不使用 `git add -f`。
- 每次提交前执行：

  ```powershell
  git diff --cached --name-only --diff-filter=A | git check-ignore -v --no-index --stdin
  ```

- Coding Agent 只在本地任务分支提交，不 push；发布动作等待项目维护者明确指示。
