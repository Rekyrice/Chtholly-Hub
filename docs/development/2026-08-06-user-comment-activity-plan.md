# 用户主页评论活动实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为用户主页提供安全、可分页的公开评论活动，并同时替换概览“最近的回应”和“评论”标签中的占位内容。

**Architecture:** 评论模块新增独立的用户活动查询入口，MyBatis 在一次联表分页中返回评论与文章路由信息；公开性过滤由 MySQL 查询统一执行。用户主页 Server Component 获取首屏，`UserTabs` 复用首屏数据并在客户端按页追加，避免 N+1 请求和非公开内容泄露。

**Tech Stack:** Java 21、Spring Boot 3.2.4、MyBatis、MySQL 8、JUnit 5、Mockito、Next.js 16、React 19、TypeScript、Vitest、Testing Library。

---

## 文件结构

### 后端

- Create: `apps/server/db/migration/V30__comment_user_activity_index.sql` — 用户评论活动复合索引。
- Modify: `apps/server/db/schema.sql` — 完整 schema 同步索引。
- Create: `apps/server/src/main/java/com/chtholly/comment/model/UserCommentActivityRow.java` — Mapper 联表查询行。
- Create: `apps/server/src/main/java/com/chtholly/comment/api/dto/UserCommentActivityResponse.java` — 公开 API DTO。
- Create: `apps/server/src/main/java/com/chtholly/comment/api/UserCommentController.java` — 用户评论活动公共入口。
- Modify: `apps/server/src/main/java/com/chtholly/comment/mapper/CommentMapper.java` — 列表与计数方法。
- Modify: `apps/server/src/main/resources/mapper/CommentMapper.xml` — 联表过滤、排序和分页 SQL。
- Modify: `apps/server/src/main/java/com/chtholly/comment/service/CommentService.java` — `listByUser` 用例契约。
- Modify: `apps/server/src/main/java/com/chtholly/comment/service/impl/CommentServiceImpl.java` — 分页映射。
- Modify: `apps/server/src/main/java/com/chtholly/auth/config/SecurityConfig.java` — 精确放行匿名 GET。

### 前端

- Modify: `apps/web/lib/types/comment.ts` — 用户评论活动与分页类型。
- Modify: `apps/web/lib/services/commentService.ts` — 用户活动分页请求。
- Modify: `apps/web/app/(site)/user/[handle]/page.tsx` — 服务端首屏加载与降级标记。
- Create: `apps/web/components/site/UserCommentActivityList.tsx` — 评论活动卡片、空态和错误态。
- Modify: `apps/web/components/site/UserTabs.tsx` — 最近 2 条、评论分页、重试和去重。
- Modify: `apps/web/app/styles/community.css` — 活动卡片与加载按钮样式。

### 测试与文档

- Modify: `apps/server/src/test/java/com/chtholly/comment/mapper/CommentMapperContractTest.java`
- Modify: `apps/server/src/test/java/com/chtholly/comment/service/impl/CommentServiceImplTest.java`
- Create: `apps/server/src/test/java/com/chtholly/comment/api/UserCommentControllerSecurityTest.java`
- Create: `apps/web/lib/services/commentService.test.ts`
- Create: `apps/web/app/(site)/user/[handle]/page.test.tsx`
- Create: `apps/web/components/site/UserTabs.test.tsx`
- Create: `apps/web/components/site/UserCommentActivityList.test.tsx`
- Modify: `docs/architecture/request-flows.md`
- Modify: `docs/architecture/frontend.md`

---

### Task 1: 建立用户评论活动持久查询

**Files:**
- Create: `apps/server/db/migration/V30__comment_user_activity_index.sql`
- Modify: `apps/server/db/schema.sql`
- Create: `apps/server/src/main/java/com/chtholly/comment/model/UserCommentActivityRow.java`
- Modify: `apps/server/src/main/java/com/chtholly/comment/mapper/CommentMapper.java`
- Modify: `apps/server/src/main/resources/mapper/CommentMapper.xml`
- Test: `apps/server/src/test/java/com/chtholly/comment/mapper/CommentMapperContractTest.java`

