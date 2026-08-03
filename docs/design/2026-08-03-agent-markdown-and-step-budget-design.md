# Agent 回答渲染与步数预算设计

## 背景

当前 Agent 将内部决策与最终表达分成两个阶段：ReAct 循环要求模型输出 JSON 动作，循环结束后再由独立的最终回答调用生成自然语言。这个边界是正确的，但现状有三个不一致：

1. 决策提示仍要求 `{"action":"final","answer":"占位"}`，诱导模型把完整答案写入实际不会被使用的 `answer` 字段，增加 token、非法换行和解析失败风险。
2. 完整 Agent 工作台支持 Markdown，浮窗和房间模式却被 `isWorkspace` 条件强制降级为纯文本；文章问答又在流式阶段持续解析未完成的 Markdown。
3. 通用 Agent 的默认最大决策步数为 5，对多工具研究偏紧；但直接将所有 Skill 统一放宽到 10 会增加延迟和无效调用。

## 目标

- JSON 只承担内部动作协议，最终用户回答始终是自然语言 Markdown。
- 工作台、浮窗、房间和文章问答在回答完成后使用一致的安全 Markdown 展示。
- 将通用 Agent 的默认最大决策步数提高到 10，同时保留 Skill 的更小任务级上限和现有整轮超时。
- 保持现有模型输出兼容性、Trace 可诊断性和 WebSocket 事件协议不变。

## 非目标

- 本次不迁移到模型供应商原生 Tool Calling 或 Structured Output。
- 本次不修改端口、模型、单次调用超时、整轮超时或工具超时。
- 本次不机械提高 `page-explain`、`evidence-outline`、`draft-fact-check` 和 `draft-edit` 的 Skill 上限。
- 本次不引入通用的重复调用或停滞终止状态；后续先依据 Trace 判断是否存在稳定重复模式。

## 方案

### 1. 最终回答统一使用安全 Markdown

共享的 `AgentChatPanel` 不再用 `isWorkspace` 决定富文本能力，而是让工作台、浮窗和房间都遵循现有 `richMarkdown` 用户偏好。该偏好默认开启，用户关闭后仍可选择纯文本。

`AgentMessageList` 保持现有两阶段渲染：消息处于 `streaming` 时使用 `whitespace-pre-wrap` 展示原始增量文本；收到 `final` 后切换为 `react-markdown` 与 `remark-gfm`。这样不会在未闭合列表、表格或代码围栏上频繁重建 DOM。

文章详情的 `PostQnA` 使用相同策略：流式阶段按纯文本展示，完成态再交给现有 Markdown 渲染器。中断回答保持纯文本，不把可能不完整的 Markdown 解释成结构化 DOM。

安全边界沿用当前实现：不启用 `rehype-raw`，不使用 `dangerouslySetInnerHTML`，因此模型生成的原始 HTML 不会作为可执行 DOM 注入。此次不新增 Markdown 插件或外部资源能力。

### 2. 收敛内部 `final` 动作协议

系统提示和动态尾部提示统一把结束动作写为：

```json
{"action":"final"}
```

工具动作继续使用：

```json
{"action":"工具名","input":{"参数名":"参数值"}}
```

`final.answer` 当前没有参与最终回答生成，因此从新的协议示例和内部 `AgentAction` 模型中移除。解析器仍接受带额外 `answer` 字段的旧输出，并继续保留控制字符容错；额外字段不会阻止一个合法的 `final` 动作。模型原始输出仍在解析前写入管理员 Trace，最终 Markdown 则继续作为独立的 `FINAL_ANSWER` 调用与终态事件记录。

### 3. 使用双层步数预算

通用默认值由 5 调整为 10，并同步以下配置入口，避免代码默认、运行配置和部署示例漂移：

- `AgentProperties.maxSteps`
- `application.yml` 中 `AGENT_MAX_STEPS` 的回退值
- `.env.example`
- `.env.prod.example`

选中 Skill 时仍使用：

```text
effectiveMaxSteps = min(globalMaxSteps, skillMaxSteps)
```

因此普通多工具对话最多可进行 10 次模型决策，而页面解释、资料大纲、事实核查和草稿编辑仍分别受现有 4、5、5、1 步上限约束。全局 60 秒整轮截止、30 秒单次模型调用和 30 秒工具调用上限保持不变；提高步数只允许在剩余时间内进行更多快速决策，不延长请求生命周期。

## 数据流

```text
用户问题
  → Agent 决策 JSON（工具动作或 final）
  → 工具 Observation / Evidence
  → {"action":"final"}
  → 独立生成并校验最终 Markdown
  → WebSocket/文章问答流
  → 流式纯文本
  → 完成态安全 Markdown
```

## 错误与兼容策略

- 旧模型返回 `{"action":"final","answer":"..."}`：按 `final` 正常结束，忽略冗余字段。
- 决策 JSON 中字符串含未转义控制字符：沿用当前局部修复后解析，Trace 保留原始输出。
- 真正结构损坏或非法转义：继续记录 `parse_error` 并在剩余预算内重试。
- 达到有效步数上限：继续返回现有 `MAX_STEPS` 状态及实际上限，不改变前端错误协议。
- 达到整轮截止时间：仍以 `TURN_TIMEOUT` 终止；10 步配置不得绕过截止时间。
- Markdown 流中断：保留已收到文本，以纯文本错误态展示，不解析不完整结构。

## 测试设计

### 前端

- `AgentChatPanelTest` 验证 workspace、float 和 room 在偏好开启时都传递 `rich=true`，关闭时都为 `false`。
- `AgentMessageListTest` 验证同一消息在流式阶段不生成列表/强调 DOM，完成后切换为 GFM Markdown。
- `PostQnATest` 验证流式阶段为纯文本、完成后为 Markdown、中断内容不被当作完成态富文本。
- 安全回归验证原始 HTML 不会生成可执行脚本节点。

### 后端

- `PromptTailRendererTest` 和上下文测试验证新协议只提示 `{"action":"final"}`。
- 循环测试验证标准 `final` 进入 `FINAL_READY`，并保留带旧 `answer` 字段的兼容用例。
- 配置测试验证 Java 默认值、YAML 回退值和两份环境示例均为 10。
- 编排测试验证 Skill 的较小上限仍会收紧全局 10 步。
- 保留上一轮《迷宫饭》多工具与多行旧输出回归，证明兼容解析仍可完成。

## 验收标准

1. 工作台、浮窗、房间和文章问答的已完成回答都能正确展示 GFM 列表、强调和代码块。
2. 流式和中断回答保持纯文本，完成事件到达后才切换 Markdown。
3. 新决策提示不再要求 `answer` 字段，旧式 final JSON 仍可解析。
4. 未选择 Skill 的普通对话默认最多 10 步；选中 Skill 时仍采用其现有较小上限。
5. 60 秒整轮超时和现有 WebSocket、Trace、Evidence、Memory 协议均不变化。
6. 前端全量测试与生产构建、后端全量测试通过。
