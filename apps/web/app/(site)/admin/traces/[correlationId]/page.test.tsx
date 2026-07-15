import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import TraceDetailPage from "@/app/(site)/admin/traces/[correlationId]/page";

vi.mock("@/components/site/TraceDetailView", () => ({
  default: ({ correlationId }: { correlationId: string }) => (
    <div data-testid="trace-detail-view">{correlationId}</div>
  ),
}));

describe("TraceDetailPage", () => {
  it("awaits the Next 16 route params before rendering the client detail", async () => {
    render(await TraceDetailPage({ params: Promise.resolve({ correlationId: "corr-42" }) }));

    expect(screen.getByTestId("trace-detail-view")).toHaveTextContent("corr-42");
  });
});
