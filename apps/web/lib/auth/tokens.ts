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
  if (!auth || typeof auth !== "object") return false;
  if (typeof auth.accessToken !== "string" || !auth.accessToken.trim()) return false;
  if (typeof auth.accessTokenExpiresAt !== "string" || !auth.accessTokenExpiresAt) return false;
  if (typeof auth.refreshToken !== "string" || !auth.refreshToken.trim()) return false;
  if (typeof auth.refreshTokenExpiresAt !== "string" || !auth.refreshTokenExpiresAt) return false;
  const expiresAt = new Date(auth.accessTokenExpiresAt).getTime();
  const refreshExpiresAt = new Date(auth.refreshTokenExpiresAt).getTime();
  if (!Number.isFinite(expiresAt) || !Number.isFinite(refreshExpiresAt)) return false;
  return Date.now() < expiresAt - 30_000;
}

/** 读取访问令牌（无副作用，可在任意时机调用） */
export function getAccessToken(): string | null {
  const auth = getStoredAuth();
  if (!auth || !isAccessTokenValid(auth)) {
    return null;
  }
  return auth.accessToken;
}

/** 清除已过期的本地登录态（仅应在 effect / 事件处理器中调用） */
export function purgeExpiredAuth(): boolean {
  const auth = getStoredAuth();
  if (auth?.accessToken && !isAccessTokenValid(auth)) {
    clearAuth();
    return true;
  }
  return false;
}

export function isLoggedIn(): boolean {
  return getAccessToken() !== null;
}
