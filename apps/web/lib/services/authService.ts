import { clearAuth, getStoredAuth, saveAuth } from "@/lib/auth/tokens";
import type {
  AuthResponse,
  AuthUser,
  IdentifierType,
  SendCodeResponse,
  VerificationScene,
} from "@/lib/types/auth";
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

  me: async () => {
    const raw = await apiFetch<Record<string, unknown>>(`${AUTH_PREFIX}/me`);
    return mapUser(raw);
  },

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
