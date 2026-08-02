# Agent 单轮可靠性与工具规划设计

## 背景

当前 Agent 已具备页面上下文、Skill、Evidence、受限工具、引用校验和 Trace，但一轮对话仍有三个运行时缺口：

1. WebSocket 消息没有客户端请求标识和服务端轮次标识，同一逻辑会话的并发请求可能交错；
2. LLM 与工具只有单次超时，Skill 的 `timeoutBudgetMs` 尚未形成整轮截止时间，最终答案还会在安全校验后逐字等待；
3. Skill 隐式路由使用无优先级子串匹配，三个只读 Skill 暴露相同工具集合，部分工具也缺少声明式参数 schema。

本次改造把“一轮 Agent 请求”提升为有身份、有所有权、有截止时间、可取消且可追踪的运行单元，同时不改变现有页面入口、端口、模型与外部服务配置。

## 目标

- 客户端生成 `requestId`，服务端接受请求后生成唯一 `turnId`，本轮全部事件携带二者。
- 同一 `(userId, chatSessionId)` 在所有应用实例之间只允许一个正在执行的 turn；重复请求和并发请求不重复执行。
- WebSocket 断开时取消该连接拥有的 turn，前端解除 busy 状态并允许用户重新发送；不伪装成旧流可以续传。
- 建立整轮绝对 deadline，并让检索、循环 LLM、工具、最终生成和引用修复共同消费剩余预算。
- 保留“完整生成后验证引用”的安全门，删除服务端逐字符等待，使通过校验的答案立即可见。
- Trace 在 ADMIN 边界内记录请求/轮次身份、预算、超时阶段、取消状态、实际工具计划、答案可见时序，以及复盘所需的原始问题、页面上下文、LLM 输入输出、工具输入输出和最终答案；只过滤基础设施凭证，并用容量与完整度字段显式标记截断。
- 补齐工具 schema，降低 Skill 隐式误触发，按问题意图收窄模型实际可调用的工具，并增加无需新密钥的受控公网搜索与抓取能力。

## 非目标

- 不实现断线后续传旧 token、跨实例回放已完成答案或持久任务队列；断线语义是取消并恢复到可重试状态。
- 不新增用户侧“查看 Trace”入口，也不新增演示预检页面。
- 不加入登录态浏览、任意浏览器操作或站外写操作工具；公网扩展只覆盖可审计的只读搜索与正文抓取。
- 不把 `agent-evaluation-v2` 做成用户页面；它仍是开发阶段的离线评测数据集与报告。

## 协议

### 客户端请求

```json
{
  "type": "chat",
  "requestId": "1c44507e-5c71-4bca-bf8d-3149175469a2",
  "sessionId": "sess-...",
  "message": "只依据当前文章总结三个观点",
  "taskType": "page-explain",
  "context": {}
}
```

`requestId` 由浏览器在每次真正发送时生成。一次人工重试必须生成新的 `requestId`；网络层对同一 payload 的重复投递不得重新运行。

### 服务端事件

服务端成功取得 turn 所有权后先发送：

```json
{
  "type": "accepted",
  "requestId": "...",
  "turnId": "...",
  "data": {}
}
```

随后 `think`、`act`、`observe`、`delta`、`final` 和 turn 内 `error` 都使用相同的 `requestId`、`turnId`。`proactive` 和 `cleared` 不属于聊天 turn，可以不携带二者。取得所有权前的校验错误尽量回显 `requestId`；并发冲突返回稳定原因 `TURN_IN_PROGRESS`，重复请求返回 `DUPLICATE_REQUEST`。

客户端只接收当前 request/turn 的事件。`accepted` 负责绑定服务端 `turnId`；旧连接或旧 turn 的迟到事件会被忽略。

## Turn 所有权与取消

新增 `AgentTurnCoordinator`，使用 Redis Lua 原子完成两件事：

- `agent:turn:active:<userId>:<sessionHash>` 保存当前 `turnId` 和短 TTL；
- `agent:turn:request:<userId>:<sessionHash>:<requestHash>` 保存 request 到 turn 的短期映射。

