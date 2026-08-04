# 后端应用层架构重构实施计划

> **执行要求：** 使用 `superpowers:subagent-driven-development` 按任务顺序实施；每项生产代码必须先通过 characterization test 或目标边界测试形成 RED，再进行最小重构并验证 GREEN。不得改变端口、配置、外部 API、WebSocket 协议和数据权威语义。

**目标：** 在兼容现有调用方的前提下，治理后端 Java 的适配层越界与核心 God Class，使 Agent、文章、认证、关系、存储和死信链路形成可测试的应用服务边界。

**架构：** 采用兼容门面下的渐进式绞杀重构。Controller/Handler 只负责协议，Facade 只负责入口兼容，应用服务负责编排，基础设施通过窄端口接入。用 ArchUnit 与结构预算冻结遗留债务并禁止新增。

**技术栈：** Java 21、Spring Boot 3.2.4、MyBatis-Plus、Redis、Kafka、ArchUnit、JUnit 5、Mockito、Testcontainers。

---

## Task 1：建立全后端架构适配度护栏

**文件：**

- 新增：`apps/server/src/test/java/com/chtholly/architecture/BackendLayerArchitectureTest.java`
- 新增：`apps/server/src/test/java/com/chtholly/architecture/ComponentDependencyBudgetTest.java`
- 参考：`apps/server/src/test/java/com/chtholly/agent/config/AgentExtensionBoundaryArchitectureTest.java`

- [ ] 写 ArchUnit 测试，禁止 Controller 新增对 Mapper、Redis、Kafka 和 Elasticsearch 的直接依赖。
- [ ] 为当前三个明确遗留 Controller 建立精确临时基线，确认测试先因未列入基线的现有越界失败。
- [ ] 增加构造依赖预算和超长应用服务显式基线；DTO、record、纯投影与 Trace 聚合不按注入依赖误判。
- [ ] 增加禁止字段注入、Mapper 反向依赖 API/Service 的规则。
- [ ] 运行：`cd apps/server; mvn -q '-Dtest=BackendLayerArchitectureTest,ComponentDependencyBudgetTest' test`
- [ ] 提交：`test: 建立后端架构边界护栏`

## Task 2：瘦身应用启动入口和 API Controller

**文件：**

- 修改：`apps/server/src/main/java/com/chtholly/ChthollyApplication.java`
- 新增：`apps/server/src/main/java/com/chtholly/seed/contentpack/ContentPackCliLauncher.java`
- 修改：`apps/server/src/test/java/com/chtholly/ChthollyApplicationTest.java`
- 修改：`apps/server/src/main/java/com/chtholly/relation/api/RelationController.java`
- 新增：`apps/server/src/main/java/com/chtholly/relation/service/RelationCounterQueryService.java`
- 新增：`apps/server/src/test/java/com/chtholly/relation/service/RelationCounterQueryServiceTest.java`
- 修改：`apps/server/src/test/java/com/chtholly/relation/api/RelationControllerCounterTest.java`
- 修改：`apps/server/src/main/java/com/chtholly/storage/api/StorageController.java`
- 新增：`apps/server/src/main/java/com/chtholly/storage/application/StorageUploadApplicationService.java`
- 新增：`apps/server/src/main/java/com/chtholly/storage/application/PostOwnershipReader.java`
- 新增：`apps/server/src/main/java/com/chtholly/storage/application/MapperPostOwnershipReader.java`
- 新增：`apps/server/src/test/java/com/chtholly/storage/application/StorageUploadApplicationServiceTest.java`

- [ ] 先写 CLI 参数解析、关系计数降级/校准、存储权限与 object key 的 characterization tests 并确认 RED。
- [ ] 将内容包 CLI 启动编排迁出 `main` 类。
- [ ] 将 Relation 计数读取、SDS 解码、缓存重建和 DB 校准迁到查询应用服务。
- [ ] 将存储权限、键生成、校验和上传编排迁到应用服务；Controller 只映射 HTTP。
- [ ] 移除上述 Controller 的 Mapper/Redis 直接依赖并收紧架构基线。
- [ ] 运行：`mvn -q '-Dtest=ChthollyApplicationTest,RelationControllerCounterTest,RelationControllerSecurityTest,RelationCounterQueryServiceTest,StorageUploadApplicationServiceTest,BackendLayerArchitectureTest' test`
- [ ] 提交：`refactor: 收口启动与接口层应用编排`

## Task 3：把 Dead Letter 重放状态机迁出 Controller

**文件：**

