import { beforeEach, describe, expect, it, vi } from "vitest";
import { apiFetch } from "@/lib/services/apiClient";
import { agentService } from "@/lib/services/agentService";

vi.mock("@/lib/services/apiClient", () => ({
  apiFetch: vi.fn(),
}));

describe("agentService", () => {
  beforeEach(() => {
    vi.mocked(apiFetch).mockReset().mockResolvedValue(undefined);
  });

  it("clears one authenticated session memory through the idempotent HTTP endpoint", async () => {
    await agentService.clearSessionMemory("sess-owned_1");

    expect(apiFetch).toHaveBeenCalledWith(
      "/api/v1/agent/sessions/sess-owned_1/memory",
      { method: "DELETE" },
    );
  });

  it("encodes the session id as a path segment", async () => {
    await agentService.clearSessionMemory("sess/unsafe");

    expect(apiFetch).toHaveBeenCalledWith(
      "/api/v1/agent/sessions/sess%2Funsafe/memory",
      { method: "DELETE" },
    );
  });
});
