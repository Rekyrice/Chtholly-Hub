# Chtholly 页面与 Trace 控制台重设计

## 1. 文档目的

本文定义 `/chtholly` 角色页面与 `/admin/traces` 可观测控制台的下一阶段产品结构、前后端合同、交互边界和验证要求。

本次设计解决两个已确认问题：

1. Chtholly 页面把迎接、经历、推荐和社区观察排成四张同构纵向卡片，像连续帖子，缺少空间主次与角色页面辨识度。
2. Trace 控制台只消费聚合统计和扁平列表；后端已有详情查询，但前端未展示单次执行的内部过程，现有记录也无法可靠建立 Step 与 LLM/Tool 调用之间的关联。

## 2. 当前实现审计

### 2.1 Chtholly 页面

当前 `/chtholly` 由 Server Component 加载经历时间线、公开 Feed 和标签数量，再依次渲染四个 `.room-zone`。四个区域使用相同的卡片轮廓、背景和纵向间距，仅通过蒙版强度区分，因此视觉上仍然是四张连续帖子卡。

当前页面还存在以下能力错位：

- `mood` 固定为 `0`，后端 Mood 属于内部能力，没有公开读取合同；本次不伪造实时心情接口。
- `/api/v1/topics` 已提供公开 Topic 聚类和主题文章列表，但前端没有对应 service/type，也没有页面消费。
- “她看到的社区”只展示当前 Feed 数量、标签数量和固定状态文案，数据解释力较弱。
- `/agent` 已有完整会话、Live2D、会话侧栏和设置；把该工作台原样嵌入 `/chtholly` 会造成重复运行时、重复 WebSocket 和过重首屏。

### 2.2 Trace 控制台

当前 `/admin/traces` 已使用：

- `/api/v1/traces/stats`；
- `/api/v1/traces/patterns`；
- `/api/v1/traces/token-trends`；
- `/api/v1/traces` 的前 20 条记录。

后端和前端 service 已具备 `GET /api/v1/traces/{correlationId}`，但页面从未调用。现有详情包含 `toolCalls` 与 `tracePayload` 原始 JSON；前端把它们声明为 `unknown`，没有类型化展示。

当前详情 JSON 中 `steps`、`toolCalls` 和 `llmCalls` 是三个平行数组。它们有耗时和顺序信息，但调用项没有 `stepIndex`，因此前端不能安全推断某个 LLM/Tool 调用属于哪一步。设计文档中的 `turnId / parentId / causationId` 跨轮次父子 Trace 尚未进入后端数据模型，本次不宣称已实现。

### 2.3 已实现但尚未形成前端入口的相关能力

| 后端能力 | 当前前端状态 | 本次决策 |
|---|---|---|
| Topic 聚类与主题文章 | 无 service、无 UI | 接入 Chtholly 页面 |
| Agent 记忆统计 | 无 UI | 不进入角色页面；留待 Admin 独立能力审计 |
| Trace 详情 | service 存在、页面未调用 | 建立独立详情页 |
| Trace 状态/用户筛选 | service 支持、页面未暴露 | 在总览列表暴露 |
| Failure Pattern 样本与修复提示 | 仅画柱状图 | 增加样本跳转与提示展示 |
| Seed 审计结果 | 无 Admin 页面 | 不属于本次范围 |
| Mood、Graph、Quality 内部服务 | 无公开读取 API | 不把内部 Service 误当成前端漏接接口 |

## 3. 设计原则

