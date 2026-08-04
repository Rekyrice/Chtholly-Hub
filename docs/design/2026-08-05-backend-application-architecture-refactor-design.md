# 后端应用层架构重构设计

## 背景

Chtholly Hub 后端已经覆盖账号、文章、互动、内容流、搜索、异步事件和角色 Agent 等完整链路，但部分核心类在持续迭代中承担了过多职责。典型表现包括：

- `ChthollyAgent` 同时负责单轮生命周期、上下文与 Skill 准备、模型调用、回答校验、记忆提交、客户端事件和 Trace 收尾。
- `AgentWebSocketHandler` 同时处理协议适配、连接状态、轮次租约、任务提交和认知副作用。
- `PostServiceImpl`、`PostFeedServiceImpl`、`AuthService` 等应用服务混合了多个用例、缓存协议和基础设施副作用。
- 个别 Controller 直接使用 Mapper、Redis 或 Kafka，在传输层实现业务状态机。
- 当前缺少持续阻止 Controller 越界和核心类重新膨胀的架构适配度测试。

这些问题不会立即破坏功能，但会放大修改半径，使超时、事务、缓存一致性和 Agent 终态等关键契约更难验证。

## 目标与非目标

### 目标

1. 让 HTTP/WebSocket 适配层只负责协议解析、认证信息提取和响应映射。
2. 让应用服务按用例编排，让领域/基础设施协作者各自承担单一职责。
3. 将 `ChthollyAgent` 收敛为兼容门面，避免把原逻辑整体搬到新的 God Class。
4. 为文章、内容流、认证、互动和存储链路建立清晰的应用服务边界。
5. 用 ArchUnit 和结构预算测试阻止新增分层越界与复杂度倒退。
6. 保持现有 API、WebSocket 消息、端口、配置、数据库、缓存与异步语义不变。

### 非目标

- 不改变前端协议或页面行为。
- 不改变 Agent 提示词、工具选择规则、最大步数、错误文案和回答格式。
- 不迁移数据库表，不改变 MySQL 权威事实、Redis 投影和 Outbox 恢复语义。
- 不在本轮改写内容包离线导入、Trace DTO 兼容投影或 Counter Lua/聚合管线。
- 不进行全仓包名重写，也不以文件行数为唯一依据拆分数据聚合对象。

## 方案比较

### 方案 A：一次性六边形架构重写

一次重排所有包、端口和实现，最终结构最整齐，但会同时触碰事务、事件、缓存、条件 Bean 和外部协议。现有 1400 余个测试能降低风险，却不足以覆盖所有运行态组合，因此不采用。

### 方案 B：兼容门面下的渐进式绞杀重构（采用）

保留现有公开 Service、Controller 和 Agent 入口，通过 characterization tests 固化行为，再逐段提取应用服务、策略和网关。每个提交都能独立验证，也允许遗留调用方逐步迁移。

### 方案 C：只按行数机械拆类

改动较小，但容易把 `ChthollyAgent` 直接搬成另一个同等规模的 Orchestrator，不能形成可执行的边界，也无法阻止回退，因此不采用。

## 总体结构

```text
HTTP / WebSocket Adapter
        |
        v
Compatibility Facade / Application Use Case
        |
        +-- Domain policy and immutable result types
        +-- Query/command collaborators
        +-- Infrastructure ports
        |
        v
Mapper / Redis / Kafka / Search / Storage adapters
```

兼容门面保留当前接口，负责参数归一化和用例分派，不再实现业务细节。应用用例可以协调多个领域端口，但不得反向依赖 Controller 或具体传输实现。

## 架构护栏

新增架构适配度测试并采用“新债务零容忍、遗留债务显式冻结”的策略：

