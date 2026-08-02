import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import TraceDetailView from "@/components/site/TraceDetailView";

const traceMocks = vi.hoisted(() => ({ detail: vi.fn() }));

vi.mock("@/lib/services/traceService", () => ({
  traceService: traceMocks,
}));

const nativeTrace = {
  correlationId: "corr-42",
  userId: 7,
  sessionId: "room-session",
  status: "SUCCESS",
  durationMs: 640,
  stepsCount: 2,
  errorMessage: null,
  compatibility: "NATIVE_V4",
  timingAccuracy: "EXACT",
  phases: [
    {
      phase: "accepted",
      events: [{
        id: "event-1",
        sequence: 1,
        stepIndex: null,
        phase: "accepted",
        type: "lifecycle",
        name: "turn_context",
        status: "ACCEPTED",
        startedOffsetMs: 0,
        durationMs: 0,
        attempt: null,
        budgetBeforeMs: null,
        budgetAfterMs: null,
        errorCode: null,
        details: { model: "deepseek-chat", runMode: "candidate" },
      }],
    },
    {
      phase: "llm",
      events: [{
        id: "event-2",
        sequence: 2,
        stepIndex: 0,
        phase: "llm",
        type: "llm",
        name: "llm_call",
        status: "SUCCESS",
        startedOffsetMs: 45,
        durationMs: 220,
        attempt: 1,
        budgetBeforeMs: 29_955,
        budgetAfterMs: 29_735,
        errorCode: null,
        details: {
          purpose: "LOOP_DECISION",
          model: "deepseek-chat",
          inputChars: 5040,
          outputChars: 103,
          firstTokenMs: 88,
          systemPrompt: {
            text: "你是珂朵莉。请先检查证据，再决定是否调用工具。",
            sourceChars: 24,
            sha256: "b".repeat(64),
            truncated: false,
            credentialRedacted: false,
          },
          userPrompt: {
            text: "用户想知道《迷宫饭》的评分、集数、放送时间和角色。",
            sourceChars: 27,
            sha256: "c".repeat(64),
            truncated: false,
            credentialRedacted: false,
          },
          rawOutput: {
            text: "{\"action\":\"bangumi_search\",\"input\":{\"keyword\":\"迷宫饭\"}}",
            sourceChars: 58,
            sha256: "d".repeat(64),
            truncated: false,
            credentialRedacted: false,
          },
        },
      }],
    },
    {
      phase: "tool",
      events: [{
        id: "event-3",
        sequence: 3,
        stepIndex: 0,
        phase: "tool",
        type: "tool",
        name: "bangumi_search",
        status: "SUCCESS",
        startedOffsetMs: 270,
        durationMs: 14,
        attempt: 1,
        budgetBeforeMs: 29_730,
        budgetAfterMs: 29_716,
        errorCode: null,
        details: {
          operation: "subject_search",
          provider: "bangumi",
          sourcePolicy: "public_api",
          sanitizedInput: { keyword: "迷宫饭" },
          outputPreview: "命中《迷宫饭》与角色资料",
          outputSha256: "a".repeat(64),
          outputChars: 1329,
          outputTruncated: false,
          resultCount: 1,
          selectedIds: ["subject:328609"],
          inputSummary: null,
          observationSummary: null,
          rawInput: {
            text: "{\"keyword\":\"迷宫饭\",\"_userQuestion\":\"查询《迷宫饭》的评分和角色\",\"password\":\"[REDACTED]\"}",
            sourceChars: 86,
            sha256: "e".repeat(64),
            truncated: false,
            credentialRedacted: true,
          },
          rawObservation: {
            text: "Bangumi subject 328609：评分 8.1，共 24 集，主要角色包括莱欧斯、玛露西尔和奇尔查克。",
            sourceChars: 55,
            sha256: "f".repeat(64),
            truncated: false,
            credentialRedacted: false,
          },
          attributes: {
            requestedUrl: "https://api.bgm.tv/v0/subjects/328609",
            httpStatus: 200,
            redirectChain: [{ status: 302, url: "https://api.bgm.tv/v0/subjects/328609" }],
            resolvedAddresses: ["104.26.0.1"],
          },
        },
      }],
    },
    {
      phase: "delivery",
      events: [{
        id: "event-4",
        sequence: 4,
        stepIndex: null,
        phase: "delivery",
        type: "lifecycle",
        name: "terminal",
        status: "SUCCESS",
        startedOffsetMs: 630,
        durationMs: 0,
        attempt: null,
        budgetBeforeMs: null,
        budgetAfterMs: null,
        errorCode: null,
        details: {
          terminalType: "final",
          answerChars: 420,
          finalAnswer: {
            text: "《迷宫饭》共 24 集，主要角色包括莱欧斯、玛露西尔和奇尔查克。",
            sourceChars: 34,
            sha256: "1".repeat(64),
            truncated: false,
            credentialRedacted: false,
          },
        },
      }],
    },
  ],
  metadata: {
    runMode: "candidate",
    failureType: "NONE",
    outcomeReason: "NONE",
    llmCallCount: 2,
    toolCallCount: 1,
    components: {
      prompt: "agent-prompt-v2",
      skillSelector: "skill-selector-v1",
      model: "deepseek-chat",
      retrieval: "document-rrf-v1",
      citationValidator: "citation-v1",
      tools: "agent-tool-v2",
      traceSchema: "agent-trace-v4",
    },
    skill: {
      selectionStatus: "SELECTED",
      id: "page-explain",
      version: "v1",
      validationStatus: "VALID",
    },
    retrieval: {
      strategy: "document-rrf-v1",
      statuses: { semantic: "SUCCESS_RESULTS", keyword: "TIMEOUT", entity: "SUCCESS_EMPTY" },
      evidenceCount: 1,
      evidenceSnapshotHash: "snapshot-sha256",
      degraded: true,
      citationValidationStatus: "VALID",
      evidence: [{
        citationId: "E1",
        documentId: "post:42",
        source: "semantic",
        sourceVersion: "content-v3",
        sourceHash: "source-sha256",
      }],
    },
    turn: {
      requestId: "request-42",
      turnId: "turn-42",
      chatSessionId: "room-session",
      connectionId: "connection-7",
      budgetMs: 30_000,
      timeoutStage: null,
      cancelled: false,
      clientDeliveryStatus: "DELIVERED",
      clientTerminalType: "final",
      clientDeliveryCode: null,
    },
    memory: { writeStatus: "COMMITTED", failureCode: null },
    toolPlan: { reason: "selected_skill_bangumi_subject", effectiveTools: ["bangumi_search"] },
    steps: [{ stepIndex: 0, action: "bangumi_search", llmMs: 210, toolMs: 35 }],
    answerTiming: { modelFirstTokenMs: 120, safeAnswerReadyMs: 610, firstClientDeltaMs: 612 },
    capture: {
      level: "ADMIN_FULL",
      policyVersion: "trace-admin-full-v1",
      maxContentFieldChars: 131_072,
      maxTotalContentChars: 2_097_152,
      capturedContentChars: 884,
      truncatedContentFields: 0,
      credentialRedactions: 1,
    },
    completeness: {
      eventLimit: 256,
      droppedEvents: 2,
      truncatedToolOutputs: 1,
      complete: false,
    },
    input: {
      fingerprint: "input-sha256",
      questionFingerprint: "question-sha256",
      pageContextFingerprint: "page-sha256",
      question: {
        text: "查询《迷宫饭》的评分、集数、放送时间并列出角色",
        sourceChars: 25,
        sha256: "2".repeat(64),
        truncated: false,
        credentialRedacted: false,
      },
      pageContext: {
        text: "正在陪读《吃掉红龙这件事，莱欧斯想得比谁都认真》",
        sourceChars: 28,
        sha256: "3".repeat(64),
        truncated: false,
        credentialRedacted: false,
      },
    },
  },
};

