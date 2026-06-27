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
  if (typeof window === "undefined") return;
  localStorage.removeItem(AUTH_TOKENS_KEY);
  window.dispatchEvent(new Event("chtholly-auth-change"));
}

/** 访问令牌是否在有效期内（留 30s 缓冲避免临界点失败） */
export function isAccessTokenValid(auth: StoredAuth | null): boolean {
  if (!auth?.accessToken) return false;
  if (!auth.accessTokenExpiresAt) return true;
  const expiresAt = new Date(auth.accessTokenExpiresAt).getTime();
  if (Number.isNaN(expiresAt)) return true;
  return Date.now() < expiresAt - 30_000;
}

export function getAccessToken(): string | null {
  const auth = getStoredAuth();
  if (!auth || !isAccessTokenValid(auth)) {
    if (auth?.accessToken) clearAuth();
    return null;
  }
  return auth.accessToken;
}

export function isLoggedIn(): boolean {
  return getAccessToken() !== null;
}