1. `..api..` 不直接依赖 `..mapper..`、Redis、Kafka、Elasticsearch 客户端。
2. Mapper 不依赖 API 或 Service。
3. `common` 不新增对业务包的反向依赖。
4. Agent WebSocket 适配层只调用 Agent 应用入口、连接注册表和协议对象。
5. 禁止字段注入。
6. 对 Controller、应用服务和方法建立结构预算；已有超限类列入显式基线，修改后只能单调下降。

结构预算不是为了强迫拆分 DTO 或聚合对象，而是识别同时拥有大量可注入协作者和控制流的方法。`AgentExecutionTrace`、`TraceDetailProjector` 等数据/投影类不会仅因行数被机械拆分。

## Agent 主链设计

### 兼容门面

`ChthollyAgent` 保留现有 `run(...)` 重载，统一转换为 `AgentTurnCommand` 并委托 `AgentTurnOrchestrator`。调用方和 WebSocket 事件协议无需改变。

### 单轮编排

Agent 单轮按以下阶段组织：

```text
创建 Trace 和根 Observation
  -> 输入与预算校验
  -> Skill / 工具 / Memory / Context 准备
  -> Boundary 或 Agent Loop
  -> 最终回答生成与协议校验
  -> Memory 围栏提交
  -> delta / timing / final
  -> 客户端交付终态
  -> Trace / Span / Metrics 持久化
```

拆分后的核心协作者：

- `AgentTurnPreparationService`：Skill 选择、工具权限收缩、预算收缩、Memory 读取和上下文构建。
- `AgentBoundaryResponseService`：无证据、澄清和安全降级回答。
- `AgentFinalAnswerService`：最终提示、完整缓冲、Action/Citation/Skill 校验与一次修复。
- `AgentMemoryCommitter`：普通和 WebSocket 围栏两种记忆写入路径。
- `AgentTurnCompletionService`：唯一允许执行成功交付顺序的位置。
- `AgentBoundedCallExecutor`：统一剩余预算、虚拟线程、中断和异常解包语义。
- `AgentTurnTraceLifecycle`：创建、终止和等待客户端交付后的观测收尾。

`AgentTurnOrchestrator` 只表达阶段顺序，不包含提示词、JSON 解析、Lua 状态判断或 Trace payload 组装。

### 必须保持的 Agent 契约

1. Skill 的工具权限和预算只能收缩，不能扩张全局配置。
2. 最终回答必须完整缓冲并通过协议、引用与 Skill 校验后才可发送。
3. Action JSON 只修复一次；再次出现即失败，不写记忆、不泄漏答案。
4. Citation 修复只能增加合法 Evidence 标记，不能修改正文。
5. Memory 提交必须先于任何 `delta` 或 `final`。
6. 整轮取消/超时不得伪装成成功降级。
7. WebSocket Trace 必须在客户端交付终态确定后持久化。
8. `requestId`、`turnId`、`sessionId`、`connectionId`、step index 和 attempt 不漂移。
9. `llm.enabled=false` 时不得因新增 Bean 提前实例化导致主站启动失败。

### Agent Loop 的第二层拆分

`AgentLoopExecutor` 保留循环状态迁移，模型决策重试、Action 解析、Evidence 累积和 Web 调研候选状态分别进入独立协作者。这样可避免 `ChthollyAgent` 变薄后把复杂度转移到 Loop。

`AgentExecutionTrace` 本轮优先保持兼容；它是运行态聚合且已有较高测试覆盖。只把生命周期编排迁出，不修改 Trace v3/v4 的字段和投影语义。

## 业务主链设计

### API 边界

- `RelationController` 的计数读取、Redis 兼容解码、MySQL 校准和重建收口到 `RelationCounterQueryService`。
- `StorageController` 的文章所有权判断、对象键生成和上传编排收口到 `StorageUploadApplicationService`。
- `DeadLetterController` 的 claim、Kafka 发送、ACK 等待与不确定态恢复收口到 `DeadLetterReplayService`。

Controller 仅负责认证、HTTP 参数和响应映射。

### 文章与内容流

