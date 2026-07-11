import { clearAuth, getStoredAuth, saveAuth } from "@/lib/auth/tokens";
import type {
  AuthResponse,
  AuthUser,
  IdentifierType,
  SendCodeResponse,
  TokenPair,
  VerificationScene,
} from "@/lib/types/auth";
import { isValidPhone, resolvePasswordLoginType } from "@/lib/validation/auth";
import { apiFetch } from "./apiClient";

const AUTH_PREFIX = "/api/v1/auth";

/** 后端 zhId 字段映射为 handle */
function mapUser(raw: Record<string, unknown>): AuthUser {
  return {
    id: Number(raw.id),
    nickname: String(raw.nickname ?? ""),
    avatar: (raw.avatar as string | null) ?? null,
    phone: (raw.phone as string | null) ?? null,
    handle: (raw.zhId as string | null) ?? null,
    birthday: (raw.birthday as string | null) ?? null,
    school: (raw.school as string | null) ?? null,
    bio: (raw.bio as string | null) ?? null,
    gender: (raw.gender as string | null) ?? null,
    tagJson: (raw.tagJson as string | null) ?? null,
    role: (raw.role as string | null) ?? null,
  };
}

function mapAuthResponse(res: AuthResponse): AuthResponse {
  const user = mapUser(res.user as unknown as Record<string, unknown>);
  saveAuth(res.token, user);
  return { user, token: res.token };
}

export const authService = {
  sendCode: (params: {
    identifier: string;
    identifierType?: IdentifierType;
    scene: VerificationScene;
  }) =>
    apiFetch<SendCodeResponse>(`${AUTH_PREFIX}/send-code`, {
      method: "POST",
      body: {
        identifier: params.identifier,
        identifierType: params.identifierType ?? "PHONE",
        scene: params.scene,
      },
      accessToken: null,
    }),

  loginWithCode: async (phone: string, code: string) => {
    const res = await apiFetch<AuthResponse>(`${AUTH_PREFIX}/login`, {
      method: "POST",
      body: {
        identifierType: "PHONE",
        identifier: phone,
        code,
      },
      accessToken: null,
    });
    return mapAuthResponse(res);
  },

  loginWithPassword: async (identifier: string, password: string) => {
    const trimmed = identifier.trim();
    const identifierType = resolvePasswordLoginType(trimmed);
    const res = await apiFetch<AuthResponse>(`${AUTH_PREFIX}/login`, {
      method: "POST",
      body: {
        identifierType,
        identifier: identifierType === "PHONE" ? trimmed : trimmed,
        password,
      },
      accessToken: null,
    });
    return mapAuthResponse(res);
  },

  register: async (phone: string, code: string) => {
    const res = await apiFetch<AuthResponse>(`${AUTH_PREFIX}/register`, {
      method: "POST",
      body: {
        identifierType: "PHONE",
        identifier: phone,
        code,
        agreeTerms: true,
      },
      accessToken: null,
    });
    return mapAuthResponse(res);
  },

  registerWithHandle: async (params: {
    handle: string;
    password: string;
    nickname?: string;
  }) => {
    const res = await apiFetch<AuthResponse>(`${AUTH_PREFIX}/register`, {
      method: "POST",
      body: {
        identifierType: "HANDLE",
        handle: params.handle.trim(),
        password: params.password,
        nickname: params.nickname?.trim() || undefined,
        agreeTerms: true,
      },
      accessToken: null,
    });
    return mapAuthResponse(res);
  },

  me: async (accessToken?: string) => {
    const requestToken = accessToken ?? getStoredAuth()?.accessToken;
    const raw = await apiFetch<Record<string, unknown>>(`${AUTH_PREFIX}/me`, {
      accessToken: requestToken,
    });
    const user = mapUser(raw);
    const stored = getStoredAuth();
    if (requestToken && stored?.accessToken === requestToken) {
      saveAuth(
        {
          accessToken: stored.accessToken,
          accessTokenExpiresAt: stored.accessTokenExpiresAt,
          refreshToken: stored.refreshToken,
          refreshTokenExpiresAt: stored.refreshTokenExpiresAt,
        },
        user,
      );
    }
    return user;
  },

  refreshToken: async () => {
    const stored = getStoredAuth();
    if (!stored?.refreshToken) {
      throw new Error("没有可用的刷新令牌");
    }
    const token = await apiFetch<TokenPair>(`${AUTH_PREFIX}/token/refresh`, {
      method: "POST",
      body: { refreshToken: stored.refreshToken },
      accessToken: null,
      skipAuthRefresh: true,
    });
    saveAuth(token, stored.user);
    if (typeof window !== "undefined") {
      window.dispatchEvent(new Event("chtholly-auth-change"));
    }
    return token;
  },

  resetPassword: (phone: string, code: string, newPassword: string) =>
    apiFetch<void>(`${AUTH_PREFIX}/password/reset`, {
      method: "POST",
      body: {
        identifierType: "PHONE",
        identifier: phone,
        code,
        newPassword,
      },
      accessToken: null,
    }),

  logout: async () => {
    const refresh = getStoredAuth()?.refreshToken;
    try {
      if (refresh) {
        await apiFetch<void>(`${AUTH_PREFIX}/logout`, {
          method: "POST",
          body: { refreshToken: refresh },
          accessToken: null,
        });
      }
    } finally {
      clearAuth();
    }
  },
};

export { isValidPhone };
