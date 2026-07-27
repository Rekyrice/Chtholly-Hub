# Agent 证据、工具与文章陪读一致性实施计划

> 对应设计：`docs/design/2026-07-27-agent-evidence-consistency-design.md`

## 实施约束

- 基线为最新 `origin/main`；
- 不修改端口、模型参数、密钥、外部服务地址或运行配置；
- 每个行为变更先补失败测试，再实现最小修复；
- 搜索计数、文章证据、Bangumi、模型错误和前端会话按职责独立提交；
- 结束前执行后端全量测试、前端全量测试和生产构建。

## 任务 1：解除普通工具问答的错误引用门禁

**测试文件**

- `apps/server/src/test/java/com/chtholly/agent/context/contributor/KnowledgeContextContributorTest.java`
- `apps/server/src/test/java/com/chtholly/agent/ChthollyAgentTest.java`

**实现文件**

- `apps/server/src/main/java/com/chtholly/agent/context/contributor/KnowledgeContextContributor.java`
- 必要时调整 `ContextContribution` / `ContextEngine`，但不改变已有 Skill 的 `EvidencePolicy.REQUIRED` 语义。

**步骤**

1. 增加测试：普通“评分、集数、角色”问题可触发混合检索，但贡献不声明 `evidenceRequired`。
2. 增加测试：证据 Skill 仍要求引用且无证据时失败关闭。
3. 将启发式检索与强制引用拆开：只有 `EvidencePolicy.REQUIRED` 设置强制引用。
4. 运行 Agent 上下文和编排定向测试。

## 任务 2：当前文章正文限定证据

**测试文件**

- `apps/server/src/test/java/com/chtholly/agent/skill/SkillSelectorTest.java`
- `apps/server/src/test/java/com/chtholly/agent/context/contributor/KnowledgeContextContributorTest.java`
- `apps/server/src/test/java/com/chtholly/llm/rag/RagQueryServiceTest.java`

**实现文件**

- `apps/server/src/main/java/com/chtholly/agent/skill/SkillSelector.java`
- `apps/server/src/main/java/com/chtholly/agent/context/contributor/PageContextContributor.java`
- `apps/server/src/main/java/com/chtholly/agent/context/contributor/KnowledgeContextContributor.java`
- `apps/server/src/main/java/com/chtholly/llm/rag/RagQueryService.java`
- 可新增一个职责单一的当前文章检索适配器。

**步骤**

1. 增加选择规则测试：“只依据当前文章，总结三个观点并标出证据”选择 `page-explain`。
2. 增加 post ID/slug 解析和文章授权测试。
3. 为 `RagQueryService` 暴露只返回指定公开文章当前版本分块的查询方法。
4. 当前文章证据任务只使用指定 post 的分块构造 Evidence，不回退到全站语义结果。
5. 验证所有 Evidence 的 `sourceId` 均属于当前文章。

## 任务 3：缺失引用的一次有限修复

**测试文件**

- `apps/server/src/test/java/com/chtholly/agent/ChthollyAgentTest.java`

**实现文件**

- `apps/server/src/main/java/com/chtholly/agent/ChthollyAgent.java`
- 可新增 `CitationRepairService`，避免继续膨胀主编排器。

**步骤**

1. 增加测试：Evidence 非空且首次答案只缺引用时调用一次修复。
2. 增加测试：未知引用不触发修复。
3. 增加测试：修复失败后仍进入 `INVALID_CITATION` 边界。
4. 实现一次非流式、受限的引用修复，并复用 `EvidenceSet.validate` 复验。
5. 在 trace 中记录修复调用和最终引用状态。

## 任务 4：Bangumi 精确相关度与复合工具流程

**测试文件**

- `apps/server/src/test/java/com/chtholly/bangumi/mapper/BangumiSubjectMapperTest.java` 或 Mapper 集成测试
- `apps/server/src/test/java/com/chtholly/agent/runtime/AgentLoopExecutorTest.java`
- `apps/server/src/test/java/com/chtholly/agent/tools/BangumiSearchToolTest.java`

**实现文件**

- `apps/server/src/main/resources/mapper/BangumiSubjectMapper.xml`
- `apps/server/src/main/java/com/chtholly/agent/runtime/AgentLoopExecutor.java`
- `apps/server/src/main/java/com/chtholly/agent/tools/BangumiSearchTool.java`
- `apps/server/src/main/java/com/chtholly/agent/tools/BangumiCharactersTool.java`