- `PostServiceImpl` 保留 `PostService` 兼容门面，将草稿/正文命令、发布协调和查询分派到独立用例服务。
- 发布协调器保持原事务边界、Outbox 写入、缓存失效和事件顺序；本轮不改变同步搜索/RAG 的现有降级行为。
- `PostFeedServiceImpl` 将公开 Feed 查询、缓存读写和 Feed Item enrichment 分离，并与个人 Feed 复用统一装配器。

### 认证

`AuthService` 保留 Controller 使用的公开入口，将注册、登录、Token 生命周期和密码恢复拆为用例服务。验证码、失败次数、Refresh Token 轮换和 JWT 形状保持不变。

### 关系与计数

关系读写先通过命令/查询拆分建立边界，并补足 characterization tests。Counter 的 MySQL 权威互动关系、Redis Bitmap/计数投影和 Kafka receipt 管线近期已稳定，本轮不重写其核心算法，只在必要时提取纯查询协作者。

### 通用基础能力

跨领域使用的 Snowflake ID 和 Outbox 契约最终应迁到中立包，但采用小步机械迁移，不与业务行为重构混在同一提交。跨领域 Mapper 直接引用先以基线冻结，逐个通过端口消除。

## 事务、缓存与事件原则

- MySQL 仍是文章、评论、关注、互动关系等业务事实的权威数据源。
- Redis 仍是 Token、限流、缓存、计数/关系投影和热点结构，不成为新的唯一事实源。
- 事务内写入与事务后副作用的既有顺序不得因为拆类发生变化。
- 当前普通异步事件潜在的提交前执行风险单独记录；只有在 characterization test 覆盖后才迁移为 `AFTER_COMMIT` 或 Outbox，不在纯结构提交中暗改语义。
- 搜索、RAG 和通知的降级必须保持主业务可用性，失败观测仍完整记录。

## 测试策略

每条拆分遵循以下顺序：

1. 用现有测试和新增 characterization test 固化当前行为。
2. 先写目标边界或架构测试并确认失败。
3. 提取协作者，兼容门面委托。
4. 跑领域定向测试。
5. 每个责任边界提交前运行 `git diff --check` 和忽略文件审计。
6. 最终运行全量单测；涉及 Redis/MySQL/Kafka 语义的提交再运行集成测试。

重点回归：

- Agent 的超时/取消、Action JSON、Citation、Memory 围栏、客户端交付和 Trace。
- 文章发布事务、Outbox、缓存失效与搜索/RAG 降级。
- Feed 的 offset/cursor、缓存回源和装配一致性。
- Auth 的验证码、登录失败防护、Token 刷新和密码重置。
- Relation/Storage/Dead Letter 的权限、重试和错误映射。

## 提交边界

按可独立验证的职责拆分提交：

1. 设计与实施计划。
2. 架构适配度测试与 API 边界治理。
3. Agent 生命周期、准备、回答、Memory 与交付拆分。
4. Agent Loop 状态协作者拆分。
5. 文章/Feed 应用服务拆分。
6. Auth/Relation 等应用服务拆分。
7. 架构文档与全量验证修正。

任一阶段若必须改变外部协议、配置、事务语义或数据权威边界，即停止该项并保留为后续行为变更，而不是在结构重构中混入。

## 完成标准

- 对外 API、WebSocket 事件和配置无变化，前端无需配套修改。
- Controller 不再直接访问 Mapper、Redis、Kafka 或搜索客户端。
- `ChthollyAgent` 成为小型兼容门面，核心编排由多个职责明确的协作者完成，且不存在等规模替代 God Class。
- 本轮涉及的核心业务门面明显降低依赖数、方法长度和修改理由数量。
- 架构测试能阻止新越界与已治理类重新膨胀。
- 定向测试、全量单测与适用的集成测试通过。
- 工作树只包含本任务文件和提交，并停在可本地合并状态。
