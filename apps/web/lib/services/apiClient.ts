/** 相对路径，经 Next.js rewrites 代理到后端 */
const BASE_URL = "";

import { clearAuth, getAccessToken, getStoredAuth } from "@/lib/auth/tokens";

export type ApiFetchOptions = {
  method?: string;
  headers?: Record<string, string>;
  body?: unknown;
  accessToken?: string | null;
  signal?: AbortSignal;
  skipAuthRefresh?: boolean;
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

type ApiFetchInternalOptions = ApiFetchOptions & {
  /** 401 后已清 token 并重试，避免循环 */
  _retriedWithoutAuth?: boolean;
};

const MAX_ERROR_MESSAGE_LENGTH = 240;
const HTML_SNIFF_PREFIX_LENGTH = 1024;

function safeErrorMessageText(value: unknown): string | null {
  if (typeof value !== "string") return null;

  const normalized = value.replace(/\s+/gu, " ").trim();
  if (!normalized) return null;
  if (normalized.length <= MAX_ERROR_MESSAGE_LENGTH) return normalized;

  let end = MAX_ERROR_MESSAGE_LENGTH - 1;
  const endsWithHighSurrogate = /[\uD800-\uDBFF]/u.test(normalized.charAt(end - 1));
  const continuesWithLowSurrogate = /[\uDC00-\uDFFF]/u.test(normalized.charAt(end));
  if (endsWithHighSurrogate && continuesWithLowSurrogate) {
    end -= 1;
  }

  return `${normalized.slice(0, end)}…`;
}

function isHtmlErrorBody(contentType: string | null, rawText: string): boolean {
  const mediaType = contentType?.split(";", 1)[0].trim().toLowerCase();
  if (mediaType === "text/html" || mediaType === "application/xhtml+xml") {
    return true;
  }

  let prefix = rawText.slice(0, HTML_SNIFF_PREFIX_LENGTH).trimStart();
  let consumedLeadingMetadata = false;
  while (prefix) {
    if (prefix.startsWith("<!--")) {
      const commentEnd = prefix.indexOf("-->");
      if (commentEnd === -1) return true;
      consumedLeadingMetadata = true;
      prefix = prefix.slice(commentEnd + 3).trimStart();
      continue;
    }

    if (/^<\?xml(?=\s|\?>)/iu.test(prefix)) {
      const declarationEnd = prefix.indexOf("?>");
      if (declarationEnd === -1) return true;
      consumedLeadingMetadata = true;
      prefix = prefix.slice(declarationEnd + 2).trimStart();
      continue;
    }

    break;
  }

  if (consumedLeadingMetadata && !prefix) return true;

  return /^(?:<!doctype\s+html(?=\s|>)|<(?:html|head|body|title|meta|base|link|style|script|noscript|main|section|article|header|footer|nav|div|h[1-6]|p|pre|table|form|iframe)(?=[\s/>]))/iu.test(
    prefix,
  );
}

function resolveApiErrorMessage(
  status: number,
  hasAccessToken: boolean,
  contentType: string | null,
  rawText: string,
  errorData: unknown,
  parsedJson: boolean,
): string {
  if (status === 401 && hasAccessToken) {
    return "登录已过期，请重新登录";
  }

  if (!isHtmlErrorBody(contentType, rawText)) {
    const dataMessage =
      typeof errorData === "object" && errorData !== null && "message" in errorData
        ? safeErrorMessageText((errorData as { message?: unknown }).message)
        : null;
    const message = dataMessage ?? (parsedJson ? null : safeErrorMessageText(rawText));
    if (message) return message;
  }

  return `请求失败（${status}）`;
}

let refreshPromise: Promise<string | null> | null = null;

async function refreshAccessTokenOnce(): Promise<string | null> {
  if (!refreshPromise) {
    refreshPromise = import("@/lib/services/authService")
      .then(({ authService }) => authService.refreshToken())
      .then((token) => token.accessToken)
      .catch(() => {
        clearAuth();
        if (typeof window !== "undefined") {
          window.location.assign("/login");
        }
        return null;
      })
      .finally(() => {
        refreshPromise = null;
      });
  }
  return refreshPromise;
}

export async function apiFetch<TResponse>(
  path: string,
  options: ApiFetchOptions = {},
): Promise<TResponse> {
  return apiFetchInternal<TResponse>(path, options);
}

async function apiFetchInternal<TResponse>(
  path: string,
  options: ApiFetchInternalOptions,
): Promise<TResponse> {
  const {
    method = "GET",
    headers = {},
    body,
    accessToken,
    signal,
    skipAuthRefresh = false,
    _retriedWithoutAuth = false,
  } = options;

  const isFormData = typeof FormData !== "undefined" && body instanceof FormData;

  const mergedHeaders: Record<string, string> = {
    ...(isFormData ? {} : { "Content-Type": "application/json" }),
    ...headers,
  };

  // accessToken 为 null 时不附带 Authorization；undefined 时从本地存储读取（含过期校验）
  const token = accessToken === undefined ? getAccessToken() : accessToken;
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
        : `${process.env.API_SERVER_URL ?? "http://localhost:8888"}${path}`;
  const response = await fetch(url, {
    method,
    headers: mergedHeaders,
    body: isFormData ? (body as FormData) : body ? JSON.stringify(body) : undefined,
    signal,
    credentials: "include",
  });

  if (response.status === 401 && !skipAuthRefresh && !_retriedWithoutAuth && accessToken !== null) {
    if (getStoredAuth()?.refreshToken) {
      const nextAccessToken = await refreshAccessTokenOnce();
      if (nextAccessToken) {
        return apiFetchInternal<TResponse>(path, {
          ...options,
          accessToken: nextAccessToken,
          skipAuthRefresh: true,
          _retriedWithoutAuth: true,
        });
      }
    }
    clearAuth();
    if (isIdempotent) {
      return apiFetchInternal<TResponse>(path, {
        ...options,
        accessToken: null,
        _retriedWithoutAuth: true,
      });
    }
  }

  if (!response.ok) {
    const contentType = response.headers.get("content-type");
    let rawText = "";
    try {
      rawText = await response.text();
    } catch {
      rawText = "";
    }
    let errorData: unknown = rawText;
    let parsedJson = false;
    if (rawText) {
      try {
        errorData = JSON.parse(rawText);
        parsedJson = true;
      } catch {
        // 保留原始文本
      }
    }
    const message = resolveApiErrorMessage(
      response.status,
      Boolean(token),
      contentType,
      rawText,
      errorData,
      parsedJson,
    );
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
