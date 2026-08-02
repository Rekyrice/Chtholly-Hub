# Agent 单轮可靠性与工具规划实施计划

> 本计划在 `codex/agent-turn-reliability` 独立 worktree 中执行，遵循测试驱动开发：每项行为先得到正确原因的失败测试，再写最小实现。

**目标：** 为 Agent 单轮请求建立稳定身份、跨实例单飞、断线取消、整轮 deadline、快速安全输出与最小工具计划；把可复盘的完整运行链写入 ADMIN Trace，并增加无需新配置的受控公网调研工具。

**架构：** WebSocket handler 负责协议、所有权与连接生命周期；`AgentTurnCoordinator` 用 Redis 保证跨实例互斥/去重；`AgentTurnBudget` 贯穿 Agent、循环、LLM、工具和检索；`AgentToolPlanner` 在 Skill/Evidence 规划后决定最终工具集合；ADMIN Trace 用类型化投影记录完整输入输出和外部条件；公网研究以 `web_search → web_fetch → Evidence` 为受控只读链路。

**技术栈：** Java 21、Spring Boot 3.2.4、Spring Data Redis、Reactor、JUnit 5/Mockito、Next.js 16、React、TypeScript、Vitest/Testing Library。

---

## 任务 1：固定 WebSocket 请求/轮次协议

**文件：**

- 修改：`apps/web/lib/types/agent.ts`
- 修改：`apps/web/components/agent/hooks/useAgentWebSocket.ts`
- 修改：`apps/web/components/agent/hooks/useAgentWebSocket.test.ts`
- 修改：`apps/server/src/main/java/com/chtholly/agent/ws/AgentWebSocketHandler.java`
- 修改：`apps/server/src/test/java/com/chtholly/agent/ws/AgentWebSocketHandlerTest.java`

**步骤：**

1. 在前端测试中断言每次 chat payload 都有新的 `requestId`，`accepted` 后只接受匹配 request/turn 的事件，旧 turn 事件被忽略。
2. 运行对应 Vitest，确认测试因协议字段缺失而失败。
3. 扩展 `AgentWsEnvelope` 和事件类型，生成 requestId，维护当前 request/turn 引用。
4. 在后端测试中断言 `requestId` 必填、服务端先发送 `accepted`，随后事件 envelope 都携带同一 `requestId/turnId`。
5. 运行 `AgentWebSocketHandlerTest`，确认测试因旧 envelope 失败。
6. 实现服务端 envelope 扩展和稳定错误 reason，保持 proactive/clear 兼容。
7. 重跑前后端定向测试。

## 任务 2：实现 Redis 单飞、去重与断线取消

**文件：**

- 新增：`apps/server/src/main/java/com/chtholly/agent/ws/AgentTurnCoordinator.java`
- 新增：`apps/server/src/main/java/com/chtholly/agent/runtime/AgentTurnControl.java`
- 新增：`apps/server/src/test/java/com/chtholly/agent/ws/AgentTurnCoordinatorTest.java`
- 修改：`apps/server/src/main/java/com/chtholly/agent/ws/AgentWebSocketHandler.java`
- 修改：`apps/server/src/test/java/com/chtholly/agent/ws/AgentWebSocketHandlerTest.java`

**步骤：**

1. 写 coordinator 测试，覆盖 `ACQUIRED`、`TURN_IN_PROGRESS`、`DUPLICATE_REQUEST` 和 owner-only release。
2. 运行测试，确认类尚不存在或行为未实现。
3. 用 Redis Lua 原子获取 active/request key，并用 compare-and-delete 释放 active key；提供测试用内存实现。
4. 写 handler 并发与关闭测试：同一逻辑会话第二次请求被拒，连接关闭会取消 Future、释放 lease，迟到 sink 不再发送。
5. 运行测试确认失败，然后在 handler 中用 `FutureTask`、连接到 turn 映射和取消令牌实现生命周期。
6. 重跑定向测试，确认不同逻辑会话仍可独立执行。

## 任务 3：建立整轮预算并传播到运行时

**文件：**

- 新增：`apps/server/src/main/java/com/chtholly/agent/runtime/AgentTurnBudget.java`
- 新增：`apps/server/src/test/java/com/chtholly/agent/runtime/AgentTurnBudgetTest.java`
- 修改：`apps/server/src/main/java/com/chtholly/agent/config/AgentProperties.java`
- 修改：`apps/server/src/main/resources/application.yml`
- 修改：`apps/server/src/main/java/com/chtholly/agent/runtime/AgentLlmInvoker.java`
- 修改：`apps/server/src/test/java/com/chtholly/agent/runtime/AgentLlmInvokerTest.java`
- 修改：`apps/server/src/main/java/com/chtholly/agent/runtime/AgentToolExecutor.java`
- 修改：`apps/server/src/test/java/com/chtholly/agent/runtime/AgentToolExecutorTest.java`
- 修改：`apps/server/src/main/java/com/chtholly/agent/runtime/AgentLoopRequest.java`
- 修改：`apps/server/src/main/java/com/chtholly/agent/runtime/AgentLoopResult.java`
- 修改：`apps/server/src/main/java/com/chtholly/agent/runtime/AgentLoopExecutor.java`
- 修改：`apps/server/src/test/java/com/chtholly/agent/runtime/AgentLoopExecutorTest.java`

