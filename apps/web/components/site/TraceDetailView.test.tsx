import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import TraceDetailView from "@/components/site/TraceDetailView";

const traceMocks = vi.hoisted(() => ({ detail: vi.fn() }));

vi.mock("@/lib/services/traceService", () => ({
  traceService: traceMocks,
}));

describe("TraceDetailView", () => {
  beforeEach(() => {
    traceMocks.detail.mockResolvedValue({
      correlationId: "corr-42",
      userId: 7,
      sessionId: "room-session",
      status: "SUCCESS",
      durationMs: 240,
      stepsCount: 2,
      errorMessage: null,
      toolCalls: [],
      tracePayload: {
        terminatedBy: "final_answer",
        failureType: "NONE",
        runMode: "candidate",
        unapprovedDiagnostic: "must-not-project",
        llmCallCount: 2,
        toolCalls: [{ name: "fulltext_search" }],
        components: {
          prompt: "agent-prompt-v1",
          skillSelector: "skill-selector-v1",
          model: "deepseek-chat",
          retrieval: "document-rrf-v1",
          tools: "agent-tool-v1",
          traceSchema: "agent-trace-v1",
        },
        skill: {
          id: "page-explain",
          version: "v1",
          selectionStatus: "SELECTED",
          validationStatus: "VALID",
        },
        retrieval: {
          strategy: "document-rrf-v1",
          statuses: {
            semantic: "SUCCESS_RESULTS",
            keyword: "TIMEOUT",
            entity: "SUCCESS_EMPTY",
          },
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
      },
      steps: [
        {
          stepIndex: 0,
          action: "fulltext_search",
          llmDurationMs: 40,
          toolDurationMs: 80,
          events: [
            {
              sequence: 1,
              stepIndex: 0,
              type: "llm",
              name: "model",
              durationMs: 40,
              success: null,
              inputSummary: null,
              observationSummary: null,
              inputChars: 120,
              outputChars: 32,
              firstTokenMs: null,
            },
            {
              sequence: 2,
              stepIndex: 0,
              type: "tool",
              name: "fulltext_search",
              durationMs: 80,
              success: true,
              inputSummary: "{\"query\":\"trace\"}",
              observationSummary: "找到 3 篇文章",
              inputChars: null,
              outputChars: null,
              firstTokenMs: null,
            },
          ],
        },
        {
          stepIndex: 1,
          action: "final_answer",
          llmDurationMs: 120,
          toolDurationMs: 0,
          events: [],
        },
      ],
      unassignedEvents: [
        {
          sequence: null,
          stepIndex: null,
          type: "tool",
          name: "legacy_tool",
          durationMs: 12,
          success: false,
          inputSummary: null,
          observationSummary: null,
          inputChars: null,
          outputChars: null,
          firstTokenMs: null,
        },
      ],
    });
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it("renders step, LLM, tool, observation and final layers", async () => {
    render(<TraceDetailView correlationId="corr-42" />);

    expect(await screen.findByText("Step 1")).toBeInTheDocument();
    expect(screen.getByText("LLM 决策")).toBeInTheDocument();
    expect(screen.getByText("工具 · fulltext_search")).toBeInTheDocument();
    expect(screen.getByText("找到 3 篇文章")).toBeInTheDocument();
    expect(screen.getByText("最终回答")).toBeInTheDocument();
    expect(screen.getByText("未分配事件（旧数据）")).toBeInTheDocument();
    expect(traceMocks.detail).toHaveBeenCalledWith("corr-42");
  });

  it("renders versioned Trace metadata through the summary allowlist", async () => {
    render(<TraceDetailView correlationId="corr-42" />);

    const metadata = await screen.findByRole("region", { name: "Trace 运行元数据" });
    expect(metadata).toBeInTheDocument();
    expect(screen.getByText("candidate")).toBeInTheDocument();
    expect(screen.getByText("NONE")).toBeInTheDocument();
    expect(screen.getByText("2 / 1")).toBeInTheDocument();
    expect(screen.getByText("deepseek-chat")).toBeInTheDocument();
    expect(screen.getByText("page-explain · v1")).toBeInTheDocument();
    expect(screen.getByText("SELECTED / VALID")).toBeInTheDocument();
    expect(screen.getByText("semantic: SUCCESS_RESULTS")).toBeInTheDocument();
    expect(screen.getByText("keyword: TIMEOUT")).toBeInTheDocument();
    expect(screen.getByText("E1 · post:42")).toBeInTheDocument();
    expect(screen.getByText("VALID", { selector: "dd" })).toBeInTheDocument();
    expect(within(metadata).queryByText("must-not-project")).not.toBeInTheDocument();
  });

  it("keeps legacy or malformed payloads readable without guessing metadata", async () => {
    traceMocks.detail.mockResolvedValueOnce({
      correlationId: "corr-legacy",
      userId: null,
      sessionId: null,
      status: "FAILURE",
      durationMs: null,
      stepsCount: 0,
      errorMessage: null,
      toolCalls: null,
      tracePayload: {
        terminatedBy: "error",
        components: "invalid",
        skill: [],
        retrieval: { statuses: "invalid", evidence: ["invalid"] },
        failureType: { unexpected: true },
      },
      steps: [],
      unassignedEvents: [],
    });

    render(<TraceDetailView correlationId="corr-legacy" />);

    expect(await screen.findByText("这条 Trace 没有可解析的 Step 层级。")).toBeInTheDocument();
    expect(screen.queryByText("Trace 运行元数据")).not.toBeInTheDocument();
    expect(screen.getAllByText("-").length).toBeGreaterThanOrEqual(3);
    expect(screen.getByText("查看原始 Trace JSON")).toBeInTheDocument();
  });
});
