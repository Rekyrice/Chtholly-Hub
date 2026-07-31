import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  AUTH_TOKENS_KEY,
  getAccessToken,
  isLoggedIn,
  purgeExpiredAuth,
} from "@/lib/auth/tokens";
import type { StoredAuth } from "@/lib/types/auth";

const NOW = new Date("2026-08-01T00:00:00.000Z");

function storeAuth(overrides: Partial<StoredAuth> = {}) {
  const auth: StoredAuth = {
    accessToken: "access-token",
    accessTokenExpiresAt: "2026-08-01T00:15:00.000Z",
    refreshToken: "refresh-token",
    refreshTokenExpiresAt: "2026-08-08T00:00:00.000Z",
    ...overrides,
  };
  localStorage.setItem(AUTH_TOKENS_KEY, JSON.stringify(auth));
}

describe("stored auth lifetime", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
    localStorage.clear();
  });

  afterEach(() => {
    vi.useRealTimers();
    localStorage.clear();
  });

  it("keeps a recoverable login when only the access token has expired", () => {
    storeAuth({ accessTokenExpiresAt: "2026-07-31T23:59:00.000Z" });

    expect(getAccessToken()).toBeNull();
    expect(isLoggedIn()).toBe(true);
    expect(purgeExpiredAuth()).toBe(false);
    expect(localStorage.getItem(AUTH_TOKENS_KEY)).not.toBeNull();
  });

  it("clears auth after both access and refresh tokens have expired", () => {
    storeAuth({
      accessTokenExpiresAt: "2026-07-31T23:59:00.000Z",
      refreshTokenExpiresAt: "2026-07-31T23:59:00.000Z",
    });

    expect(isLoggedIn()).toBe(false);
    expect(purgeExpiredAuth()).toBe(true);
    expect(localStorage.getItem(AUTH_TOKENS_KEY)).toBeNull();
  });
});
