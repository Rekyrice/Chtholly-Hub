import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { hydrateRoot } from "react-dom/client";
import { renderToString } from "react-dom/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import TraceDashboardPage from "@/app/(site)/admin/traces/page";

const traceMocks = vi.hoisted(() => ({
  getTraceStats: vi.fn(async () => ({ executionTrend: [], tokenTrend: [] })),
  getTracePatterns: vi.fn(async () => []),
  getTokenTrends: vi.fn(async () => []),
  list: vi.fn(async () => ({ items: [] })),
}));

vi.mock("@/lib/services/traceService", () => ({
  traceService: traceMocks,
}));

vi.mock("recharts", () => {
  const Container = ({ children }: { children?: React.ReactNode }) => <div>{children}</div>;
  const Empty = () => null;
  return {
    Area: Empty,
    AreaChart: Container,
    Bar: Empty,
    BarChart: Container,
    CartesianGrid: Empty,
    Line: Empty,
    LineChart: Container,
    ResponsiveContainer: Container,
    Tooltip: Empty,
    XAxis: Empty,
    YAxis: Empty,
  };
});

describe("TraceDashboardPage hydration", () => {
  beforeEach(() => {
    traceMocks.getTraceStats.mockResolvedValue({
      totalExecutions: 10,
      successCount: 6,
      failureCount: 2,
      timeoutCount: 1,
      abortedCount: 1,
      successRate: 60,
      avgDurationMs: 1200,
      p95DurationMs: 2600,
      executionTrend: [],
      tokenTrend: [],
      topFailurePatterns: [],
    });
    traceMocks.getTracePatterns.mockResolvedValue([
      {
        patternKey: "tool:search:timeout",
        occurrenceCount: 2,
        sampleTraceIds: ["trace-123"],
        resolutionHint: "检查工具超时",
      },
    ]);
    traceMocks.getTokenTrends.mockResolvedValue([]);
    traceMocks.list.mockResolvedValue({
      items: [
        {
          correlationId: "trace-123",
          userId: 7,
          sessionId: "session",
          status: "FAILURE",
          durationMs: 1200,
          startedAt: "2026-07-13T00:00:00Z",
          finishedAt: "2026-07-13T00:00:01Z",
          stepsCount: 2,
          inputTokens: 20,
          outputTokens: 10,
          errorMessage: "timeout",
        },
      ],
      page: 0,
      size: 10,
      total: 21,
      hasMore: true,
    });
    window.history.replaceState(null, "", "/admin/traces");
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    document.body.innerHTML = "";
  });

  it("hydrates a deterministic server snapshot before applying the query range", async () => {
    const browserWindow = window;
    browserWindow.history.replaceState(null, "", "/admin/traces?from=2024-01-02&to=2024-01-09");
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => undefined);

    vi.stubGlobal("window", undefined);
    const serverHtml = renderToString(<TraceDashboardPage />);
    vi.stubGlobal("window", browserWindow);

    expect(serverHtml).not.toContain("2024-01-02");
    const container = document.createElement("div");
    container.innerHTML = serverHtml;
    document.body.appendChild(container);
    const serverDomBeforeHydration = container.innerHTML;
    const root = hydrateRoot(container, <TraceDashboardPage />);

    expect(container.innerHTML).toBe(serverDomBeforeHydration);
    await act(async () => {});
    await waitFor(() => {
      const inputs = container.querySelectorAll<HTMLInputElement>('input[type="date"]');
      expect(inputs[0]?.value).toBe("2024-01-02");
      expect(inputs[1]?.value).toBe("2024-01-09");
    });

    const hydrationErrors = consoleError.mock.calls.filter((call) =>
      call.some((value) => /hydration|didn't match/i.test(String(value))),
    );
    expect(hydrationErrors).toEqual([]);

    act(() => root.unmount());
    container.remove();
    consoleError.mockRestore();
  });

  it("shows explicit status layers, filters and links to trace details", async () => {
    render(<TraceDashboardPage />);

    expect(await screen.findByText("失败执行")).toBeInTheDocument();
    expect(screen.getByText("超时执行")).toBeInTheDocument();
    expect(screen.getByText("中止执行")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "trace-123" })).toHaveAttribute(
      "href",
      "/admin/traces/trace-123",
    );
    expect(screen.getByRole("link", { name: "查看样本 trace-123" })).toHaveAttribute(
      "href",
      "/admin/traces/trace-123",
    );

    fireEvent.change(screen.getByLabelText("Trace 状态"), { target: { value: "TIMEOUT" } });

    await waitFor(() => {
      expect(traceMocks.list).toHaveBeenLastCalledWith(
        expect.objectContaining({ status: "TIMEOUT", page: 0 }),
      );
    });
    expect(screen.getByRole("button", { name: "下一页" })).toBeEnabled();
  });
});
