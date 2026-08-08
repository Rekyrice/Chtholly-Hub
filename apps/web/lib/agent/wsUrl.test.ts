import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiFetch } from "@/lib/services/apiClient";
import { getAgentWsUrl } from "./wsUrl";

vi.mock("@/lib/auth/tokens", () => ({
  getAccessToken: () => "access-token",
}));

vi.mock("@/lib/services/apiClient", () => ({
  apiFetch: vi.fn(),
}));

describe("getAgentWsUrl", () => {
  beforeEach(() => {
    vi.mocked(apiFetch).mockResolvedValue({
      ticket: "ws-ticket",
      expiresInSeconds: 30,
    });
    vi.stubEnv("NEXT_PUBLIC_WS_URL", "");
    vi.stubEnv("NEXT_PUBLIC_API_SERVER_URL", "");
  });

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  it("uses the public same-origin proxy when production has no explicit WebSocket URL", async () => {
    vi.stubGlobal("window", {
      location: {
        protocol: "http:",
        hostname: "121.199.14.139",
        host: "121.199.14.139",
      },
    });

    await expect(getAgentWsUrl()).resolves.toBe(
      "ws://121.199.14.139/api/v1/agent/ws?ticket=ws-ticket",
    );
  });

  it("uses secure same-origin WebSocket behind an HTTPS reverse proxy", async () => {
    vi.stubGlobal("window", {
      location: {
        protocol: "https:",
        hostname: "hub.rekyrice.com",
        host: "hub.rekyrice.com",
      },
    });

    await expect(getAgentWsUrl()).resolves.toBe(
      "wss://hub.rekyrice.com/api/v1/agent/ws?ticket=ws-ticket",
    );
  });

  it("keeps direct backend WebSocket access for local development", async () => {
    vi.stubGlobal("window", {
      location: {
        protocol: "http:",
        hostname: "localhost",
        host: "localhost:3000",
      },
    });

    await expect(getAgentWsUrl()).resolves.toBe(
      "ws://localhost:8888/api/v1/agent/ws?ticket=ws-ticket",
    );
  });
});
