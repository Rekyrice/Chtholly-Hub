# Agent 回答渲染与步数预算实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 统一所有 Agent 对话入口的完成态 Markdown，收敛内部 final 动作协议，并将通用 Agent 默认步数提高到 10 而不放宽 Skill 和整轮超时边界。

**Architecture:** 保留“JSON 决策循环 → 独立最终答案生成 → 客户端展示”的两阶段架构。前端在流式阶段渲染纯文本、终态渲染安全 GFM；后端提示只要求 `{"action":"final"}`，解析继续容忍旧 `answer` 字段；全局步数与 Skill 步数继续取较小值。

**Tech Stack:** Java 21、Spring Boot 3.2.4、JUnit 5、AssertJ、Next.js 16、React、TypeScript、Vitest、Testing Library、react-markdown、remark-gfm。

---

### Task 1: 统一共享 Agent 面板的 Markdown 偏好

**Files:**
- Modify: `apps/web/components/agent/AgentChatPanel.test.tsx`
- Modify: `apps/web/components/agent/AgentChatPanel.tsx:147-155`

- [ ] **Step 1: 写共享入口的失败测试**

让测试状态可以切换 Markdown 偏好，并捕获传给 `AgentMessageList` 的 `rich`：

```tsx
const agentState = vi.hoisted(() => ({
  busy: false,
  streaming: false,
  input: "",
  richMarkdown: true,
  sendMessage: vi.fn(),
}));
const messageListState = vi.hoisted(() => ({
  compactAssistantMessages: undefined as boolean | undefined,
  rich: undefined as boolean | undefined,
}));

vi.mock("@/components/agent/AgentMessageList", () => ({
  default: (props: { compactAssistantMessages?: boolean; rich?: boolean }) => {
    messageListState.compactAssistantMessages = props.compactAssistantMessages;
    messageListState.rich = props.rich;
    return <div />;
  },
}));

it.each(["float", "workspace", "room"] as const)(
  "uses the Markdown preference for the %s variant",
  (variant) => {
    agentState.richMarkdown = true;
    const { rerender } = render(<AgentChatPanel variant={variant} />);
    expect(messageListState.rich).toBe(true);

    agentState.richMarkdown = false;
    rerender(<AgentChatPanel variant={variant} />);
    expect(messageListState.rich).toBe(false);
  },
);
```

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```powershell
cd apps/web
npx vitest run components/agent/AgentChatPanel.test.tsx
```

Expected: `float` 和 `room` 在偏好为 `true` 时仍收到 `rich=false`，测试失败。

- [ ] **Step 3: 最小修改共享面板**

将 `AgentChatPanel` 的参数改为：

```tsx
<AgentMessageList
  messages={messages}
  busy={busy}
  showSteps={showSteps}
  liveSteps={liveSteps}
  rich={richMarkdown}
  mangaLayout={isWorkspace}
  showAssistantAvatar={showAssistantAvatar}
  compactAssistantMessages={variant === "float"}
  scrollContainerRef={scrollContainerRef}
  onSuggestion={/* 保留现有回调 */}
/>
```

只解除 Markdown 与 `isWorkspace` 的耦合；漫画布局、头像和折叠规则不变。

- [ ] **Step 4: 运行测试并确认 GREEN**

Run:

```powershell
npx vitest run components/agent/AgentChatPanel.test.tsx
```

Expected: 通过。

- [ ] **Step 5: 提交该职责**

```powershell
git add apps/web/components/agent/AgentChatPanel.tsx apps/web/components/agent/AgentChatPanel.test.tsx
git commit -m "fix: 统一 Agent 面板 Markdown 偏好"
```

### Task 2: 统一流式纯文本与完成态 Markdown

**Files:**
- Modify: `apps/web/components/agent/AgentMessageList.test.tsx`
- Modify: `apps/web/components/site/PostQnA.test.tsx`
- Modify: `apps/web/components/site/PostQnA.tsx:127-141`

- [ ] **Step 1: 写共享消息列表状态切换和安全失败测试**

在 `AgentMessageList.test.tsx` 增加：

