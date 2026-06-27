import { getAccessToken } from "@/lib/auth/tokens";

/** 构建 Agent WebSocket URL（开发环境直连 :8888）。 */
export function getAgentWsUrl(): string | null {
  const token = getAccessToken();
  if (!token) return null;

  const explicit = process.env.NEXT_PUBLIC_WS_URL;
  if (explicit) {
    return `${explicit.replace(/\/$/, "")}/api/v1/agent/ws?token=${encodeURIComponent(token)}`;
  }

  const api = process.env.NEXT_PUBLIC_API_SERVER_URL;
  if (api) {
    const wsBase = api.replace(/^http/i, "ws");
    return `${wsBase.replace(/\/$/, "")}/api/v1/agent/ws?token=${encodeURIComponent(token)}`;
  }

  if (typeof window === "undefined") return null;
  const host = window.location.hostname;
  return `ws://${host}:8888/api/v1/agent/ws?token=${encodeURIComponent(token)}`;
}
