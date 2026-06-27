import { apiFetch } from "@/lib/services/apiClient";
import { getAccessToken } from "@/lib/auth/tokens";

type WsTicketResponse = {
  ticket: string;
  expiresInSeconds: number;
};

/** 向后端申请 WebSocket 握手 ticket（短生命周期，替代 JWT）。 */
export async function fetchAgentWsTicket(): Promise<string | null> {
  const token = getAccessToken();
  if (!token) return null;
  try {
    const resp = await apiFetch<WsTicketResponse>("/api/v1/agent/ws-ticket", {
      method: "POST",
      accessToken: token,
    });
    return resp.ticket ?? null;
  } catch {
    return null;
  }
}

function buildWsBaseUrl(): string | null {
  const explicit = process.env.NEXT_PUBLIC_WS_URL;
  if (explicit) {
    return `${explicit.replace(/\/$/, "")}/api/v1/agent/ws`;
  }

  const api = process.env.NEXT_PUBLIC_API_SERVER_URL;
  if (api) {
    const wsBase = api.replace(/^http/i, "ws");
    return `${wsBase.replace(/\/$/, "")}/api/v1/agent/ws`;
  }

  if (typeof window === "undefined") return null;
  const host = window.location.hostname;
  return `ws://${host}:8888/api/v1/agent/ws`;
}

/** 构建 Agent WebSocket URL（需先换取 ticket）。 */
export async function getAgentWsUrl(): Promise<string | null> {
  const ticket = await fetchAgentWsTicket();
  if (!ticket) return null;

  const base = buildWsBaseUrl();
  if (!base) return null;

  return `${base}?ticket=${encodeURIComponent(ticket)}`;
}
