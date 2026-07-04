"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import {
  createSessionId,
  loadActiveSessionId,
  loadRichMarkdownPreference,
  loadShowStepsPreference,
  loadStoredSessions,
  loadWorkspaceDarkPreference,
  saveActiveSessionId,
  saveRichMarkdownPreference,
  saveShowStepsPreference,
  saveStoredSessions,
  saveWorkspaceDarkPreference,
  sessionTitleFromMessages,
  type AgentSessionRecord,
} from "@/lib/agent/sessions";
import { getAgentWsUrl } from "@/lib/agent/wsUrl";
import { isLoggedIn, purgeExpiredAuth } from "@/lib/auth/tokens";
import type {
  AgentEventType,
  AgentWsEnvelope,
  ChatMessage,
  ProactiveNotificationItem,
} from "@/lib/types/agent";
import type { AgentLivePhase } from "@/lib/types/live2d";

type AgentChatContextValue = {
  loggedIn: boolean;
  activeSessionId: string;
  sessions: AgentSessionRecord[];
  messages: ChatMessage[];
  input: string;
  setInput: (value: string) => void;
  connected: boolean;
  busy: boolean;
  showSteps: boolean;
  setShowSteps: (value: boolean | ((prev: boolean) => boolean)) => void;
  workspaceDark: boolean;
  setWorkspaceDark: (value: boolean | ((prev: boolean) => boolean)) => void;
  richMarkdown: boolean;
  setRichMarkdown: (value: boolean | ((prev: boolean) => boolean)) => void;
  liveSteps: string[];
  livePhase: AgentLivePhase;
  /** 是否存在流式输出中的助手消息 */
  streaming: boolean;
  /** 最近一次 Agent 错误文案，无则为 null */
  lastError: string | null;
  proactiveNotifications: ProactiveNotificationItem[];
  visibleProactiveNotification: ProactiveNotificationItem | null;
  dismissProactiveNotification: () => void;
  sendMessage: (text: string) => Promise<void>;
  clearConversation: () => void;
  switchSession: (sessionId: string) => void;
  createSession: () => string;
  renameSession: (sessionId: string, title: string) => void;
  deleteSession: (sessionId: string) => void;
  fillAndSend: (text: string) => void;
};

const AgentChatContext = createContext<AgentChatContextValue | null>(null);

function upsertSessionRecord(
  sessions: AgentSessionRecord[],
  record: AgentSessionRecord,
): AgentSessionRecord[] {
  const idx = sessions.findIndex((s) => s.id === record.id);
  if (idx === -1) return [record, ...sessions];
  const next = [...sessions];
  next[idx] = record;
  next.sort((a, b) => b.updatedAt - a.updatedAt);
  return next;
}