一次 acquire 返回三种明确结果：

- `ACQUIRED`：同时写入 active 与 request 映射；
- `TURN_IN_PROGRESS`：逻辑会话已有其他 turn；
- `DUPLICATE_REQUEST`：相同 request 已被接受过，并返回原 `turnId`。

释放使用 compare-and-delete Lua，仅 turn 所有者可以删除 active key。TTL 取全局 turn 上限再加固定清理余量，保证进程崩溃后锁会自动消失。

每个连接在进程内保存它拥有的 `FutureTask` 与取消令牌。连接关闭时：

1. 标记令牌已取消；
2. 中断本地任务；
3. 按所有权释放 Redis active key；
4. 事件 sink 拒绝迟到事件；
5. Agent 在写 Memory 前再次检查令牌和 deadline，避免被取消的答案进入历史。

关闭连接不会删除 request 去重映射。用户重新发送时生成新 request，旧 payload 的迟到重放仍不会重复执行。

## 整轮截止时间

新增 `agent.turn-timeout-seconds`，默认 60 秒。WebSocket 接受请求时创建单调时钟预算；选中 Skill 后，有效 deadline 为：

```text
min(全局 turn deadline, turn 开始时间 + Skill.timeoutBudgetMs)
```

每个阶段都只拿到 `min(自身上限, 整轮剩余时间)`：

- 上下文与 Evidence 检索在可取消的虚拟线程中执行，并受剩余预算约束；
- ReAct 每次 LLM 调用和工具执行使用剩余预算；
- 最终回答生成和最多一次引用修复继续消费同一预算；
- 任一阶段开始前都检查取消与过期状态。

整轮耗尽时统一终止为 `TIMEOUT`，Trace 记录 `timeoutStage`。断线中断终止为 `ABORTED/CANCELLED`。超时或取消后不再发送 final，也不写入 Memory。

## 最终答案输出

最终回答仍先完整收集，再依次通过 Evidence 引用校验和 Skill 输出校验。只有安全答案形成后才发送客户端。服务端不再按 Unicode 字符 `sleep`；一次发送完整 `delta`，随后发送 `final`。这样保留失败关闭边界，同时移除与答案长度线性增长的额外等待。

Trace 将区分：

- `modelFirstTokenMs`：模型首次产生内容，相对 turn 开始；
- `safeAnswerReadyMs`：引用与 Skill 校验完成；
- `firstClientDeltaMs`：首次可见答案交给 WebSocket sink。

## Trace 扩展

在现有 JSON payload 中升级为 `agent-trace-v4`，不新增数据库列：

- `turn.requestId`、`turn.turnId`、`turn.chatSessionId`、`turn.connectionId`；
- `turn.budgetMs`、`turn.timeoutStage`、`turn.cancelled`；
- `toolPlan.reason`、`toolPlan.effectiveTools`；
- `answerTiming.modelFirstTokenMs`、`safeAnswerReadyMs`、`firstClientDeltaMs`；
- 顶层 `steps`、`events`、`llmCalls`、`toolCalls`，以及 ADMIN 归档所需的原始问题、页面上下文、每次模型调用、工具输入/Observation、最终答案和失败因果链；
- `privacy` 与 `capture` 中的事件丢弃、工具预览截断、正文截断和凭证过滤计数。

Trace 的 `correlationId` 使用规范化 `turnId`，使协议事件、日志、Observation 与持久 Trace 可以稳定关联。详情接口保持 ADMIN-only；管理员能看到完整捕获正文与对应指纹、长度和 SHA-256，普通用户没有 Trace 入口。

## Skill 路由与工具规划

### 隐式 Skill 优先级

显式 `taskType` 始终优先且保持权威。没有显式类型时按风险和意图确定唯一结果：

1. 事实核查：`事实核查`、`查证`、`求证`、`验证真假`、`是否属实`、`是否准确` 等；
2. 证据大纲：`大纲`、`提纲`、`框架`、`目录`、`章节安排` 等；
3. 页面解释：`解释`、`总结`、`概括`、`主要观点`、`讲了什么` 等。