1. **角色页面先建立空间，不建立更多卡片。** 人物、问候和聊天入口构成主舞台，其余内容围绕主舞台形成明确主次。
2. **只有一个首屏主动作。** `/chtholly` 第一屏只出现“和珂朵莉聊天”，不同时出现含义相近的完整会话按钮。
3. **融合体验，不复制工作台。** 轻量聊天在房间内展开；会话管理、设置和长对话继续由 `/agent` 承担。
4. **Trace 先回答两个不同问题。** 总览回答“系统是否健康”，详情回答“这一轮发生了什么”。
5. **只展示可证明的层级。** 单次 Trace 内通过明确字段建立 Step 关联；没有父子合同前不绘制跨轮次任务树。
6. **观测数据默认最小化。** 不保存完整工具输出，只保存限长、脱敏的 observation 摘要。
7. **沿用站点视觉合同。** 保留当前字体、主题 Token、全页背景与通透组件体系，不引入新的页面专属色板。

## 4. Chtholly 页面设计

### 4.1 页面定位

`/chtholly` 是“珂朵莉的房间与轻量协作入口”，不是第二个 Admin Dashboard，也不是完整 Agent 工作台的复制品。

页面同时承担三项职责：

- 让人物成为第一视觉重心；
- 让用户无需跳页即可开始一次轻量问答；
- 展示她最近形成的经历、注意到的社区主题和留下的内容推荐。

### 4.2 桌面端空间结构

```text
┌──────────────────────────────────────────────────────┐
│ 人物舞台                   聊天书桌                   │
│ 珂朵莉插画                 问候 / 输入 / 唯一主按钮   │
└──────────────────────────────────────────────────────┘
          ┌──────────────────────┬──────────────────┐
          │ 她最近在想什么       │ 她注意到的主题   │
          │ 经历时间线，跨两行   ├──────────────────┤
          │                      │ 她留下的推荐     │
          └──────────────────────┴──────────────────┘
```

下方三个区域必须使用严格网格：

- 经历区位于左侧并跨两行；
- 主题与推荐在右侧上下叠放；
- 三块共享同一顶线和底线；
- 卡片间距、标题基线、内边距与圆角统一；
- 不使用人为错位或悬浮制造“设计感”。

### 4.3 移动端顺序

移动端按以下顺序自然折叠：

1. 人物；
2. 问候、输入与“和珂朵莉聊天”；
3. 经历；
4. 主题；
5. 推荐。

移动端不恢复为四张等高长卡。主题和推荐可以各显示较少条目，并提供明确的继续查看入口。

### 4.4 聊天行为

首屏只有一个主按钮“和珂朵莉聊天”。

- 已登录用户点击后，在聊天书桌原位展开轻量对话。
- 未登录用户点击后显示现有登录要求，不建立 WebSocket。
- 轻量对话复用 `AgentChatProvider`、会话状态和 WebSocket 合同，不创建第二套 transport/hook。
- 展开后，聊天面板右上角才出现“全屏打开”，进入 `/agent?session=...`。
- 完整工作台继续负责会话侧栏、设置、Live2D 主舞台和长对话；轻量对话不复制这些功能。

为避免 `/chtholly` 同时挂载页面内 Provider 与全站 Floating Agent Provider，应把该路由标记为独立 Agent surface：

- 新增 Chtholly 路由布局，在该布局内提供唯一的 `AgentChatProvider`；
- 路由策略在 `/chtholly` 禁用 Floating Agent；
- 主动通知仍可在该 Provider 下复用；
- 离开 `/chtholly` 后恢复现有延迟运行时策略。

### 4.5 数据来源

| 区域 | 数据来源 | 降级 |
|---|---|---|
| 经历 | `agentService.experienceTimeline()` | 显示安静空态，不阻塞其他区域 |
| 主题 | 新增 `topicService.list()` 对接 `/api/v1/topics` | 扩展关闭或无数据时隐藏列表并显示轻量空态 |
| 推荐 | 保留现有公开 Feed 读取与文章链接 | 无数据时显示书架空态 |
| 聊天 | 现有 Agent WebSocket 与会话状态 | 未登录、LLM 关闭或连接失败沿用现有错误/降级语义 |

原“她看到的社区”不再独立成块。社区动向由 Topic 名称、摘要、文章数量和关键实体表达，不再展示当前 Feed 长度、标签总数或固定“安静”状态。

