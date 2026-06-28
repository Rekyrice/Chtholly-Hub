export type IdentifierType = "PHONE" | "EMAIL" | "HANDLE";
export type VerificationScene = "REGISTER" | "LOGIN" | "RESET_PASSWORD";

export type AuthUser = {
  id: number;
  nickname: string;
  avatar?: string | null;
  phone?: string | null;
  handle?: string | null;
  birthday?: string | null;
  school?: string | null;
  bio?: string | null;
  gender?: string | null;
  tagJson?: string | null;
};

export type TokenPair = {
  accessToken: string;
  accessTokenExpiresAt: string;
  refreshToken: string;
  refreshTokenExpiresAt: string;
};

export type AuthResponse = {
  user: AuthUser;
  token: TokenPair;
};

export type SendCodeResponse = {
  identifier: string;
  scene: VerificationScene;
  expireSeconds: number;
};

export type StoredAuth = TokenPair & {
  user?: AuthUser;
};

export type RegisterHandleBody = {
  handle: string;
  password: string;
  nickname?: string;
  agreeTerms: boolean;
};