删除单独以“是什么”触发页面解释的规则；“这篇文章”只有和解释/总结意图组合时才触发。复合请求按上述优先级选择，不再因多个子串直接进入无意义的 `rule_conflict`。

### 最小工具计划

新增确定性 `AgentToolPlanner`。Skill 选择完成后先由 `KnowledgeContextContributor` 按 Evidence 策略检索站内内容，因此已选 Skill 默认移除重复的 `article_rag` 与 `fulltext_search`。再根据问题收窄 Bangumi 工具：

- 评分、集数、放送、季数：`bangumi_search`；
- 角色、人物、声优、配角：`bangumi_search` + `bangumi_characters`；
- 作者、漫画家及其他作品：`bangumi_person_works`；
- 明确 URL：`web_fetch`；明确联网、外部资料或时效意图：`web_search` + `web_fetch`；
- 没有外部资料意图：不给已选 Skill 暴露 Bangumi 或公网工具；“只依据当前文章/站内”与“不要联网”优先排除公网工具。

普通对话未命中 Skill 且没有公网意图时保留原有五工具集合；只有确定性命中 URL、联网研究或时效关键词时才增加公网工具，常见的“别联网/不联网”等否定表达具有更高优先级。`web_search` 结果只用于发现候选，必须抓取本轮累计候选中的至少一个页面并生成动态 Evidence 后才能结束；同 URL 内容变化会更新原 citation 的版本绑定。最终生成使用 canonical Evidence 快照，工具 transcript 只保留未附加动态 Evidence 的 canonical Observation；工具决策过程、客户端 Observation 和 ADMIN Trace 仍保留完整旧/新版本历史。Trace 记录最终集合、规划原因、候选/抓取关系和每个外部步骤的诊断元数据。

### 参数 schema

- `article_rag`：`query` 必填字符串，`topK` 可选整数；
- `bangumi_characters`：`keyword` 可选字符串，允许结合问题和历史推断；
- `bangumi_person_works`：`keyword`、`work_title`、`work_type` 均声明为可选，运行时继续要求 `keyword/work_title` 至少一个，并校验 `work_type`。
- `web_search`：`query` 必填、`maxResults` 有界；`web_fetch`：`url` 必填、`maxChars` 有界，运行时继续执行逐跳 URL/DNS/robots/媒体类型/体积/charset 校验。

## 前端状态机

前端维护当前 `requestId`、已接受的 `turnId`、临时消息和步骤：

```text
idle → sending(requestId) → accepted(turnId) → streaming → final/error → idle
                                  └─ socket close/error → interrupted → idle
```

断线时保留已经收到的助手文本，但将其标为非 streaming，并追加一条简短的中断提示；如果尚未收到文本，只追加提示。随后清空 busy、步骤和当前 turn，使下一次发送能够重新连接。`onerror` 与 `onclose` 共用幂等收尾，避免重复提示。

## 失败语义

| 场景 | 协议原因 | Trace | Memory |
|------|----------|-------|--------|
| 同会话已有 turn | `TURN_IN_PROGRESS` | 不创建新执行 Trace | 不写 |
| request 重复 | `DUPLICATE_REQUEST` | 不创建新执行 Trace | 不写 |
| 整轮耗尽 | `TURN_TIMEOUT` | `TIMEOUT` + `timeoutStage` | 不写 |
| 连接断开 | 无法发送；本地显示中断 | `ABORTED` + cancelled | 不写 |
| 单次工具失败但仍有预算 | 作为 observation | 保留工具失败明细 | 由后续最终结果决定 |

## 验证策略

- 后端先写失败测试覆盖 Redis 原子所有权、协议标识、并发拒绝、断线取消、deadline 传播、Memory 写入围栏、Trace payload 与工具规划。
- 前端先写失败测试覆盖 request 生成、accepted 绑定、迟到事件忽略以及 close/error 后解除 busy。
- 定向测试通过后执行后端 `mvn test`、前端 `npm run test:run` 与 `npm run build`。
- 提交前执行 `git diff --check`、新增忽略文件审计和任务范围审计。