**步骤：**

1. 写预算测试，覆盖全局上限、Skill 收紧、取消、过期和阶段剩余时间。
2. 运行测试确认失败，实现基于单调时钟的 `AgentTurnBudget`。
3. 写 LLM/工具测试，断言显式剩余预算小于单次配置时采用剩余预算，并在超时/中断时取消底层 Future。
4. 运行测试确认旧重载忽略剩余预算，新增兼容重载并实现 `min(stage, remaining)`。
5. 写 loop 测试，覆盖步骤前过期、LLM 阶段耗尽、工具阶段耗尽和取消终态。
6. 给 `AgentLoopRequest` 增加预算并保留旧构造器，传播到 LLM/工具调用，新增 `TURN_TIMEOUT/CANCELLED` 结果。
7. 重跑全部运行时定向测试。

## 任务 4：在 Agent 编排中执行 deadline、Memory 围栏和快速输出

**文件：**

- 修改：`apps/server/src/main/java/com/chtholly/agent/ChthollyAgent.java`
- 修改：`apps/server/src/test/java/com/chtholly/agent/ChthollyAgentTest.java`

**步骤：**

1. 写失败测试：Skill 的 `timeoutBudgetMs` 收紧全局预算；检索超时终止整轮；最终生成和引用修复使用剩余预算；超时/取消不写 Memory；安全答案只发一个完整 delta 后发 final。
2. 运行 `ChthollyAgentTest`，确认失败原因分别来自预算未使用、Memory 无围栏和逐字符发送。
3. 新增带 turn control/budget 的 run 重载，并保留现有公共重载供既有调用与测试兼容。
4. 在虚拟线程中按剩余预算执行上下文构建；把有效预算传入 loop、最终生成、边界回复与引用修复。
5. 每次事件发送和 Memory 写入前检查 turn 可用性；统一记录 timeout/cancel 终态。
6. 删除服务端逐字符 sleep，校验通过后发送一个完整 delta 与 final。
7. 重跑 `ChthollyAgentTest` 和运行时测试。

## 任务 5：扩展 Trace 的 turn、预算、规划与答案时序

**文件：**

- 修改：`apps/server/src/main/java/com/chtholly/agent/observability/AgentExecutionTrace.java`
- 修改：`apps/server/src/main/java/com/chtholly/agent/observability/AgentComponentVersions.java`
- 修改：`apps/server/src/test/java/com/chtholly/agent/observability/AgentExecutionTraceTest.java`
- 修改：`apps/server/src/main/java/com/chtholly/agent/ChthollyAgent.java`

**步骤：**

1. 写 payload 测试，要求 `turn`、`toolPlan`、`answerTiming` 三组字段，并确认正文不进入 payload。
2. 运行测试确认字段缺失。
3. 扩展 Trace 构造与记录方法，保留旧构造器；让 turnId 成为 correlationId。
4. 在 Agent/handler 中记录有效预算、工具计划、超时阶段、取消状态和三个答案时序。
5. 重跑 Trace、Agent 和持久化相关测试。

## 任务 6：改进 Skill 路由、参数 schema 与工具最小化

**文件：**

- 修改：`apps/server/src/main/java/com/chtholly/agent/skill/SkillSelector.java`
- 修改：`apps/server/src/test/java/com/chtholly/agent/skill/SkillSelectorTest.java`
- 新增：`apps/server/src/main/java/com/chtholly/agent/runtime/AgentToolPlanner.java`
- 新增：`apps/server/src/test/java/com/chtholly/agent/runtime/AgentToolPlannerTest.java`
- 修改：`apps/server/src/main/java/com/chtholly/agent/tools/ArticleRagTool.java`
- 修改：`apps/server/src/main/java/com/chtholly/agent/tools/BangumiCharactersTool.java`
- 修改：`apps/server/src/main/java/com/chtholly/agent/tools/BangumiPersonWorksTool.java`
- 修改对应三个工具测试
- 修改：`apps/server/src/main/java/com/chtholly/agent/ChthollyAgent.java`

**步骤：**