```tsx
it("keeps streaming Markdown as text and renders it after completion", () => {
  const content = "核心观点：\n\n- **认真**面对证据";
  const { rerender } = render(
    <AgentMessageList
      messages={[{ id: "reply", role: "assistant", content, streaming: true }]}
      busy
      showSteps={false}
      liveSteps={[]}
      rich
    />,
  );

  expect(screen.queryByRole("list")).not.toBeInTheDocument();
  expect(screen.queryByText("认真")).not.toBeInTheDocument();

  rerender(
    <AgentMessageList
      messages={[{ id: "reply", role: "assistant", content, streaming: false }]}
      busy={false}
      showSteps={false}
      liveSteps={[]}
      rich
    />,
  );

  expect(screen.getByRole("list")).toBeInTheDocument();
  expect(screen.getByText("认真").tagName).toBe("STRONG");
});

it("does not execute raw HTML in a completed Markdown reply", () => {
  const { container } = render(
    <AgentMessageList
      messages={[{
        id: "safe",
        role: "assistant",
        content: "<script>alert('x')</script>\n\n**安全内容**",
      }]}
      busy={false}
      showSteps={false}
      liveSteps={[]}
      rich
    />,
  );

  expect(container.querySelector("script")).toBeNull();
  expect(screen.getByText("安全内容").tagName).toBe("STRONG");
});
```

现有实现应已满足这两项；若测试直接通过，保留它们作为既有安全合同，不为制造 RED 而改生产代码。

- [ ] **Step 2: 写文章问答流式状态的失败测试**

在 `PostQnA.test.tsx` 增加一个可控完成时机的生成器：

```tsx
function pausedAnswer(answer: string) {
  let finish!: () => void;
  const gate = new Promise<void>((resolve) => {
    finish = resolve;
  });
  return {
    finish,
    async *stream(): AsyncGenerator<PostQaStreamEvent> {
      yield { type: "delta", data: answer };
      await gate;
      yield { type: "done" };
    },
  };
}

it("renders streamed Markdown as text and enriches it only after done", async () => {
  const answer = pausedAnswer("核心观点：\n\n- **认真**面对证据");
  qaStream.mockImplementation(() => answer.stream());
  render(<PostQnA postId="42" />);

  ask("核心观点是什么？");
  await screen.findByText(/\*\*认真\*\*/u);
  expect(screen.queryByRole("list")).not.toBeInTheDocument();

  answer.finish();
  expect(await screen.findByRole("list")).toBeInTheDocument();
  expect(screen.getByText("认真").tagName).toBe("STRONG");
});

it("keeps interrupted Markdown as plain text", async () => {
  qaStream.mockImplementation(() => interruptedAnswer("- **尚未完成**"));
  render(<PostQnA postId="42" />);

  ask("继续呢？");
  await screen.findByText(/\*\*尚未完成\*\*/u);
  expect(screen.queryByRole("list")).not.toBeInTheDocument();
  expect(screen.queryByText("尚未完成")).not.toBeInTheDocument();
});
```

- [ ] **Step 3: 运行文章问答测试并确认 RED**

Run:

```powershell
npx vitest run components/site/PostQnA.test.tsx
```

Expected: 流式内容已被现有 `ReactMarkdown` 解析成列表和强调节点，测试失败。

- [ ] **Step 4: 最小实现状态分流**

在 `PostQnA.tsx` 中先计算展示文本，再按终态选择渲染器：

```tsx
const content = turn.answer
  || (turn.status === "streaming" ? "珂朵莉正在想……" : "没有收到回答。");

{turn.status === "done" ? (
  <ReactMarkdown remarkPlugins={[remarkGfm]}>{content}</ReactMarkdown>
) : (
  <span className="whitespace-pre-wrap">{content}</span>
)}
```

保持现有 `QnATurn.status`、错误文案、历史筛选和流式协议不变。

- [ ] **Step 5: 运行定向前端测试并确认 GREEN**

Run:

```powershell
npx vitest run components/agent/AgentMessageList.test.tsx components/site/PostQnA.test.tsx
```

Expected: 通过。

- [ ] **Step 6: 提交该职责**