- [ ] **Step 1: 写入失败的 Mapper 与索引契约测试**

在 `CommentMapperContractTest` 增加两个测试。第一个截取 `listPublicActivityByUserId` 和 `countPublicActivityByUserId` SQL，断言列表与计数都包含：

```java
assertThat(listStatement)
        .contains("JOIN posts p ON p.id = c.post_id")
        .contains("c.user_id = #{userId}")
        .contains("c.deleted_at IS NULL")
        .contains("p.status = 'published'")
        .contains("p.visible = 'public'")
        .contains("ORDER BY c.created_at DESC, c.id DESC")
        .contains("LIMIT #{limit} OFFSET #{offset}");
assertThat(countStatement)
        .contains("c.user_id = #{userId}")
        .contains("c.deleted_at IS NULL")
        .contains("p.status = 'published'")
        .contains("p.visible = 'public'");
```

第二个测试读取迁移和 `schema.sql`，断言二者都有：

```java
"KEY ix_comments_user_deleted_ct (user_id, deleted_at, created_at, id)"
```

- [ ] **Step 2: 验证测试因能力缺失而失败**

Run:

```powershell
cd apps/server
mvn -q '-Dtest=CommentMapperContractTest' test
```

Expected: FAIL，原因是 Mapper XML 尚无用户活动语句，V30 迁移尚不存在。

- [ ] **Step 3: 添加索引、查询行与 Mapper SQL**

迁移使用：

```sql
ALTER TABLE comments
    ADD KEY ix_comments_user_deleted_ct
        (user_id, deleted_at, created_at, id);
```

`UserCommentActivityRow` 使用 Lombok `@Data`，字段为 `Long id`、`Long postId`、`String postSlug`、`String postTitle`、`Long parentId`、`String content`、`Instant createdAt`。

Mapper 方法签名为：

```java
List<UserCommentActivityRow> listPublicActivityByUserId(
        @Param("userId") long userId,
        @Param("limit") int limit,
        @Param("offset") int offset);

long countPublicActivityByUserId(@Param("userId") long userId);
```

列表 SQL 使用同一联表过滤，并输出 `postSlug`、`postTitle`；计数 SQL 使用完全相同的 `JOIN` 与 `WHERE`。同步 `schema.sql` 的 comments 索引段。

- [ ] **Step 4: 验证 Mapper 与索引契约转绿**

Run:

```powershell
mvn -q '-Dtest=CommentMapperContractTest' test
```

Expected: PASS。

- [ ] **Step 5: 提交持久查询**

提交前对暂存新增文件执行 ignore 审计，然后提交：

```powershell
git commit -m "feat: 增加用户评论活动持久查询"
```

---

### Task 2: 提供分页用例和匿名读取 API

**Files:**
- Create: `apps/server/src/main/java/com/chtholly/comment/api/dto/UserCommentActivityResponse.java`
- Create: `apps/server/src/main/java/com/chtholly/comment/api/UserCommentController.java`
- Modify: `apps/server/src/main/java/com/chtholly/comment/service/CommentService.java`
- Modify: `apps/server/src/main/java/com/chtholly/comment/service/impl/CommentServiceImpl.java`
- Modify: `apps/server/src/main/java/com/chtholly/auth/config/SecurityConfig.java`
- Test: `apps/server/src/test/java/com/chtholly/comment/service/impl/CommentServiceImplTest.java`
- Create: `apps/server/src/test/java/com/chtholly/comment/api/UserCommentControllerSecurityTest.java`

- [ ] **Step 1: 写入失败的 Service 映射测试**

新增测试，Mock Mapper 返回顶级评论和回复：