1. 写 SkillSelector 失败测试：`求证/验证真假`、`框架/目录` 可命中；单独“是什么”不命中；复合事实核查/大纲请求按优先级稳定选择。
2. 实现有优先级的确定性规则并重跑测试。
3. 写工具 schema 失败测试，断言字段名、类型和 required 状态；实现 schema。
4. 写 ToolPlanner 失败测试，覆盖当前文章 Skill 移除重复站内工具、评分/角色/作者问题的 Bangumi 子集、普通对话保留五工具。
5. 实现 planner，并在 ChthollyAgent 构建 Context 前应用；把原因和有效工具写 Trace。
6. 重跑 Skill、工具、Agent 定向测试。

## 任务 7：完成前端断线恢复状态

**文件：**

- 修改：`apps/web/components/agent/hooks/useAgentWebSocket.ts`
- 修改：`apps/web/components/agent/hooks/useAgentWebSocket.test.ts`
- 按测试需要修改：`apps/web/components/agent/AgentChatProvider.test.tsx`

**步骤：**

1. 写失败测试：busy turn 遇到 close/error 时只收尾一次，保留已收文本、取消 streaming、追加中断提示并恢复可发送。
2. 运行定向 Vitest，确认当前实现只修改 connected 导致失败。
3. 实现幂等 `interruptActiveTurn`，清理 request/turn、steps、streaming 与 busy；下一次 send 建立新连接和新 request。
4. 重跑 Agent 前端定向测试。

## 任务 8：升级 ADMIN Trace 归档与展示

**文件：**

- 修改：`apps/server/src/main/java/com/chtholly/agent/observability/`
- 修改：`apps/server/src/main/java/com/chtholly/agent/trace/`
- 修改：`apps/web/components/site/TraceDetailView.tsx`
- 修改：`apps/web/lib/types/trace.ts`
- 修改对应 Trace 后端与前端测试

**步骤：**

1. 先写投影与 UI 失败测试，覆盖原始问题/上下文、LLM 输入输出、工具输入/Observation、外部条件、最终答案、步骤摘要和完整度。
2. 把 payload 升级为 `agent-trace-v4`，保持旧版本兼容读取，详情接口保持 ADMIN-only。
3. 只遮蔽基础设施凭证；记录每段正文的长度、SHA-256、截断状态和整轮容量计数。
4. 用类型化 DTO 和前端类型投影展示，不透传未知数据库 JSON。

## 任务 9：增加受控公网研究与动态 Evidence

**文件：**

- 新增：`apps/server/src/main/java/com/chtholly/agent/tools/WebSearchTool.java`
- 新增：`apps/server/src/main/java/com/chtholly/agent/tools/WebFetchTool.java`
- 新增：`apps/server/src/main/java/com/chtholly/agent/web/`
- 修改：`apps/server/src/main/java/com/chtholly/agent/runtime/AgentToolPlanner.java`
- 修改：`apps/server/src/main/java/com/chtholly/agent/runtime/AgentLoopExecutor.java`
- 修改：`apps/server/src/main/java/com/chtholly/agent/evidence/`
- 新增/修改对应工具、安全、循环和 Evidence 测试

**步骤：**

1. 写工具规划失败测试：站内限定优先排除公网；URL 只开放抓取；显式联网/时效意图开放搜索与抓取；普通闲聊不出站。
2. 实现 discovery-only `web_search` 和 `web_fetch`，不新增密钥、端口或必需配置。
3. 为抓取链增加连接级 DNS pinning、逐 hop URL/redirect/robots 校验、用户与 host 配额、媒体类型/体积/charset 边界。
4. 让成功抓取生成绑定 final URL 与正文哈希的 PUBLIC Evidence；搜索摘要不得直接获得 citation。
5. 循环累计本轮所有搜索候选 URL，只有抓取命中任一可见候选且产出 Evidence 才允许结束；不得用独立总量上限丢弃模型仍可见的候选，同 URL 新哈希原位更新 citation 并重新注入；最终答案 transcript 保留工具元数据但不重复注入历史动态 Evidence，只使用最终 canonical Evidence 快照。
6. 把 provider HTTP 元数据、每跳安全决策、实际编码和抽取结果写入 Trace。

## 任务 10：更新架构文档并做完整验证

**文件：**

- 修改：`docs/architecture/agent-system.md`
- 修改：`docs/architecture/request-flows.md`
- 修改：`docs/architecture/backend.md`
- 修改：本设计与实施计划

**步骤：**

1. 更新 WebSocket envelope、单飞/取消语义、deadline、Trace v4、动态 Evidence 与公网工具边界；只记录真实已实现行为。
2. 后端执行定向测试集合。
3. 后端执行 `mvn test`。
4. 前端执行 `npm run test:run`。
5. 前端执行 `npm run build`。
6. 执行 `git diff --check`、配置/端口零改动检查与 `git status --short`，检查只包含本任务文件。
7. 分职责提交中文 Conventional Commits；每次提交前执行新增忽略文件审计。