```powershell
git add apps/web/components/agent/AgentMessageList.test.tsx apps/web/components/site/PostQnA.tsx apps/web/components/site/PostQnA.test.tsx
git commit -m "fix: 完成后渲染 Agent Markdown"
```

### Task 3: 收敛 final 动作协议并保持兼容

**Files:**
- Modify: `apps/server/src/test/java/com/chtholly/agent/context/PromptTailRendererTest.java`
- Modify: `apps/server/src/test/java/com/chtholly/agent/context/ContextEngineTest.java`
- Modify: `apps/server/src/test/java/com/chtholly/agent/runtime/AgentLoopExecutorTest.java`
- Modify: `apps/server/src/main/java/com/chtholly/agent/context/PromptTailRenderer.java:39-40`
- Modify: `apps/server/src/main/resources/agent-domain.yml:5-8`
- Modify: `apps/server/src/main/java/com/chtholly/agent/AgentAction.java`
- Modify: `apps/server/src/main/java/com/chtholly/agent/runtime/AgentLoopExecutor.java:681-690`

- [ ] **Step 1: 先把协议断言改为新合同**

将 Prompt 和 Context 测试中的期望改为：

```java
.contains("{\"action\":\"final\"}")
.doesNotContain("{\"action\":\"final\",\"answer\"");
```

同时保留 `AgentLoopExecutorTest.compoundBangumiQuestionAcceptsFinalActionWithLiteralLineBreaks`，证明旧式带多行 `answer` 的 final 仍兼容；增加或保留标准 `{"action":"final"}` 进入 `FINAL_READY` 的断言。

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```powershell
cd ../server
mvn -q '-Dtest=PromptTailRendererTest,ContextEngineTest,AgentLoopExecutorTest' test
```

Expected: Prompt 仍包含旧的 `answer` 示例，协议断言失败。

- [ ] **Step 3: 更新两处提示来源**

`PromptTailRenderer` 改为：

```java
.append("输出格式：只输出单个 JSON 对象；调用工具用 {\"action\":\"工具名\",\"input\":{...}}，")
.append("可以回答时用 {\"action\":\"final\"}");
```

`agent-domain.yml` 改为：

```yaml
结束回答示例：{"action":"final"}
```

- [ ] **Step 4: 删除未使用的内部 answer 字段**

将动作模型收敛为：

```java
public record AgentAction(String action, JsonNode input) {
    public boolean isFinal() {
        return "final".equalsIgnoreCase(action);
    }
}
```

解析器只读取有效字段：

```java
JsonNode input = node.path("input");
return new AgentAction(action, input.isMissingNode() ? null : input);
```

Jackson 仍允许 JSON 中存在额外 `answer`，因此旧输出继续被识别为 final；不要删掉 `AgentJsonExtractor` 的控制字符兼容修复。

- [ ] **Step 5: 运行定向后端测试并确认 GREEN**

Run:

```powershell
mvn -q '-Dtest=PromptTailRendererTest,ContextEngineTest,AgentJsonExtractorTest,AgentLoopExecutorTest' test
```

Expected: 通过。

- [ ] **Step 6: 提交该职责**

```powershell
git add apps/server/src/main/java/com/chtholly/agent/AgentAction.java apps/server/src/main/java/com/chtholly/agent/context/PromptTailRenderer.java apps/server/src/main/java/com/chtholly/agent/runtime/AgentLoopExecutor.java apps/server/src/main/resources/agent-domain.yml apps/server/src/test/java/com/chtholly/agent/context/PromptTailRendererTest.java apps/server/src/test/java/com/chtholly/agent/context/ContextEngineTest.java apps/server/src/test/java/com/chtholly/agent/runtime/AgentLoopExecutorTest.java
git commit -m "refactor: 收敛 Agent 结束动作协议"
```

### Task 4: 将通用 Agent 默认步数提高到 10

**Files:**
- Create: `apps/server/src/test/java/com/chtholly/agent/config/AgentPropertiesTest.java`
- Modify: `apps/server/src/test/java/com/chtholly/agent/ChthollyAgentTest.java`
- Modify: `apps/server/src/main/java/com/chtholly/agent/config/AgentProperties.java:16-17`
- Modify: `apps/server/src/main/resources/application.yml:144-152`
- Modify: `.env.example:68`
- Modify: `.env.prod.example:46`