**步骤**

1. 固定精确标题优先于高评分衍生条目的 SQL 排序契约。
2. 增加复合问题测试：搜索工具成功但问题仍要求角色时，Observation 明确要求继续角色工具。
3. 在运行时的工具 Observation 中加入未完成意图提示，不把角色查询硬编码进搜索服务。
4. 验证普通单一作品搜索仍可在一次工具调用后结束。

## 任务 5：模型临时错误重试与分类

**测试文件**

- `apps/server/src/test/java/com/chtholly/agent/runtime/AgentLoopExecutorTest.java`
- `apps/server/src/test/java/com/chtholly/agent/runtime/AgentLlmInvokerTest.java`
- `apps/server/src/test/java/com/chtholly/agent/ChthollyAgentTest.java`

**实现文件**

- `apps/server/src/main/java/com/chtholly/agent/runtime/AgentLoopExecutor.java`
- `apps/server/src/main/java/com/chtholly/agent/runtime/AgentLlmInvoker.java`
- `apps/server/src/main/java/com/chtholly/agent/observability/AgentExecutionTrace.java`
- `apps/server/src/main/java/com/chtholly/agent/config/AgentErrorMessages.java` 的默认文案来源代码；不修改部署配置。

**步骤**

1. 增加测试：超时、限流/临时上游错误各自分类，失败调用写入 trace。
2. 增加测试：可重试异常只重试一次；请求错误与中断不重试。
3. 在运行时增加固定一次重试，不新增参数。
4. 将临时错误提示改为“模型服务暂时不可用，请稍后再试”，配置缺失才使用配置提示。

## 任务 6：搜索互动计数使用权威值

**测试文件**

- `apps/server/src/test/java/com/chtholly/search/service/impl/SearchHitMapperTest.java`

**实现文件**

- `apps/server/src/main/java/com/chtholly/search/service/impl/SearchHitMapper.java`

**步骤**

1. 增加测试：ES 为 0、权威计数为点赞 4/收藏 2 时返回 4/2。
2. 通过 `CounterService.getCountsBatch` 一次批量读取点赞与收藏。
3. 计数服务异常时记录日志并回退到 ES 值，评论计数保持现有批量查询。

## 任务 7：文章问答排版与文章专属陪读会话

**测试文件**

- `apps/web/components/site/PostQnA.test.tsx`
- `apps/web/components/agent/AgentWorkspace.test.tsx`
- `apps/web/components/agent/hooks/useAgentSessions.test.ts`
- `apps/web/components/agent/hooks/useAgentWebSocket.test.ts`
- `apps/web/components/site/ArticleReadingSidebar.test.tsx`

**实现文件**

- `apps/web/components/site/PostQnA.tsx`
- `apps/web/app/styles/article.css`
- `apps/web/lib/agent/sessions.ts`
- `apps/web/components/agent/hooks/useAgentSessions.ts`
- `apps/web/components/agent/AgentChatProvider.tsx`
- `apps/web/components/agent/AgentWorkspace.tsx`
- `apps/web/components/agent/hooks/useAgentWebSocket.ts`
- `apps/web/app/(site)/post/[slug]/page.tsx`
- `apps/web/app/styles/agent.css`

**步骤**

1. 增加 Markdown 渲染和中文字体测试。
2. 为会话记录增加可选的 `contextKey`、`contextTitle`，兼容旧 localStorage 数据。
3. 增加“按上下文创建或复用会话”的 hook 能力。
4. 从文章链接传递文章标题和 post ID；Workspace 自动切换到对应文章会话并显示陪读横幅。
5. WebSocket 从活动会话读取文章上下文，避免用户切到普通会话后 URL 遗留上下文继续污染请求。
6. 验证普通会话、显式 `session` 参数和文章会话之间的优先级。

## 任务 8：验证与交付

1. 运行后端定向测试。
2. 运行 `mvn test`。
3. 运行前端定向测试与 `npm run test:run`。
4. 运行 `npm run build`。
5. 运行 `git diff --check`、范围检查和忽略文件审计。
6. 按独立职责提交；不 push，等待用户下一步指令。
