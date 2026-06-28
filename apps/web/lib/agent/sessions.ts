import type { ChatMessage } from "@/lib/types/agent";

export type AgentSessionRecord = {
  id: string;
  title: string;
  messages: ChatMessage[];
  createdAt: number;
  updatedAt: number;
};

const STORAGE_KEY = "chtholly-agent-sessions";
const ACTIVE_KEY = "chtholly-agent-active-session";
const SHOW_STEPS_KEY = "chtholly-agent-show-steps";

export function createSessionId() {
  return `sess-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

export function sessionTitleFromMessages(messages: ChatMessage[]) {
  const firstUser = messages.find((m) => m.role === "user");
  if (!firstUser?.content) return "新对话";
  const text = firstUser.content.trim();
  return text.length > 24 ? `${text.slice(0, 24)}…` : text;
}

export function loadStoredSessions(): AgentSessionRecord[] {
  if (typeof window === "undefined") return [];
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw) as AgentSessionRecord[];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

export function saveStoredSessions(sessions: AgentSessionRecord[]) {
  if (typeof window === "undefined") return;
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(sessions));
  } catch {
    // 存储失败时忽略
  }
}

export function loadActiveSessionId(): string | null {
  if (typeof window === "undefined") return null;
  return localStorage.getItem(ACTIVE_KEY);
}

export function saveActiveSessionId(id: string) {
  if (typeof window === "undefined") return;
  localStorage.setItem(ACTIVE_KEY, id);
}

/** 推理步骤展示偏好，默认关闭以减少干扰 */
export function loadShowStepsPreference(): boolean {
  if (typeof window === "undefined") return false;
  try {
    const raw = localStorage.getItem(SHOW_STEPS_KEY);
    if (raw === null) return false;
    return raw === "true";
  } catch {
    return false;
  }
}

export function saveShowStepsPreference(show: boolean) {
  if (typeof window === "undefined") return;
  try {
    localStorage.setItem(SHOW_STEPS_KEY, String(show));
  } catch {
    // 存储失败时忽略
  }
}