```java
when(commentMapper.countPublicActivityByUserId(9L)).thenReturn(21L);
when(commentMapper.listPublicActivityByUserId(9L, 20, 20L))
        .thenReturn(List.of(activity(100L, null), activity(101L, 88L)));

PageResponse<UserCommentActivityResponse> page = service.listByUser(9L, 2, 20);

assertThat(page.page()).isEqualTo(2);
assertThat(page.total()).isEqualTo(21L);
assertThat(page.hasMore()).isFalse();
assertThat(page.items()).extracting(UserCommentActivityResponse::id)
        .containsExactly("100", "101");
assertThat(page.items().get(1).parentId()).isEqualTo("88");
```

另加空页测试，断言 Mapper 列表为空时仍返回正确 `total/page/size/hasMore`。

- [ ] **Step 2: 验证 Service 测试失败**

Run:

```powershell
mvn -q '-Dtest=CommentServiceImplTest' test
```

Expected: FAIL，原因是 `listByUser` 与 DTO 尚不存在。

- [ ] **Step 3: 实现最小分页用例**

DTO 使用字符串 ID：

```java
public record UserCommentActivityResponse(
        String id,
        String postId,
        String postSlug,
        String postTitle,
        String parentId,
        String content,
        Instant createdAt) {
}
```

Service 使用 `PageRequest.of(page, size)`，先计数，再按 `size/offset` 查询，最后通过 `PageResponse.offset(...)` 返回。`parentId` 为空时保持 `null`。

- [ ] **Step 4: 验证 Service 测试转绿**

Run:

```powershell
mvn -q '-Dtest=CommentServiceImplTest' test
```

Expected: PASS。

- [ ] **Step 5: 写入失败的 Controller 与安全测试**

`UserCommentControllerSecurityTest` 使用 `@WebMvcTest(UserCommentController.class)` 和 `@Import(SecurityConfig.class)`：

```java
when(commentService.listByUser(9L, 1, 20))
        .thenReturn(PageResponse.offset(List.of(), 1, 20, 0L));

mockMvc.perform(get("/api/v1/users/9/comments"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray());

mockMvc.perform(get("/api/v1/users/9/comments")
                .param("page", "0"))
        .andExpect(status().isBadRequest());

mockMvc.perform(post("/api/v1/users/9/comments"))
        .andExpect(status().isUnauthorized());
```

- [ ] **Step 6: 验证匿名 GET 仍被拒绝**

Run:

```powershell
mvn -q '-Dtest=UserCommentControllerSecurityTest' test
```

Expected: FAIL；GET 在实现路由前为 404，加入 Controller 但未加安全规则时为 401。

- [ ] **Step 7: 实现 Controller 与精确安全规则**

Controller 使用：

```java
@RestController
@RequestMapping("/api/v1/users/{userId}/comments")
@Validated
@RequiredArgsConstructor
public class UserCommentController {
    private final CommentService commentService;

    @GetMapping
    public PageResponse<UserCommentActivityResponse> list(
            @PathVariable long userId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return commentService.listByUser(userId, page, size);
    }
}
```

`SecurityConfig` 只增加：

```java
.requestMatchers(HttpMethod.GET, "/api/v1/users/*/comments").permitAll()
```

- [ ] **Step 8: 验证后端用例与安全契约转绿**

Run:

```powershell
mvn -q '-Dtest=CommentServiceImplTest,CommentMapperContractTest,UserCommentControllerSecurityTest' test
```

Expected: PASS。

- [ ] **Step 9: 提交后端应用层**

```powershell
git commit -m "feat: 开放用户评论活动接口"
```

---

### Task 3: 接入前端传输和服务端首屏

**Files:**
- Modify: `apps/web/lib/types/comment.ts`
- Modify: `apps/web/lib/services/commentService.ts`
- Create: `apps/web/lib/services/commentService.test.ts`
- Modify: `apps/web/app/(site)/user/[handle]/page.tsx`
- Create: `apps/web/app/(site)/user/[handle]/page.test.tsx`

- [ ] **Step 1: 写入失败的 commentService 请求测试**

Mock `apiFetch` 后执行：

```ts
await commentService.listByUser("9007199254740993", 2, 20);

expect(mocks.apiFetch).toHaveBeenCalledWith(
  "/api/v1/users/9007199254740993/comments?page=2&size=20",
);
```