### 4.6 组件边界

建议拆分为职责清晰的组件：

- `ChthollyRoomHero`：人物、问候、聊天书桌容器；
- `ChthollyInlineChat`：消费既有 Agent Chat context，只负责轻量展开态；
- `ChthollyExperienceTimeline`：近期、周摘要和归档经历；
- `ChthollyTopicWindow`：Topic 聚类摘要与主题文章入口；
- `ChthollyRecommendationShelf`：公开推荐文章；
- `topicService` 与 Topic types：隔离 API 合同。

页面 Server Component 继续负责可公开预取的数据；仅聊天展开、身份状态和 WebSocket 位于 Client Component。

## 5. Trace 控制台设计

### 5.1 两层信息架构

Trace 使用两个可深链路由：

- `/admin/traces`：运维总览；
- `/admin/traces/[correlationId]`：单次执行详情。

不使用窄侧栏或抽屉承载完整执行链。独立详情页可刷新、可复制链接，也能在移动端按自然文档流阅读。

### 5.2 总览页

总览页保留现有趋势能力，同时补齐：

- 状态卡：总执行、成功率、失败、超时、中止、P95；
- 日期范围作用于统计、失败模式和 Trace 列表；
- 列表筛选：状态、用户 ID、精确 correlation ID；
- 列表分页，而不是固定前 20 条；
- 列表列：开始时间、correlation ID、状态、用户/会话、耗时、步数、Token；
- 点击行进入独立详情页；
- Failure Pattern 展示 `resolutionHint`，并把 `sampleTraceIds` 变成可点击样本。

列表后端查询需要补充可选 `from`、`to` 和精确 `correlationId`，保证日期筛选和总览数据口径一致。所有筛选进入 URL query，刷新后保持。

### 5.3 单次执行详情

详情页头部显示：

- 状态；
- correlation ID；
- 用户与会话；
- 开始/结束时间；
- 总耗时；
- 步数；
- 输入/输出 Token；
- 终止原因与错误摘要。

主体以 Step 为一级，以调用事件为二级：

```text
Trace
├─ Step 0 · action
│  ├─ LLM 调用
│  ├─ Tool 调用
│  │  └─ 脱敏 Observation 摘要
│  └─ Step 耗时
├─ Step 1 · action
│  └─ LLM 调用
└─ Final / Error
```

每个事件展示类型、顺序、耗时、成功状态和允许公开的摘要。原始 JSON 默认折叠，作为诊断退路而不是主要界面。

### 5.4 最小后端合同补充

本次只建立单次 Trace 内部层级，不增加数据库列，也不实现跨轮次父子 Trace。

`trace_payload` JSON 中的调用事件增加：

- `stepIndex`：所属 Step；
- `sequence`：本次执行内的稳定递增顺序；
- Tool 事件增加 `observationSummary`；
- Final 阶段或无法归属 Step 的事件允许 `stepIndex = null`。

运行时在记录 LLM/Tool 调用时显式传入当前 `stepIndex`。服务端详情 DTO 将原始 JSON 归一化为类型化结构：

```text
TraceDetail
├─ summary fields
├─ steps[]
│  ├─ stepIndex / action / duration
│  └─ events[]
└─ unassignedEvents[]
```

旧 Trace 不做数据迁移：

- 能按旧 `steps` 数组展示的内容仍正常展示；
- 缺少 `stepIndex` 的 LLM/Tool 调用进入“未归属事件”；
- 页面明确标记“旧版记录缺少步骤关联”，禁止按数组位置猜测归属。

### 5.5 Observation 安全边界

`observationSummary` 不是完整工具输出。持久化前必须：

- 对 `authorization`、`cookie`、`accessToken`、`refreshToken`、`password` 等敏感键脱敏；
- 限制最大长度；
- 不持久化任意页面全文、完整外部响应或二进制内容；
- 解析失败时保存安全错误类别，不回退保存原始对象。

