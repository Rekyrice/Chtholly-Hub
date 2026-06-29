import type { ChatMessage } from "@/lib/types/agent";

export type AgentSessionRecord = {
  id: string;
  title: string;
  messages: ChatMessage[];
  createdAt: number;
  updatedAt: number;
  /** 用户手动重命名后，不再被首条消息自动覆盖标题 */
  titleLocked?: boolean;
};

const STORAGE_KEY = "chtholly-agent-sessions";
const ACTIVE_KEY = "chtholly-agent-active-session";
const SHOW_STEPS_KEY = "chtholly-agent-show-steps";
const WORKSPACE_DARK_KEY = "chtholly-agent-workspace-dark";
const RICH_MARKDOWN_KEY = "chtholly-agent-rich-markdown";
const STAGE_COLLAPSED_KEY = "chtholly-agent-stage-collapsed";
const SESSIONS_COLLAPSED_KEY = "chtholly-agent-sessions-collapsed";
const LIVE2D_BG_KEY = "chtholly-agent-live2d-bg";

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

export function loadWorkspaceDarkPreference(): boolean {
  if (typeof window === "undefined") return false;
  try {
    return localStorage.getItem(WORKSPACE_DARK_KEY) === "true";
  } catch {
    return false;
  }
}

export function saveWorkspaceDarkPreference(dark: boolean) {
  if (typeof window === "undefined") return;
  try {
    localStorage.setItem(WORKSPACE_DARK_KEY, String(dark));
  } catch {
    // 存储失败时忽略
  }
}

export function loadRichMarkdownPreference(): boolean {
  if (typeof window === "undefined") return true;
  try {
    const raw = localStorage.getItem(RICH_MARKDOWN_KEY);
    if (raw === null) return true;
    return raw === "true";
  } catch {
    return true;
  }
}

export function saveRichMarkdownPreference(rich: boolean) {
  if (typeof window === "undefined") return;
  try {
    localStorage.setItem(RICH_MARKDOWN_KEY, String(rich));
  } catch {
    // 存储失败时忽略
  }
}

export function loadStageCollapsedPreference(): boolean {
  if (typeof window === "undefined") return false;
  try {
    return localStorage.getItem(STAGE_COLLAPSED_KEY) === "true";
  } catch {
    return false;
  }
}

export function saveStageCollapsedPreference(collapsed: boolean) {
  if (typeof window === "undefined") return;
  try {
    localStorage.setItem(STAGE_COLLAPSED_KEY, String(collapsed));
  } catch {
    // 存储失败时忽略
  }
}

export function loadSessionsCollapsedPreference(): boolean {
  if (typeof window === "undefined") return false;
  try {
    return localStorage.getItem(SESSIONS_COLLAPSED_KEY) === "true";
  } catch {
    return false;
  }
}

export function saveSessionsCollapsedPreference(collapsed: boolean) {
  if (typeof window === "undefined") return;
  try {
    localStorage.setItem(SESSIONS_COLLAPSED_KEY, String(collapsed));
  } catch {
    // 存储失败时忽略
  }
}

export function loadLive2DBackgroundPreference(): import("@/lib/live2d/layout").Live2DBackgroundTheme {
  if (typeof window === "undefined") return "dusk";
  try {
    const raw = localStorage.getItem(LIVE2D_BG_KEY);
    if (raw === "aurora" || raw === "twilight" || raw === "soft" || raw === "dusk") {
      return raw;
    }
    return "dusk";
  } catch {
    return "dusk";
  }
}

export function saveLive2DBackgroundPreference(theme: import("@/lib/live2d/layout").Live2DBackgroundTheme) {
  if (typeof window === "undefined") return;
  try {
    localStorage.setItem(LIVE2D_BG_KEY, theme);
  } catch {
    // 存储失败时忽略
  }
}
