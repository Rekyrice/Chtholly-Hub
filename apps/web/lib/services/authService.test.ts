import { afterEach, describe, expect, it, vi } from "vitest";
import { getStoredAuth, saveAuth } from "@/lib/auth/tokens";
import { authService } from "@/lib/services/authService";
import type { TokenPair } from "@/lib/types/auth";

const tokenPair = (accessToken: string): TokenPair => ({
  accessToken,
  accessTokenExpiresAt: "2999-01-01T00:00:00Z",
  refreshToken: `refresh-${accessToken}`,
  refreshTokenExpiresAt: "2999-01-02T00:00:00Z",
});

function jsonResponse(body: object): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

describe("authService.me", () => {
  afterEach(() => {
    localStorage.clear();
    vi.unstubAllGlobals();
  });

  it("does not let a late response for token A overwrite the stored user for token B", async () => {
    const pending = new Map<string, (response: Response) => void>();
    vi.stubGlobal(
      "fetch",
      vi.fn((_url: string | URL | Request, init?: RequestInit) => {
        const authorization = new Headers(init?.headers).get("Authorization") ?? "";
        return new Promise<Response>((resolve) => pending.set(authorization, resolve));
      }),
    );

    saveAuth(tokenPair("token-a"));
    const requestA = authService.me("token-a");
    saveAuth(tokenPair("token-b"));
    const requestB = authService.me("token-b");

    pending.get("Bearer token-b")?.(jsonResponse({ id: 2, nickname: "User B" }));
    await requestB;
    pending.get("Bearer token-a")?.(jsonResponse({ id: 1, nickname: "User A" }));
    await requestA;

    expect(getStoredAuth()?.accessToken).toBe("token-b");
    expect(getStoredAuth()?.user).toMatchObject({ id: 2, nickname: "User B" });
  });

  it("captures the current token before an implicit request and compares it before writing", async () => {
    let resolveRequest!: (response: Response) => void;
    vi.stubGlobal(
      "fetch",
      vi.fn(
        () =>
          new Promise<Response>((resolve) => {
            resolveRequest = resolve;
          }),
      ),
    );

    saveAuth(tokenPair("token-a"));
    const requestA = authService.me();
    saveAuth(tokenPair("token-b"));
    resolveRequest(jsonResponse({ id: 1, nickname: "User A" }));
    await requestA;

    expect(getStoredAuth()?.accessToken).toBe("token-b");
    expect(getStoredAuth()?.user).toBeUndefined();
  });
});