export function AgentChatProvider({ children }: { children: ReactNode }) {
  const [loggedIn, setLoggedIn] = useState(false);
  const [sessions, setSessions] = useState<AgentSessionRecord[]>([]);
  const [activeSessionId, setActiveSessionId] = useState("");
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [connected, setConnected] = useState(false);
  const [busy, setBusy] = useState(false);
  const [showSteps, setShowStepsState] = useState(false);
  const [workspaceDark, setWorkspaceDarkState] = useState(false);
  const [richMarkdown, setRichMarkdownState] = useState(true);
  const [liveSteps, setLiveSteps] = useState<string[]>([]);
  const [livePhase, setLivePhase] = useState<AgentLivePhase>("idle");
  const [lastError, setLastError] = useState<string | null>(null);
  const [proactiveNotifications, setProactiveNotifications] = useState<ProactiveNotificationItem[]>(
    [],
  );
  const [visibleProactiveNotification, setVisibleProactiveNotification] =
    useState<ProactiveNotificationItem | null>(null);
  const [hydrated, setHydrated] = useState(false);

  const wsRef = useRef<WebSocket | null>(null);
  const streamingIdRef = useRef<string | null>(null);
  const stepsRef = useRef<string[]>([]);
  /** 区分「仅清后端记忆」与「用户主动清空对话」 */
  const backendClearIntentRef = useRef<"none" | "backend" | "user">("none");
  const activeSessionIdRef = useRef(activeSessionId);
  const messagesRef = useRef(messages);
  const sessionsRef = useRef(sessions);

  activeSessionIdRef.current = activeSessionId;
  messagesRef.current = messages;
  sessionsRef.current = sessions;

  const syncAuth = useCallback(() => {
    purgeExpiredAuth();
    setLoggedIn(isLoggedIn());
  }, []);

  const setShowSteps = useCallback((value: boolean | ((prev: boolean) => boolean)) => {
    setShowStepsState((prev) => {
      const next = typeof value === "function" ? value(prev) : value;
      saveShowStepsPreference(next);
      return next;
    });
  }, []);

  const setWorkspaceDark = useCallback((value: boolean | ((prev: boolean) => boolean)) => {
    setWorkspaceDarkState((prev) => {
      const next = typeof value === "function" ? value(prev) : value;
      saveWorkspaceDarkPreference(next);
      return next;
    });
  }, []);

  const setRichMarkdown = useCallback((value: boolean | ((prev: boolean) => boolean)) => {
    setRichMarkdownState((prev) => {
      const next = typeof value === "function" ? value(prev) : value;
      saveRichMarkdownPreference(next);
      return next;
    });
  }, []);

  useEffect(() => {
    syncAuth();
    window.addEventListener("chtholly-auth-change", syncAuth);
    return () => window.removeEventListener("chtholly-auth-change", syncAuth);
  }, [syncAuth]);

  useEffect(() => {
    const stored = loadStoredSessions();
    let activeId = loadActiveSessionId();
    if (!activeId || !stored.some((s) => s.id === activeId)) {
      activeId = stored[0]?.id ?? createSessionId();
    }
    const active = stored.find((s) => s.id === activeId);
    setSessions(
      stored.length > 0
        ? stored
        : [
            {
              id: activeId,
              title: "新对话",
              messages: [],
              createdAt: Date.now(),
              updatedAt: Date.now(),
            },
          ],
    );
    setActiveSessionId(activeId);
    setMessages(active?.messages ?? []);
    setShowStepsState(loadShowStepsPreference());
    setWorkspaceDarkState(loadWorkspaceDarkPreference());
    setRichMarkdownState(loadRichMarkdownPreference());
    saveActiveSessionId(activeId);
    setHydrated(true);
  }, []);

  const persistActiveSession = useCallback((nextMessages: ChatMessage[]) => {
    const id = activeSessionIdRef.current;
    if (!id) return;
    setSessions((prev) => {
      const existing = prev.find((s) => s.id === id);
      const merged: AgentSessionRecord = {
        id,
        title: existing?.titleLocked
          ? existing.title
          : sessionTitleFromMessages(nextMessages),
        messages: nextMessages,
        createdAt: existing?.createdAt ?? Date.now(),
        updatedAt: Date.now(),
        titleLocked: existing?.titleLocked,
      };
      const next = upsertSessionRecord(prev, merged);
      saveStoredSessions(next);
      return next;
    });
  }, []);

  useEffect(() => {
    if (!hydrated) return;
    persistActiveSession(messages);
  }, [messages, hydrated, persistActiveSession]);

  const pushStep = useCallback((line: string) => {
    stepsRef.current = [...stepsRef.current, line];
    setLiveSteps([...stepsRef.current]);
  }, []);

  const formatActInput = (input: unknown) => {
    if (!input || typeof input !== "object") return "";
    try {
      return JSON.stringify(input);
    } catch {
      return String(input);
    }
  };

  const dismissProactiveNotification = useCallback(() => {
    setVisibleProactiveNotification(null);
  }, []);

  const pushProactiveNotification = useCallback((data: Record<string, unknown>) => {
    const message = String(data.message ?? "");
    if (!message) return;

    const notification: ProactiveNotificationItem = {
      type: String(data.type ?? "thought"),
      message,
      timestamp: String(data.timestamp ?? new Date().toISOString()),
      channel: data.channel ? String(data.channel) : undefined,
    };

    setProactiveNotifications((prev) => [...prev, notification].slice(-20));
    setVisibleProactiveNotification(notification);
  }, []);

  const handleEnvelope = useCallback(
    (env: AgentWsEnvelope) => {
      const type = env.type as AgentEventType;
      const data = env.data ?? {};

      if (type === "proactive") {
        pushProactiveNotification(data);
        return;
      }

      if (type === "think") {
        setLivePhase("think");
        pushStep(`💭 ${String(data.content ?? "")}`);
        return;
      }
      if (type === "act") {
        setLivePhase("act");
        const tool = String(data.tool ?? "");
        const inputStr = formatActInput(data.input);
        pushStep(inputStr ? `🔧 ${tool}(${inputStr})` : `🔧 ${tool}`);
        return;
      }
      if (type === "observe") {
        const content = String(data.content ?? "");
        pushStep(
          `👁 ${content.length > 200 ? content.slice(0, 200) + "…" : content}`,
        );
        return;
      }
      if (type === "delta") {
        setLivePhase("speaking");
        const chunk = String(data.content ?? "");
        if (!chunk) return;
        const streamId = streamingIdRef.current;
        if (!streamId) {
          const id = `a-${Date.now()}`;
          streamingIdRef.current = id;
          setMessages((prev) => [
            ...prev,
            {
              id,
              role: "assistant",
              content: chunk,
              streaming: true,
              steps: [...stepsRef.current],
            },
          ]);
          return;
        }
        setMessages((prev) =>
          prev.map((m) => (m.id === streamId ? { ...m, content: m.content + chunk } : m)),
        );
        return;
      }
      if (type === "final") {
        setLivePhase("done");
        setLastError(null);
        const content = String(data.content ?? "");
        const streamId = streamingIdRef.current;
        const steps = [...stepsRef.current];
        if (streamId) {
          setMessages((prev) =>
            prev.map((m) =>
              m.id === streamId ? { ...m, content, streaming: false, steps } : m,
            ),
          );
        } else {
          setMessages((prev) => [
            ...prev,
            { id: `a-${Date.now()}`, role: "assistant", content, steps },
          ]);
        }
        streamingIdRef.current = null;
        stepsRef.current = [];
        setLiveSteps([]);
        setBusy(false);
        window.setTimeout(() => setLivePhase("idle"), 2500);
        return;
      }
      if (type === "error") {
        setLivePhase("error");
        const reason = String(data.reason ?? "");
        const msg =
          reason === "RATE_LIMITED"
            ? "发送过于频繁，请稍后再试。"
            : String(data.message ?? "出错了");
        setLastError(msg);
        const streamId = streamingIdRef.current;
        const steps = [...stepsRef.current];
        if (streamId) {
          setMessages((prev) => prev.filter((m) => m.id !== streamId));
        }
        streamingIdRef.current = null;
        setMessages((prev) => [
          ...prev,
          { id: `e-${Date.now()}`, role: "system", content: msg, steps },
        ]);
        stepsRef.current = [];
        setLiveSteps([]);
        setBusy(false);
        window.setTimeout(() => {
          setLivePhase("idle");
          setLastError(null);
        }, 3000);
        return;
      }
      if (type === "cleared") {
        setLivePhase("idle");
        setLastError(null);
        if (backendClearIntentRef.current === "user") {
          setMessages([]);
        }
        backendClearIntentRef.current = "none";
        stepsRef.current = [];
        setLiveSteps([]);
        streamingIdRef.current = null;
        setBusy(false);
      }
    },
    [pushProactiveNotification, pushStep],
  );

  const attachWsHandlers = useCallback(
    (ws: WebSocket) => {
      ws.onopen = () => setConnected(true);
      ws.onclose = () => setConnected(false);
      ws.onerror = () => setConnected(false);
      ws.onmessage = (ev) => {
        try {
          handleEnvelope(JSON.parse(ev.data) as AgentWsEnvelope);
        } catch {
          // 忽略解析错误
        }
      };
    },
    [handleEnvelope],
  );

  const connect = useCallback(async () => {
    const url = await getAgentWsUrl();
    if (!url) return null;
    const ws = new WebSocket(url);
    attachWsHandlers(ws);
    return ws;
  }, [attachWsHandlers]);

  useEffect(() => {
    if (!loggedIn || !hydrated) {
      wsRef.current?.close();
      wsRef.current = null;
      setConnected(false);
      return;
    }

    let closed = false;
    void connect().then((ws) => {
      if (closed || !ws) return;
      wsRef.current = ws;
    });

    return () => {
      closed = true;
      wsRef.current?.close();
      wsRef.current = null;
      setConnected(false);
    };
  }, [loggedIn, hydrated, connect]);

  /** 仅清空指定会话在 Redis 中的 Agent 记忆，不改动本地消息列表 */
  const clearBackendMemory = useCallback((sessionId: string) => {
    if (!sessionId) return;
    const ws = wsRef.current;
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    backendClearIntentRef.current = "backend";
    ws.send(JSON.stringify({ type: "clear", sessionId }));
  }, []);

  const clearConversation = useCallback(() => {
    const sessionId = activeSessionIdRef.current;
    if (!sessionId) return;
    backendClearIntentRef.current = "user";
    const ws = wsRef.current;
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      setMessages([]);
      stepsRef.current = [];
      setLiveSteps([]);
      streamingIdRef.current = null;
      setBusy(false);
      backendClearIntentRef.current = "none";
      return;
    }
    ws.send(JSON.stringify({ type: "clear", sessionId }));
  }, []);

  const sendMessage = useCallback(
    async (text: string) => {
      const trimmed = text.trim();
      if (!trimmed || busy) return;

      if (!loggedIn) {
        setMessages((prev) => [
          ...prev,
          { id: `s-${Date.now()}`, role: "system", content: "请先登录后再使用 Agent。" },
        ]);
        return;
      }

      let ws = wsRef.current;
      if (!ws || ws.readyState !== WebSocket.OPEN) {
        ws = await connect();
        if (!ws) {
          setMessages((prev) => [
            ...prev,
            { id: `s-${Date.now()}`, role: "system", content: "无法连接 Agent 服务。" },
          ]);
          return;
        }
        wsRef.current = ws;
        attachWsHandlers(ws);
        await new Promise<void>((resolve, reject) => {
          if (ws!.readyState === WebSocket.OPEN) {
            setConnected(true);
            resolve();
            return;
          }
          ws!.onopen = () => {
            setConnected(true);
            resolve();
          };
          ws!.onerror = () => reject(new Error("connect failed"));
        }).catch(() => {
          setMessages((prev) => [
            ...prev,
            {
              id: `s-${Date.now()}`,
              role: "system",
              content: "Agent 连接失败，请确认后端已启动且 LLM_ENABLED=true。",
            },
          ]);
        });
      }

      ws = wsRef.current;
      if (!ws || ws.readyState !== WebSocket.OPEN) return;

      const sessionId = activeSessionIdRef.current;
      if (!sessionId) return;

      setMessages((prev) => [...prev, { id: `u-${Date.now()}`, role: "user", content: trimmed }]);
      setInput("");
      setLastError(null);
      setBusy(true);
      stepsRef.current = [];
      setLiveSteps([]);
      streamingIdRef.current = null;
      const context = {
        page: window.location.pathname,
        title: document.title,
        source: new URLSearchParams(window.location.search).get("context") ?? undefined,
      };
      const source = context.source;
      const postSlug = source?.startsWith("post:") ? source.slice("post:".length) : undefined;
      if (postSlug) {
        Object.assign(context, { postSlug });
      }
      ws.send(JSON.stringify({ type: "chat", sessionId, message: trimmed, context }));
    },
    [attachWsHandlers, busy, connect, loggedIn],
  );

  const fillAndSend = useCallback(
    (text: string) => {
      setInput(text);
      void sendMessage(text);
    },
    [sendMessage],
  );

  const switchSession = useCallback(
    (sessionId: string) => {
      if (sessionId === activeSessionIdRef.current) return;

      persistActiveSession(messagesRef.current);

      const target = sessionsRef.current.find((s) => s.id === sessionId);
      if (!target) return;

      setActiveSessionId(sessionId);
      saveActiveSessionId(sessionId);
      setMessages(target.messages);
      setInput("");
      stepsRef.current = [];
      setLiveSteps([]);
      streamingIdRef.current = null;
      setBusy(false);
    },
    [persistActiveSession],
  );

  const createSession = useCallback(() => {
    persistActiveSession(messagesRef.current);
    const id = createSessionId();
    const record: AgentSessionRecord = {
      id,
      title: "新对话",
      messages: [],
      createdAt: Date.now(),
      updatedAt: Date.now(),
    };
    setSessions((prev) => {
      const next = upsertSessionRecord(prev, record);
      saveStoredSessions(next);
      return next;
    });
    setActiveSessionId(id);
    saveActiveSessionId(id);
    setMessages([]);
    setInput("");
    stepsRef.current = [];
    setLiveSteps([]);
    streamingIdRef.current = null;
    setBusy(false);
    return id;
  }, [persistActiveSession]);

  const renameSession = useCallback((sessionId: string, title: string) => {
    const trimmed = title.trim();
    if (!trimmed) return;
    setSessions((prev) => {
      const next = prev.map((s) =>
        s.id === sessionId
          ? { ...s, title: trimmed, titleLocked: true, updatedAt: Date.now() }
          : s,
      );
      saveStoredSessions(next);
      return next;
    });
  }, []);

  const deleteSession = useCallback(
    (sessionId: string) => {
      const isActive = sessionId === activeSessionIdRef.current;
      if (isActive) {
        persistActiveSession(messagesRef.current);
      }

      let nextSessions = sessionsRef.current.filter((s) => s.id !== sessionId);
      clearBackendMemory(sessionId);

      if (nextSessions.length === 0) {
        const id = createSessionId();
        const record: AgentSessionRecord = {
          id,
          title: "新对话",
          messages: [],
          createdAt: Date.now(),
          updatedAt: Date.now(),
        };
        nextSessions = [record];
        setActiveSessionId(id);
        saveActiveSessionId(id);
        setMessages([]);
        setInput("");
        stepsRef.current = [];
        setLiveSteps([]);
        streamingIdRef.current = null;
        setBusy(false);
      } else if (isActive) {
        const fallback = [...nextSessions].sort((a, b) => b.updatedAt - a.updatedAt)[0];
        setActiveSessionId(fallback.id);
        saveActiveSessionId(fallback.id);
        setMessages(fallback.messages);
        setInput("");
        stepsRef.current = [];
        setLiveSteps([]);
        streamingIdRef.current = null;
        setBusy(false);
      }

      setSessions(nextSessions);
      saveStoredSessions(nextSessions);
    },
    [clearBackendMemory, persistActiveSession],
  );

  const streaming = useMemo(
    () => messages.some((m) => m.streaming),
    [messages],
  );

  const value = useMemo<AgentChatContextValue>(
    () => ({
      loggedIn,
      activeSessionId,
      sessions,
      messages,
      input,
      setInput,
      connected,
      busy,
      showSteps,
      setShowSteps,
      workspaceDark,
      setWorkspaceDark,
      richMarkdown,
      setRichMarkdown,
      liveSteps,
      livePhase,
      streaming,
      lastError,
      proactiveNotifications,
      visibleProactiveNotification,
      dismissProactiveNotification,
      sendMessage,
      clearConversation,
      switchSession,
      createSession,
      renameSession,
      deleteSession,
      fillAndSend,
    }),
    [
      loggedIn,
      activeSessionId,
      sessions,
      messages,
      input,
      connected,
      busy,
      showSteps,
      workspaceDark,
      richMarkdown,
      liveSteps,
      livePhase,
      streaming,
      lastError,
      proactiveNotifications,
      visibleProactiveNotification,
      dismissProactiveNotification,
      sendMessage,
      clearConversation,
      switchSession,
      createSession,
      renameSession,
      deleteSession,
      fillAndSend,
    ],
  );

  return <AgentChatContext.Provider value={value}>{children}</AgentChatContext.Provider>;
}

export function useAgentChatContext() {
  const ctx = useContext(AgentChatContext);
  if (!ctx) {
    throw new Error("useAgentChatContext 必须在 AgentChatProvider 内使用");
  }
  return ctx;
}