- [ ] **Step 2: 验证服务方法缺失**

Run:

```powershell
npx vitest run lib/services/commentService.test.ts
```

Expected: FAIL，原因是 `listByUser` 不存在。

- [ ] **Step 3: 添加活动类型和请求方法**

在 `comment.ts` 增加：

```ts
export type UserCommentActivityItem = {
  id: string;
  postId: string;
  postSlug: string;
  postTitle: string;
  parentId: string | null;
  content: string;
  createdAt: string;
};

export type UserCommentActivityPage = {
  items: UserCommentActivityItem[];
  total: number;
  page: number;
  size: number;
  hasMore: boolean;
};
```

`commentService.listByUser` 对 `userId` 使用 `encodeURIComponent`，显式携带页码和页大小。

- [ ] **Step 4: 验证 transport 测试转绿**

Run: `npx vitest run lib/services/commentService.test.ts`

Expected: PASS。

- [ ] **Step 5: 写入失败的用户主页首屏与降级测试**

Mock `userService`、`postService`、`relationService`、`commentService` 和子组件。成功用例断言 `UserTabs` 收到 `initialComments` 及 `commentsInitialLoadFailed=false`；失败用例让 `commentService.listByUser` reject，断言页面仍返回且传入空分页和 `commentsInitialLoadFailed=true`。

- [ ] **Step 6: 验证页面测试失败**

Run:

```powershell
npx vitest run 'app/(site)/user/[handle]/page.test.tsx'
```

Expected: FAIL，原因是页面尚未调用 `commentService`，`UserTabs` 也无对应属性。

- [ ] **Step 7: 实现服务端首屏加载**

`UserPage` 在用户解析成功后请求：

```ts
const emptyComments: UserCommentActivityPage = {
  items: [], total: 0, page: 1, size: 20, hasMore: false,
};
let initialComments = emptyComments;
let commentsInitialLoadFailed = false;
try {
  initialComments = await commentService.listByUser(String(user.id), 1, 20);
} catch {
  commentsInitialLoadFailed = true;
}
```

将两项属性传给 `UserTabs`。

- [ ] **Step 8: 验证页面与服务测试转绿**

Run:

```powershell
npx vitest run lib/services/commentService.test.ts 'app/(site)/user/[handle]/page.test.tsx'
```

Expected: PASS。

- [ ] **Step 9: 提交前端数据链路**

```powershell
git commit -m "feat: 接入用户评论活动首屏数据"
```

---

### Task 4: 替换评论占位并实现分页交互

**Files:**
- Create: `apps/web/components/site/UserCommentActivityList.tsx`
- Create: `apps/web/components/site/UserCommentActivityList.test.tsx`
- Modify: `apps/web/components/site/UserTabs.tsx`
- Create: `apps/web/components/site/UserTabs.test.tsx`
- Modify: `apps/web/app/styles/community.css`

- [ ] **Step 1: 写入失败的活动列表展示测试**

测试两条活动：一条 `parentId=null`，一条带父 ID。断言页面分别出现“评论了《标题》”“回复了《标题》”，正文和 `/post/{slug}` 链接存在；空数组显示“还没有留下公开回应”，错误属性显示“回应暂时没有加载出来”。

- [ ] **Step 2: 验证活动列表组件缺失**

Run: `npx vitest run components/site/UserCommentActivityList.test.tsx`

Expected: FAIL，原因是组件不存在。

- [ ] **Step 3: 实现纯展示组件**

组件接口固定为：

```ts
type Props = {
  items: UserCommentActivityItem[];
  error?: string | null;
  emptyTitle?: string;
  onRetry?: () => void;
};
```

使用 `Link` 指向 `/post/${encodeURIComponent(item.postSlug)}`，通过 `parentId` 选择文案，时间格式化函数对无效时间回退“时间未知”。

- [ ] **Step 4: 验证展示组件转绿**

Run: `npx vitest run components/site/UserCommentActivityList.test.tsx`

Expected: PASS。

- [ ] **Step 5: 写入失败的 UserTabs 行为测试**