describe("TraceDetailView", () => {
  beforeEach(() => {
    traceMocks.detail.mockResolvedValue(nativeTrace);
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it("renders an exact v4 waterfall with all execution stages", async () => {
    render(<TraceDetailView correlationId="corr-42" />);

    const waterfall = await screen.findByRole("region", { name: "Trace 时间瀑布" });
    expect(within(waterfall).getByRole("region", { name: "接收请求阶段" })).toBeInTheDocument();
    expect(within(waterfall).getByRole("region", { name: "模型调用阶段" })).toBeInTheDocument();
    expect(within(waterfall).getByRole("region", { name: "工具执行阶段" })).toBeInTheDocument();
    expect(within(waterfall).getByRole("region", { name: "交付结果阶段" })).toBeInTheDocument();
    expect(within(waterfall).getByRole("article", { name: "模型 llm_call" })).toBeInTheDocument();
    expect(within(waterfall).getByRole("article", { name: "工具 bangumi_search" })).toBeInTheDocument();
    expect(within(waterfall).getByText("+270ms")).toBeInTheDocument();
    expect(screen.getByText("原生 v4 · 精确时间")).toBeInTheDocument();
    expect(traceMocks.detail).toHaveBeenCalledWith("corr-42");
  });

  it("expands full administrator tool input and observation", async () => {
    render(<TraceDetailView correlationId="corr-42" />);

    const toolCard = await screen.findByRole("article", { name: "工具 bangumi_search" });
    fireEvent.click(within(toolCard).getByText("查看诊断"));

    expect(within(toolCard).getByText("bangumi")).toBeInTheDocument();
    expect(within(toolCard).getByText("public_api")).toBeInTheDocument();
    expect(within(toolCard).getByText("迷宫饭")).toBeInTheDocument();
    expect(within(toolCard).getByText("命中《迷宫饭》与角色资料")).toBeInTheDocument();
    expect(within(toolCard).getByText("subject:328609")).toBeInTheDocument();
    expect(within(toolCard).getByText(/"_userQuestion":"查询《迷宫饭》的评分和角色"/)).toBeInTheDocument();
    expect(within(toolCard).getByText(/评分 8\.1，共 24 集/)).toBeInTheDocument();
    expect(within(toolCard).getByText("凭证字段已过滤")).toBeInTheDocument();
    expect(within(toolCard).getByText("执行背景与外部条件")).toBeInTheDocument();
    expect(within(toolCard).getByText(/"requestedUrl": "https:\/\/api\.bgm\.tv/)).toBeInTheDocument();
    expect(within(toolCard).getByText(/"resolvedAddresses"/)).toBeInTheDocument();
    expect(screen.queryByText("查看原始 Trace JSON")).not.toBeInTheDocument();
  });

  it("expands the exact prompts and raw model output used by one call", async () => {
    render(<TraceDetailView correlationId="corr-42" />);

    const llmCard = await screen.findByRole("article", { name: "模型 llm_call" });
    fireEvent.click(within(llmCard).getByText("查看调用指标"));

    expect(within(llmCard).getByText(/请先检查证据，再决定是否调用工具/)).toBeInTheDocument();
    expect(within(llmCard).getByText(/用户想知道《迷宫饭》的评分、集数/)).toBeInTheDocument();
    expect(within(llmCard).getByText(/"action":"bangumi_search"/)).toBeInTheDocument();
  });

  it("renders typed metadata and original administrator turn context", async () => {
    render(<TraceDetailView correlationId="corr-42" />);

    const metadata = await screen.findByRole("region", { name: "Trace 运行元数据" });
    expect(within(metadata).getByText("page-explain · v1")).toBeInTheDocument();
    expect(within(metadata).getByText("semantic: SUCCESS_RESULTS")).toBeInTheDocument();
    expect(within(metadata).getByText("E1 · post:42")).toBeInTheDocument();
    expect(within(metadata).getByText("turn-42")).toBeInTheDocument();
    expect(within(metadata).getByText("DELIVERED / final")).toBeInTheDocument();
    expect(within(metadata).getByText("120ms / 610ms / 612ms")).toBeInTheDocument();
    expect(within(metadata).getByText("agent-trace-v4")).toBeInTheDocument();
    expect(within(metadata).getByText("ADMIN_FULL")).toBeInTheDocument();
    expect(within(metadata).getByText(/查询《迷宫饭》的评分、集数、放送时间/)).toBeInTheDocument();
    expect(within(metadata).getByText(/正在陪读《吃掉红龙这件事/)).toBeInTheDocument();
    expect(within(metadata).getByText("room-session / connection-7")).toBeInTheDocument();
    const steps = within(metadata).getByRole("region", { name: "Agent 循环步骤" });
    expect(within(steps).getByText("Step 1 · bangumi_search")).toBeInTheDocument();
    expect(within(steps).getByText("210ms / 35ms")).toBeInTheDocument();
    expect(within(metadata).getByText("Trace 事件不完整：已丢弃 2 个事件。")).toBeInTheDocument();
    expect(within(metadata).getByText("2 / 256")).toBeInTheDocument();
  });

  it("labels legacy duration-only traces without inventing start offsets", async () => {
    traceMocks.detail.mockResolvedValueOnce({
      ...nativeTrace,
      correlationId: "corr-v3",
      compatibility: "LEGACY_V3",
      timingAccuracy: "DURATION_ONLY",
      phases: [{
        phase: "tool",
        events: [{
          ...nativeTrace.phases[2].events[0],
          id: "legacy-tool-1",
          startedOffsetMs: null,
          details: {
            ...nativeTrace.phases[2].events[0].details,
            sanitizedInput: null,
            outputPreview: null,
            inputSummary: `sha256=${"b".repeat(64)};chars=17`,
            observationSummary: `sha256=${"c".repeat(64)};chars=1329`,
          },
        }],
      }],
    });

    render(<TraceDetailView correlationId="corr-v3" />);

    expect(await screen.findByText("旧版 v3 · 仅调用耗时")).toBeInTheDocument();
    expect(screen.getByText("旧数据没有可靠的开始时间，因此不会推测事件先后间隔。")).toBeInTheDocument();
    expect(screen.queryByText(/\+\d+ms/)).not.toBeInTheDocument();
    expect(screen.getByText(`sha256=${"b".repeat(64)};chars=17`)).toBeInTheDocument();
  });

  it("fails closed for malformed traces", async () => {
    traceMocks.detail.mockResolvedValueOnce({
      correlationId: "corr-malformed",
      userId: null,
      sessionId: null,
      status: "FAILURE",
      durationMs: null,
      stepsCount: 0,
      errorMessage: null,
      compatibility: "MALFORMED",
      timingAccuracy: "NONE",
      phases: [],
      metadata: null,
    });

    render(<TraceDetailView correlationId="corr-malformed" />);

    expect(await screen.findByText("这条 Trace 无法安全解析")).toBeInTheDocument();
    expect(screen.getByText("这条记录无法按 v4 合同组织成可靠链路，请重新触发一次 Agent 任务生成新 Trace。")).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Trace 运行元数据" })).not.toBeInTheDocument();
    expect(screen.queryByText("查看原始 Trace JSON")).not.toBeInTheDocument();
  });

  it("keeps sparse allowlisted collections readable", async () => {
    const sparseTool = {
      ...nativeTrace.phases[2].events[0],
      details: {
        operation: "subject_search",
        provider: "bangumi",
        sourcePolicy: "public_api",
        outputChars: 0,
        outputTruncated: false,
      },
    };
    traceMocks.detail.mockResolvedValueOnce({
      ...nativeTrace,
      phases: [{ phase: "tool", events: [sparseTool] }],
      metadata: {
        ...nativeTrace.metadata,
        retrieval: {
          strategy: "document-rrf-v1",
          evidenceCount: 0,
          degraded: false,
          citationValidationStatus: "NOT_RUN",
        },
        toolPlan: { reason: "not_planned" },
      },
    });

    render(<TraceDetailView correlationId="corr-sparse" />);

    expect(await screen.findByRole("article", { name: "工具 bangumi_search" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Trace 运行元数据" })).toBeInTheDocument();
    expect(screen.getByText("not_planned")).toBeInTheDocument();
  });

  it("shows the current-post retrieval route even when no evidence was produced", async () => {
    traceMocks.detail.mockResolvedValueOnce({
      ...nativeTrace,
      phases: [],
      metadata: {
        ...nativeTrace.metadata,
        retrieval: {
          strategy: "current-post-rag-v1",
          statuses: { current_post: "SUCCESS_EMPTY" },
          evidenceCount: 0,
          degraded: false,
          citationValidationStatus: "NOT_RUN",
          evidence: [],
        },
      },
    });

    render(<TraceDetailView correlationId="current-post-empty" />);

    const metadata = await screen.findByRole("region", { name: "Trace 运行元数据" });
    expect(within(metadata).getByText("检索路由")).toBeInTheDocument();
    expect(within(metadata).getByText("current_post: SUCCESS_EMPTY")).toBeInTheDocument();
    expect(within(metadata).getByText("0")).toBeInTheDocument();
  });
});