现有 `toolCalls` 与 `tracePayload` 原始字段可以保留兼容读取，但新 UI 只消费归一化 DTO。

### 5.6 明确不做

本次不包含：

- `turnId / parentId / causationId`；
- 跨轮次、跨后台任务或多 Agent 的父子树；
- Trace 重放、取消或重新执行；
- 完整 Prompt、完整 Context 或完整工具输出展示；
- 统一后台任务编排平台；
- Agent Memory Stats、Seed Audit、Graph 和 Quality 的新管理页面。

## 6. 错误与空态

### 6.1 Chtholly 页面

- 经历、主题和推荐独立请求、独立降级，一个区域失败不清空整页。
- Agent 未登录、LLM 关闭、连接失败和响应错误复用现有状态文案与重试入口。
- 主题扩展关闭时不显示伪造主题，也不把请求失败描述成“社区安静”。

### 6.2 Trace 控制台

- 总览聚合失败与列表失败分区展示，能保留仍然成功的部分。
- 详情不存在时返回明确 404 状态和返回总览入口。
- 新旧 payload 解析失败时展示基础摘要、错误标记与折叠原始数据入口，不让整个详情页崩溃。
- correlation ID、用户 ID 和日期参数必须校验；非法参数不发送请求。

## 7. 可访问性与响应式要求

- 所有聊天、筛选、分页、Trace 行和折叠区域支持键盘操作与可见焦点。
- 状态不能只通过颜色区分，必须同时显示文本或图标。
- Trace 时间线使用语义列表/标题层级，折叠原始 JSON 使用可访问的 disclosure 控件。
- 移动端 Trace 详情采用纵向文档流，元数据先于时间线或以折叠摘要呈现，不产生横向主滚动。
- `prefers-reduced-motion` 下禁用非必要展开动画。

## 8. 测试与验证

### 8.1 前端

- Topic service 的成功、空数据和失败测试；
- Chtholly Server Component 的局部降级测试；
- `/chtholly` 只挂载一个 Agent Provider/连接的策略测试；
- 未登录与已登录聊天入口测试；
- 轻量对话展开和“全屏打开”会话传递测试；
- 桌面端三块区域网格合同与移动端顺序测试；
- Trace 总览筛选、URL 同步、分页和失败样本跳转测试；
- Trace 详情的新旧 payload、未归属事件、错误和空态测试；
- `npm run test:run`、`npm run lint`、`npm run build`。

### 8.2 后端

- `AgentExecutionTrace` 的 `stepIndex`、`sequence` 与 observation 脱敏/截断测试；
- Loop 和 Final 阶段调用归属测试；
- Trace 持久化与归一化详情 DTO 测试；
- 旧 payload 兼容解析测试；
- Trace 列表日期、状态、用户和 correlation ID 组合筛选测试；
- `TraceController` 权限与参数测试；
- `mvn test`。

### 8.3 浏览器验证

- 以桌面和移动视口检查 Chtholly 主舞台、三个区域对齐、内容降级和聊天展开；
- 检查 `/chtholly` 不出现 Floating Agent 重复入口；
- 检查 Trace 总览的筛选、分页、样本跳转和深链刷新；
- 检查新 Trace 的 Step 层级与旧 Trace 的“未归属事件”；
- 检查 Admin 背景下的文字对比度与图表 Tooltip 可读性。

## 9. 分阶段提交边界

实施时按可独立验证的职责拆分提交：

1. Topic API 前端合同与 Chtholly 数据结构；
2. Chtholly 空间布局与严格网格；
3. Chtholly 轻量聊天与运行时去重；
4. Trace 单次执行层级后端合同；
5. Trace 总览筛选与详情路由；
6. Trace 详情层级 UI 与兼容空态。

每个阶段提交前执行新增文件 ignore 审计；发布前以最新 `origin/main` 审计整个任务分支的新增文件。
