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
  loadShowStepsPreference,
  loadStoredSessions,
  saveActiveSessionId,
  saveShowStepsPreference,
  saveStoredSessions,
  sessionTitleFromMessages,
  type AgentSessionRecord,
} from "@/lib/agent/sessions";
import { getAgentWsUrl } from "@/lib/agent/wsUrl";
import { isLoggedIn, purgeExpiredAuth } from "@/lib/auth/tokens";
import type { AgentEventType, AgentWsEnvelope, ChatMessage } from "@/lib/types/agent";

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
  liveSteps: string[];
  sendMessage: (text: string) => Promise<void>;
  clearConversation: () => void;
  switchSession: (sessionId: string) => void;
  createSession: () => string;
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
  const [liveSteps, setLiveSteps] = useState<string[]>([]);
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
    saveActiveSessionId(activeId);
    setHydrated(true);
  }, []);

  const persistActiveSession = useCallback((nextMessages: ChatMessage[]) => {
    const id = activeSessionIdRef.current;
    if (!id) return;
    const record: AgentSessionRecord = {
      id,
      title: sessionTitleFromMessages(nextMessages),
      messages: nextMessages,
      createdAt: Date.now(),
      updatedAt: Date.now(),
    };
    setSessions((prev) => {
      const existing = prev.find((s) => s.id === id);
      const merged: AgentSessionRecord = {
        ...record,
        createdAt: existing?.createdAt ?? record.createdAt,
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

  const handleEnvelope = useCallback(
    (env: AgentWsEnvelope) => {
      const type = env.type as AgentEventType;
      const data = env.data ?? {};

      if (type === "think") {
        pushStep(`💭 ${String(data.content ?? "")}`);
        return;
      }
      if (type === "act") {
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
        return;
      }
      if (type === "error") {
        const reason = String(data.reason ?? "");
        const msg =
          reason === "RATE_LIMITED"
            ? "发送过于频繁，请稍后再试。"
            : String(data.message ?? "出错了");
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
        return;
      }
      if (type === "cleared") {
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
    [pushStep],
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

  /** 仅清空服务端 Redis 记忆，不改动当前会话的本地消息列表 */
  const clearBackendMemory = useCallback(() => {
    const ws = wsRef.current;
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    backendClearIntentRef.current = "backend";
    ws.send(JSON.stringify({ type: "clear" }));
  }, []);

  const clearConversation = useCallback(() => {
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
    ws.send(JSON.stringify({ type: "clear" }));
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

      setMessages((prev) => [...prev, { id: `u-${Date.now()}`, role: "user", content: trimmed }]);
      setInput("");
      setBusy(true);
      stepsRef.current = [];
      setLiveSteps([]);
      streamingIdRef.current = null;
      ws.send(JSON.stringify({ type: "chat", message: trimmed }));
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
      clearBackendMemory();
    },
    [clearBackendMemory, persistActiveSession],
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
    clearBackendMemory();
    return id;
  }, [clearBackendMemory, persistActiveSession]);

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
      liveSteps,
      sendMessage,
      clearConversation,
      switchSession,
      createSession,
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
      liveSteps,
      sendMessage,
      clearConversation,
      switchSession,
      createSession,
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