准备 3 条初始活动，断言概览只显示前 2 条；点击“评论”标签后显示 3 条。再让 `commentService.listByUser` 返回下一页（含一个重复 ID和一个新 ID），点击“加载更多”，断言新活动追加且重复项只出现一次。

另写两项独立测试：

- `commentsInitialLoadFailed=true` 时显示重试，重试成功后替换错误态。
- 加载更多失败时保留已有活动，并允许再次点击重试。

- [ ] **Step 6: 验证占位实现无法满足测试**

Run: `npx vitest run components/site/UserTabs.test.tsx`

Expected: FAIL，原因是当前仍渲染 `ComingSoonCard`，无分页状态。

- [ ] **Step 7: 实现 UserTabs 评论状态与分页**

新增属性：

```ts
initialComments: UserCommentActivityPage;
commentsInitialLoadFailed?: boolean;
```

状态保存 `items/page/hasMore/loading/error`。请求下一页时使用 `initialComments.size`，成功后按 `id` 去重：

```ts
setComments((current) => {
  const seen = new Set(current.map((item) => item.id));
  return [...current, ...next.items.filter((item) => !seen.has(item.id))];
});
```

概览传 `comments.slice(0, 2)`；评论标签传完整已加载列表。首屏失败重试请求第 1 页并替换列表；加载更多失败不清空已有列表。

- [ ] **Step 8: 添加活动视觉样式并验证交互测试**

在 `community.css` 增加 `member-comment-list`、`member-comment-card`、`member-comment-card__meta`、`member-comment-card__content` 和加载按钮样式，复用现有主题变量并保留键盘焦点。

Run:

```powershell
npx vitest run components/site/UserCommentActivityList.test.tsx components/site/UserTabs.test.tsx
```

Expected: PASS。

- [ ] **Step 9: 提交评论展示**

```powershell
git commit -m "feat: 展示用户公开评论活动"
```

---

### Task 5: 同步架构文档并完成全量回归

**Files:**
- Modify: `docs/architecture/request-flows.md`
- Modify: `docs/architecture/frontend.md`

- [ ] **Step 1: 更新请求链路与前端数据流**

在请求链路文档记录：用户评论活动由 `UserCommentController -> CommentService.listByUser -> CommentMapper` 读取，MySQL 是权威数据，只返回公开已发布文章下的未删除评论。在前端架构文档记录用户页首屏由 Server Component 获取，`UserTabs` 负责客户端续页与失败重试。

- [ ] **Step 2: 运行后端定向测试**

```powershell
cd apps/server
mvn -q '-Dtest=CommentMapperContractTest,CommentServiceImplTest,UserCommentControllerSecurityTest' test
```

Expected: 全部 PASS。

- [ ] **Step 3: 运行后端全量单元测试**

```powershell
mvn test
```

Expected: BUILD SUCCESS，0 failures，0 errors。

- [ ] **Step 4: 运行前端全量测试与生产构建**

```powershell
cd ../web
npm run test:run
npm run build
```

Expected: 测试全部通过，Next.js production build 成功。

- [ ] **Step 5: 执行提交与范围审计**

```powershell
cd ../..
git diff --check
git status --short
git diff --name-only --diff-filter=A origin/main...HEAD | git check-ignore -v --no-index --stdin
```

Expected: `git diff --check` 无输出；ignore 审计无输出；状态只包含本任务文档变更。

- [ ] **Step 6: 提交文档**

```powershell
git commit -m "docs: 记录用户评论活动链路"
```

---

## 最终验收清单

- [ ] 概览只显示最新 2 条公开回应。
- [ ] 评论标签分页显示评论与回复，排序稳定且重试不重复。
- [ ] 活动能够跳转对应文章。
- [ ] 未发布、非公开或软删除内容不出现在查询中。
- [ ] 初次失败和续页失败均有可重试状态。
- [ ] Snowflake ID 在 API 和前端始终使用字符串。
- [ ] 后端全量测试、前端全量测试和生产构建全部通过。
