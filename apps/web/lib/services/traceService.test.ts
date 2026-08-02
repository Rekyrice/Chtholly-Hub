import { beforeEach, describe, expect, it, vi } from "vitest";
import { apiFetch } from "@/lib/services/apiClient";
import { traceService } from "@/lib/services/traceService";

vi.mock("@/lib/services/apiClient", () => ({
  apiFetch: vi.fn(),
}));

describe("traceService", () => {
  beforeEach(() => {
    vi.mocked(apiFetch).mockReset();
    vi.mocked(apiFetch).mockResolvedValue({ items: [], page: 0, size: 20, total: 0, hasMore: false });
  });

  it("passes list hierarchy filters without changing exact correlation IDs", async () => {
    await traceService.list({
      page: 2,
      size: 10,
      status: "FAILURE",
      userId: 7,
      from: "2026-07-01",
      to: "2026-07-09",
      correlationId: "corr-exact",
    });

    expect(apiFetch).toHaveBeenCalledWith(
      "/api/v1/traces?page=2&size=10&status=FAILURE&userId=7&from=2026-07-01&to=2026-07-09&correlationId=corr-exact",
    );
  });

  it("normalizes missing safe projection fields without recreating raw payloads", async () => {
    vi.mocked(apiFetch).mockResolvedValue({
      correlationId: "legacy-trace",
      userId: 7,
      sessionId: "legacy-session",
      status: "SUCCESS",
      durationMs: 120,
      stepsCount: 1,
      errorMessage: null,
      compatibility: "UNSUPPORTED",
      timingAccuracy: "NONE",
    });

    const detail = await traceService.detail("legacy-trace");

    expect(detail.phases).toEqual([]);
    expect(detail.metadata).toBeNull();
    expect(detail).not.toHaveProperty("tracePayload");
    expect(detail).not.toHaveProperty("toolCalls");
  });
});
