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

function isTokenValid(token: unknown, expiresAt: unknown): boolean {
  if (typeof token !== "string" || !token.trim()) return false;
  if (typeof expiresAt !== "string" || !expiresAt) return false;
  const expiresAtMs = new Date(expiresAt).getTime();
  return Number.isFinite(expiresAtMs) && Date.now() < expiresAtMs - 30_000;
}

/** 访问令牌是否在有效期内（留 30s 缓冲避免临界点失败） */
export function isAccessTokenValid(auth: StoredAuth | null): boolean {
  if (!auth || typeof auth !== "object") return false;
  return isTokenValid(auth.accessToken, auth.accessTokenExpiresAt);
}

/** 刷新令牌是否仍能恢复登录态 */
export function isRefreshTokenValid(auth: StoredAuth | null): boolean {
  if (!auth || typeof auth !== "object") return false;
  return isTokenValid(auth.refreshToken, auth.refreshTokenExpiresAt);
}

/** 当前浏览器是否仍持有可用或可恢复的登录态 */
export function isAuthSessionValid(auth: StoredAuth | null): boolean {
  return isAccessTokenValid(auth) || isRefreshTokenValid(auth);
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
  if (auth && !isAuthSessionValid(auth)) {
    clearAuth();
    return true;
  }
  return false;
}

export function isLoggedIn(): boolean {
  return isAuthSessionValid(getStoredAuth());
}