- 修改：`apps/server/src/main/java/com/chtholly/common/kafka/deadletter/DeadLetterController.java`
- 新增：`apps/server/src/main/java/com/chtholly/common/kafka/deadletter/DeadLetterReplayService.java`
- 新增：`apps/server/src/main/java/com/chtholly/common/kafka/deadletter/DeadLetterReplayCommand.java`
- 新增：`apps/server/src/main/java/com/chtholly/common/kafka/deadletter/DeadLetterReplayResult.java`
- 修改：`apps/server/src/test/java/com/chtholly/common/kafka/deadletter/DeadLetterControllerTest.java`
- 新增：`apps/server/src/test/java/com/chtholly/common/kafka/deadletter/DeadLetterReplayServiceTest.java`

- [ ] 将 claim、发送、ACK、迟到回调、不确定态恢复路径写成服务级 characterization tests。
- [ ] 完整迁移状态机，Controller 只解析请求和映射状态码。
- [ ] 保持 topic、headers、超时、重试和错误文案不变。
- [ ] 收紧 API 层 Kafka 依赖架构基线。
- [ ] 运行：`mvn -q '-Dtest=DeadLetterControllerTest,DeadLetterReplayServiceTest,DeadLetterMessageServiceTest,BackendLayerArchitectureTest' test`
- [ ] 提交：`refactor: 提取死信重放应用服务`

## Task 4：提取 Agent 预算、Memory、交付与 Trace 生命周期

**文件：**

- 新增：`apps/server/src/main/java/com/chtholly/agent/runtime/AgentBoundedCallExecutor.java`
- 新增：`apps/server/src/main/java/com/chtholly/agent/memory/AgentMemoryCommitter.java`
- 新增：`apps/server/src/main/java/com/chtholly/agent/runtime/turn/AgentTurnCommand.java`
- 新增：`apps/server/src/main/java/com/chtholly/agent/runtime/turn/AgentTurnCompletionService.java`
- 新增：`apps/server/src/main/java/com/chtholly/agent/observability/AgentTurnTraceLifecycle.java`
- 新增相应测试：`apps/server/src/test/java/com/chtholly/agent/runtime/AgentBoundedCallExecutorTest.java`
- 新增相应测试：`apps/server/src/test/java/com/chtholly/agent/memory/AgentMemoryCommitterTest.java`
- 新增相应测试：`apps/server/src/test/java/com/chtholly/agent/runtime/turn/AgentTurnCompletionServiceTest.java`
- 新增相应测试：`apps/server/src/test/java/com/chtholly/agent/observability/AgentTurnTraceLifecycleTest.java`
- 修改：`apps/server/src/main/java/com/chtholly/agent/ChthollyAgent.java`
- 修改：`apps/server/src/test/java/com/chtholly/agent/ChthollyAgentTest.java`

- [ ] 先验证预算超时/中断、direct/fenced Memory、交付顺序和客户端终态 Trace 收尾的当前契约。
- [ ] 提取通用有界调用执行器，保持异常分类和线程中断标记。
- [ ] 提取 Memory 提交与成功交付服务，确保 Memory 失败时不发送答案。
- [ ] 提取 Trace 生命周期，确保 WebSocket 下延迟到交付终态再持久化。
- [ ] 让原有 35 个 Agent 行为测试继续通过。
- [ ] 运行：`mvn -q '-Dtest=AgentBoundedCallExecutorTest,AgentMemoryCommitterTest,AgentTurnCompletionServiceTest,AgentTurnTraceLifecycleTest,ChthollyAgentTest' test`
- [ ] 提交：`refactor: 分离 Agent 单轮可靠性交付边界`

## Task 5：提取 Agent 准备、Boundary 与最终回答流水线

**文件：**

- 新增：`apps/server/src/main/java/com/chtholly/agent/runtime/planning/AgentToolCatalog.java`
- 新增：`apps/server/src/main/java/com/chtholly/agent/runtime/planning/AgentTurnPreparationService.java`
- 新增：`apps/server/src/main/java/com/chtholly/agent/runtime/planning/AgentTurnPreparation.java`
- 新增：`apps/server/src/main/java/com/chtholly/agent/runtime/answer/AgentBoundaryResponseService.java`
- 新增：`apps/server/src/main/java/com/chtholly/agent/runtime/answer/AgentFinalAnswerService.java`
- 新增：`apps/server/src/main/java/com/chtholly/agent/runtime/answer/FinalAnswerProtocolGuard.java`
- 新增：`apps/server/src/main/java/com/chtholly/agent/runtime/answer/AgentCitationRepairService.java`
- 新增对应的 planning/answer 单元测试。
- 修改：`apps/server/src/main/java/com/chtholly/agent/ChthollyAgent.java`
- 修改：`apps/server/src/test/java/com/chtholly/agent/ChthollyAgentTest.java`

