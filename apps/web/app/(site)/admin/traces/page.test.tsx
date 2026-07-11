import { act, waitFor } from "@testing-library/react";
import { hydrateRoot } from "react-dom/client";
import { renderToString } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";
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
  afterEach(() => {
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
});
