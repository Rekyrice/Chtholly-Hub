/** 相对路径，经 Next.js rewrites 代理到后端 */
const BASE_URL = "";

import { AUTH_TOKENS_KEY } from "@/lib/auth/tokens";

export type ApiFetchOptions = {
  method?: string;
  headers?: Record<string, string>;
  body?: unknown;
  accessToken?: string | null;
  signal?: AbortSignal;
};

export class ApiError extends Error {
  readonly status: number;
  readonly data: unknown;

  constructor(status: number, message: string, data: unknown) {
    super(message);
    this.status = status;
    this.data = data;
  }
}

export async function apiFetch<TResponse>(
  path: string,
  options: ApiFetchOptions = {},
): Promise<TResponse> {
  const { method = "GET", headers = {}, body, accessToken, signal } = options;

  const isFormData = typeof FormData !== "undefined" && body instanceof FormData;

  const getStoredAccessToken = (): string | null => {
    if (typeof window === "undefined") return null;
    try {
      const raw = localStorage.getItem(AUTH_TOKENS_KEY);
      if (!raw) return null;
      const parsed = JSON.parse(raw) as { accessToken?: string };
      return parsed.accessToken ?? null;
    } catch {
      return null;
    }
  };

  const mergedHeaders: Record<string, string> = {
    ...(isFormData ? {} : { "Content-Type": "application/json" }),
    ...headers,
  };

  // accessToken 为 null 时不附带 Authorization；undefined 时从本地存储读取
  const token = accessToken === undefined ? getStoredAccessToken() : accessToken;
  if (token) {
    mergedHeaders.Authorization = `Bearer ${token}`;
  }

  const methodUpper = method.toUpperCase();
  const isIdempotent =
    methodUpper === "GET" || methodUpper === "HEAD" || methodUpper === "OPTIONS";
  if (!isIdempotent && typeof document !== "undefined") {
    try {
      const cookies = document.cookie ?? "";
      const match = cookies.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
      const xsrfToken = match ? decodeURIComponent(match[1]) : null;
      if (xsrfToken && !("X-XSRF-TOKEN" in mergedHeaders)) {
        mergedHeaders["X-XSRF-TOKEN"] = xsrfToken;
      }
    } catch {
      // 忽略 Cookie 读取失败
    }
  }

  // 浏览器走 Next rewrites；服务端直连 Spring Boot，避免 Node 中相对路径无效
  const url =
    path.startsWith("http")
      ? path
      : typeof window !== "undefined"
        ? BASE_URL
          ? `${BASE_URL}${path}`
          : path
        : `${process.env.API_SERVER_URL ?? "http://localhost:8080"}${path}`;
  const response = await fetch(url, {
    method,
    headers: mergedHeaders,
    body: isFormData ? (body as FormData) : body ? JSON.stringify(body) : undefined,
    signal,
    credentials: "include",
  });

  if (!response.ok) {
    let rawText = "";
    try {
      rawText = await response.text();
    } catch {
      rawText = "";
    }
    let errorData: unknown = rawText;
    if (rawText) {
      try {
        errorData = JSON.parse(rawText);
      } catch {
        // 保留原始文本
      }
    }
    const message =
      typeof errorData === "object" &&
      errorData !== null &&
      "message" in errorData
        ? (errorData as { message: string }).message
        : rawText || `请求失败：${response.status}`;
    throw new ApiError(response.status, message, errorData);
  }

  if (response.status === 204) {
    return undefined as TResponse;
  }

  const contentType = response.headers.get("content-type");
  if (contentType?.includes("application/json")) {
    return (await response.json()) as TResponse;
  }

  return (await response.text()) as TResponse;
}