- [ ] 先迁移重复工具名、Skill 工具/预算收缩、无证据短路、Action JSON、Citation 修复和 Skill 输出验证测试。
- [ ] 提取准备阶段不可变结果，工具索引在 Bean 初始化时 fail-fast。
- [ ] 提取 Boundary 和 Final 回答服务；最终 prompt 不包含工具协议。
- [ ] 保持“完整缓冲并验证后发送”和“一次修复”契约。
- [ ] 运行所有新增测试及 `ChthollyAgentTest`。
- [ ] 提交：`refactor: 拆分 Agent 准备与回答流水线`

## Task 6：建立薄 Agent 门面并拆分 Loop 状态协作者

**文件：**

- 新增：`apps/server/src/main/java/com/chtholly/agent/runtime/turn/AgentTurnOrchestrator.java`
- 新增：`apps/server/src/main/java/com/chtholly/agent/runtime/AgentDecisionGateway.java`
- 新增：`apps/server/src/main/java/com/chtholly/agent/runtime/AgentActionParser.java`
- 新增：`apps/server/src/main/java/com/chtholly/agent/runtime/AgentEvidenceTracker.java`
- 修改：`apps/server/src/main/java/com/chtholly/agent/runtime/AgentLoopExecutor.java`
- 修改：`apps/server/src/main/java/com/chtholly/agent/ChthollyAgent.java`
- 新增/修改相应 runtime 测试。

- [ ] 先写门面重载归一化和 Loop 状态迁移 characterization tests。
- [ ] 让 `ChthollyAgent` 仅保留重载兼容与 command 委托。
- [ ] 让 Orchestrator 仅表达阶段顺序，不承载提示词、解析或持久化细节。
- [ ] 将模型决策重试、Action 解析和 Evidence/Web 候选状态迁出 Loop。
- [ ] 增加架构规则，禁止门面重新依赖 LLM、Context、Skill 实现、Trace Persistence 和工具列表。
- [ ] 运行：`mvn -q '-Dtest=ChthollyAgentTest,AgentLoopExecutorTest,AgentExtensionBoundaryArchitectureTest,BackendLayerArchitectureTest,ComponentDependencyBudgetTest' test`
- [ ] 提交：`refactor: 建立 Agent 单轮应用编排`

## Task 7：瘦身 Agent WebSocket 适配层

**文件：**

- 修改：`apps/server/src/main/java/com/chtholly/agent/ws/AgentWebSocketHandler.java`
- 新增：`apps/server/src/main/java/com/chtholly/agent/ws/AgentConnectionRegistry.java`
- 新增：`apps/server/src/main/java/com/chtholly/agent/ws/AgentWsMessageDispatcher.java`
- 新增：`apps/server/src/main/java/com/chtholly/agent/ws/AgentTurnSubmissionService.java`
- 新增对应测试并修改 `AgentWebSocketHandlerTest.java`。

- [ ] 固化认证、协议错误、轮次串行、断线取消、终态 holdback、会话清理和主动通知行为。
- [ ] 将连接状态、消息分派和任务提交迁到独立协作者。
- [ ] Handler 仅保留 WebSocket 生命周期适配。
- [ ] 运行 WebSocket 定向测试及 Agent 全套测试。
- [ ] 提交：`refactor: 瘦身 Agent WebSocket 适配层`

## Task 8：拆分文章命令与发布协调

**文件：**

- 修改：`apps/server/src/main/java/com/chtholly/post/service/impl/PostServiceImpl.java`
- 新增：`apps/server/src/main/java/com/chtholly/post/service/impl/PostCommandService.java`
- 新增：`apps/server/src/main/java/com/chtholly/post/service/impl/PostPublicationCoordinator.java`
- 新增：`apps/server/src/main/java/com/chtholly/post/service/impl/PostContentQueryService.java`
- 修改：`apps/server/src/test/java/com/chtholly/post/service/impl/PostServiceImplTest.java`
- 修改：`apps/server/src/test/java/com/chtholly/post/service/impl/PostServiceImplTransactionTest.java`
- 新增相应服务级测试。

- [ ] 先固化草稿、更新、发布、可见性、删除、Outbox、缓存和降级顺序。
- [ ] 保留 `PostService` 公开接口和 `PostServiceImpl` 兼容门面。
- [ ] 将命令、发布协调和内容查询分派到独立服务。
- [ ] 不在结构重构中改变普通事件为 AFTER_COMMIT；将该一致性修复保留为独立行为任务。
- [ ] 运行文章服务定向测试。
- [ ] 提交：`refactor: 拆分文章命令与发布编排`

