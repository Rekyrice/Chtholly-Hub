import type { AuthUser, StoredAuth, TokenPair } from "@/lib/types/auth";

export const AUTH_TOKENS_KEY = "chtholly_auth_tokens";

export function getStoredAuth(): StoredAuth | null {
  if (typeof window === "undefined") return null;
  try {
    const raw = localStorage.getItem(AUTH_TOKENS_KEY);
    if (!raw) return null;
    return JSON.parse(raw) as StoredAuth;
  } catch {
    return null;
  }
}

export function saveAuth(token: TokenPair, user?: AuthUser) {
  const payload: StoredAuth = { ...token, user };
  localStorage.setItem(AUTH_TOKENS_KEY, JSON.stringify(payload));
}

export function clearAuth() {
  localStorage.removeItem(AUTH_TOKENS_KEY);
}

export function getAccessToken(): string | null {
  return getStoredAuth()?.accessToken ?? null;
}
