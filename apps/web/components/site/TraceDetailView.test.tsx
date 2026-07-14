import { cleanup, render, screen } from "@testing-library/react";
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
      tracePayload: { terminatedBy: "final_answer" },
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
});