## Task 9：拆分公开 Feed 查询、缓存和装配

**文件：**

- 修改：`apps/server/src/main/java/com/chtholly/post/service/impl/PostFeedServiceImpl.java`
- 新增：`apps/server/src/main/java/com/chtholly/post/service/impl/PublicPostFeedQueryService.java`
- 新增：`apps/server/src/main/java/com/chtholly/post/service/impl/PostFeedCacheGateway.java`
- 新增：`apps/server/src/main/java/com/chtholly/post/service/impl/FeedItemAssembler.java`
- 修改：`apps/server/src/main/java/com/chtholly/post/service/impl/PersonalPostFeedService.java`
- 修改/新增相应 Feed 测试。

- [ ] 固化 offset/cursor、Redis/Caffeine、热点续期、DB 回源和 enrichment 行为。
- [ ] 提取缓存协议和统一 Feed Item 装配。
- [ ] 让公开与个人 Feed 复用装配器，不改变 DTO 和排序。
- [ ] 运行所有 Feed 测试。
- [ ] 提交：`refactor: 分离内容流查询缓存与装配`

## Task 10：拆分 Auth 与 Relation 应用用例

**文件：**

- 修改：`apps/server/src/main/java/com/chtholly/auth/service/AuthService.java`
- 新增：`apps/server/src/main/java/com/chtholly/auth/service/AuthRegistrationService.java`
- 新增：`apps/server/src/main/java/com/chtholly/auth/service/AuthLoginService.java`
- 新增：`apps/server/src/main/java/com/chtholly/auth/service/AuthTokenLifecycleService.java`
- 新增：`apps/server/src/main/java/com/chtholly/auth/service/AuthPasswordRecoveryService.java`
- 修改/新增 Auth 测试。
- 修改：`apps/server/src/main/java/com/chtholly/relation/service/impl/RelationServiceImpl.java`
- 新增：`apps/server/src/main/java/com/chtholly/relation/service/impl/RelationCommandService.java`
- 新增：`apps/server/src/main/java/com/chtholly/relation/service/impl/RelationQueryService.java`
- 新增：`apps/server/src/main/java/com/chtholly/relation/service/impl/RelationProjectionCache.java`
- 新增 Relation characterization tests。

- [ ] 固化验证码、注册、登录失败防护、Token 刷新/登出、密码重置部分失败语义。
- [ ] 让 Auth 门面按用例委托，不改变 Token、Redis key 和审计数据。
- [ ] 固化关注/取关、分页、缓存和 Profile 装配行为。
- [ ] 拆分 Relation 命令、查询与缓存投影，不改变事件时机。
- [ ] 运行 Auth/Relation 定向测试并收紧依赖预算。
- [ ] 提交：`refactor: 拆分认证与关系应用用例`

## Task 11：文档、静态审查与全量验证

**文件：**

- 修改：`docs/architecture/backend.md`
- 修改：`docs/architecture/agent-system.md`
- 修改：`docs/architecture/request-flows.md`
- 修改：`docs/development/testing.md`（仅在新增验证入口时）

- [ ] 更新应用层、Agent 单轮、Controller 边界和遗留基线说明。
- [ ] 检查所有新增公开类与公开方法的英文 Javadoc，复杂实现保留中文 WHY 注释。
- [ ] 运行：`cd apps/server; mvn test '-Dspring.profiles.active=test'`
- [ ] 运行：`mvn -Pintegration-test verify`；若本机外部依赖不可用，保存完整失败证据并确认不是代码回归。
- [ ] 运行：`cd ../..; git diff --check; git status --short`
- [ ] 审核提交历史、相对 `origin/main` 的文件范围和新增忽略文件审计。
- [ ] 请求独立代码审查，处理发现后重新跑相关验证。
- [ ] 提交：`docs: 更新后端应用层架构说明`
- [ ] 停在 `refactor/backend-application-services` 的干净 worktree，等待用户授权本地合并；不 push。

## 执行中的缩减规则

本计划覆盖整个后端的主要运行链，但不以“改动文件越多越好”为目标。出现以下任一情况时，对应子项应停止在已建立的边界，而不是强行继续：

- 需要改变外部 API、事件格式、端口或配置。
- 需要修改数据库结构或 Redis key 语义。
- 现有测试无法区分重构与行为变更，且补足集成验证超出当前环境能力。
- 新提取类只是原 God Class 的一比一搬家，未减少修改理由或依赖方向。

停止某个高风险子项不影响继续治理其他独立后端边界；最终交付必须明确列出已完成范围与保留债务。