- [ ] **Step 1: 写默认值和配置回退的失败测试**

新增 `AgentPropertiesTest`：

```java
package com.chtholly.agent.config;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPropertiesTest {

    @Test
    void defaultsToTenDecisionStepsAcrossJavaAndApplicationYaml() throws Exception {
        assertThat(new AgentProperties().getMaxSteps()).isEqualTo(10);
        try (InputStream stream = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream("application.yml"))) {
            String yaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(yaml).contains("max-steps: ${AGENT_MAX_STEPS:10}");
        }
    }
}
```

在 `ChthollyAgentTest` 增加一项明确的双层预算测试：先执行 `properties.setMaxSteps(10)`，选择测试 Skill（`maxSteps=3`），捕获 `AgentLoopRequest` 并断言 `request.maxSteps()==3`。

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```powershell
mvn -q '-Dtest=AgentPropertiesTest,ChthollyAgentTest' test
```

Expected: Java 默认和 YAML 回退仍为 5，`AgentPropertiesTest` 失败；Skill 收紧断言保持通过。

- [ ] **Step 3: 同步四个默认配置入口**

应用以下值：

```java
private int maxSteps = 10;
```

```yaml
max-steps: ${AGENT_MAX_STEPS:10}
```

```dotenv
AGENT_MAX_STEPS=10
```

两份 `.env` 示例都更新为 10。四份 Skill YAML 不改，所有 timeout 配置不改。

- [ ] **Step 4: 运行定向测试和配置一致性检查并确认 GREEN**

Run:

```powershell
mvn -q '-Dtest=AgentPropertiesTest,ChthollyAgentTest' test
cd ../..
Select-String -Path .env.example,.env.prod.example,apps/server/src/main/resources/application.yml -Pattern 'AGENT_MAX_STEPS'
```

Expected: 测试通过，三个配置文件均显示默认值 10。

- [ ] **Step 5: 提交该职责**

```powershell
git add .env.example .env.prod.example apps/server/src/main/java/com/chtholly/agent/config/AgentProperties.java apps/server/src/main/resources/application.yml apps/server/src/test/java/com/chtholly/agent/config/AgentPropertiesTest.java apps/server/src/test/java/com/chtholly/agent/ChthollyAgentTest.java
git commit -m "feat: 提高 Agent 通用决策步数"
```

### Task 5: 文档同步、全量验证与范围审计

**Files:**
- Modify: `docs/architecture/agent-system.md`
- Modify: `docs/development/configuration.md`

- [ ] **Step 1: 更新稳定知识文档**

在 Agent 系统文档中明确：

```text
内部决策只使用 JSON 动作，final 仅作为结束信号；最终答案由独立调用生成 Markdown。通用默认最大步数为 10，Skill 可用更小的 maxSteps 收紧，所有执行仍受整轮 deadline 约束。
```

在配置文档的 `AGENT_MAX_STEPS` 项中将默认值更新为 10，并说明 Skill 取较小值。

- [ ] **Step 2: 运行前端完整验证**

Run:

```powershell
cd apps/web
npm run test:run
npm run build
```

Expected: 两个命令退出码均为 0。

- [ ] **Step 3: 运行后端完整验证**

Run:

```powershell
cd ../server
mvn test
```

Expected: 退出码为 0，Surefire 报告中 failures=0、errors=0。

- [ ] **Step 4: 做提交前仓库审计**

Run:

```powershell
cd ../..
git diff --check
git status --short
git diff --cached --name-only --diff-filter=A | git check-ignore -v --no-index --stdin
```

Expected: `git diff --check` 无输出；忽略规则审计无输出；状态只包含本计划范围。

- [ ] **Step 5: 提交文档并复核分支范围**

```powershell
git add docs/architecture/agent-system.md docs/development/configuration.md
git commit -m "docs: 更新 Agent 回答与步数边界"
git diff --check origin/main...HEAD
git status --short
```

Expected: 工作树干净，分支相对 `origin/main` 只包含本次 Agent 修复、设计、计划与实施提交。
