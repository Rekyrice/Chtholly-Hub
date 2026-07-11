"use client";

import { useCallback, useEffect, useMemo, useRef, useState, type Dispatch, type SetStateAction } from "react";
import { getAgentWsUrl } from "@/lib/agent/wsUrl";
import type {
  AgentEventType,
  AgentWsEnvelope,
  ChatMessage,
  ProactiveNotificationItem,
} from "@/lib/types/agent";
import type { AgentLivePhase } from "@/lib/types/live2d";

type UseAgentWebSocketOptions = {
  loggedIn: boolean;
  hydrated: boolean;
  activeSessionIdRef: { current: string };
  messages: ChatMessage[];
  setMessages: Dispatch<SetStateAction<ChatMessage[]>>;
  onInputConsumed: () => void;
};

function formatActInput(input: unknown) {
  if (!input || typeof input !== "object") return "";
  try {
    return JSON.stringify(input);
  } catch {
    return String(input);
  }
}

export function useAgentWebSocket({
  loggedIn,
  hydrated,
  activeSessionIdRef,
  messages,
  setMessages,
  onInputConsumed,
}: UseAgentWebSocketOptions) {
  const [connected, setConnected] = useState(false);
  const [busy, setBusy] = useState(false);
  const [liveSteps, setLiveSteps] = useState<string[]>([]);
  const [livePhase, setLivePhase] = useState<AgentLivePhase>("idle");
  const [lastError, setLastError] = useState<string | null>(null);
  const [proactiveNotifications, setProactiveNotifications] = useState<ProactiveNotificationItem[]>([]);
  const [visibleProactiveNotification, setVisibleProactiveNotification] =
    useState<ProactiveNotificationItem | null>(null);
  const wsRef = useRef<WebSocket | null>(null);
  const streamingIdRef = useRef<string | null>(null);
  const stepsRef = useRef<string[]>([]);
  const backendClearIntentRef = useRef<"none" | "backend" | "user">("none");

  const pushStep = useCallback((line: string) => {
    stepsRef.current = [...stepsRef.current, line];
    setLiveSteps([...stepsRef.current]);
  }, []);

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
    setProactiveNotifications((previous) => [...previous, notification].slice(-20));
    setVisibleProactiveNotification(notification);
  }, []);

  const resetTransient = useCallback(() => {
    stepsRef.current = [];
    setLiveSteps([]);
    streamingIdRef.current = null;
    backendClearIntentRef.current = "none";
    setBusy(false);
    setLivePhase("idle");
    setLastError(null);
  }, []);

  const handleEnvelope = useCallback(
    (envelope: AgentWsEnvelope) => {
      const type = envelope.type as AgentEventType;
      const data = envelope.data ?? {};
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
        const input = formatActInput(data.input);
        pushStep(input ? `🔧 ${tool}(${input})` : `🔧 ${tool}`);
        return;
      }
      if (type === "observe") {
        const content = String(data.content ?? "");
        pushStep(`👁 ${content.length > 200 ? `${content.slice(0, 200)}…` : content}`);
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
          setMessages((previous) => [
            ...previous,
            { id, role: "assistant", content: chunk, streaming: true, steps: [...stepsRef.current] },
          ]);
          return;
        }
        setMessages((previous) =>
          previous.map((message) =>
            message.id === streamId ? { ...message, content: message.content + chunk } : message,
          ),
        );
        return;
      }
      if (type === "final") {
        const content = String(data.content ?? "");
        const streamId = streamingIdRef.current;
        const steps = [...stepsRef.current];
        setLivePhase("done");
        setLastError(null);
        setMessages((previous) =>
          streamId
            ? previous.map((message) =>
                message.id === streamId
                  ? { ...message, content, streaming: false, steps }
                  : message,
              )
            : [...previous, { id: `a-${Date.now()}`, role: "assistant", content, steps }],
        );
        streamingIdRef.current = null;
        stepsRef.current = [];
        setLiveSteps([]);
        setBusy(false);
        window.setTimeout(() => setLivePhase("idle"), 2500);
        return;
      }
      if (type === "error") {
        const reason = String(data.reason ?? "");
        const message = reason === "RATE_LIMITED"
          ? "发送过于频繁，请稍后再试。"
          : String(data.message ?? "出错了");
        const streamId = streamingIdRef.current;
        const steps = [...stepsRef.current];
        setLivePhase("error");
        setLastError(message);
        if (streamId) {
          setMessages((previous) => previous.filter((item) => item.id !== streamId));
        }
        streamingIdRef.current = null;
        setMessages((previous) => [
          ...previous,
          { id: `e-${Date.now()}`, role: "system", content: message, steps },
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
        if (backendClearIntentRef.current === "user") setMessages([]);
        resetTransient();
      }
    },
    [pushProactiveNotification, pushStep, resetTransient, setMessages],
  );

  const attachHandlers = useCallback(
    (socket: WebSocket) => {
      socket.onopen = () => setConnected(true);
      socket.onclose = () => setConnected(false);
      socket.onerror = () => setConnected(false);
      socket.onmessage = (event) => {
        try {
          handleEnvelope(JSON.parse(event.data) as AgentWsEnvelope);
        } catch {
          // 非协议消息不应打断后续流式事件。
        }
      };
    },
    [handleEnvelope],
  );

  const connect = useCallback(async () => {
    const url = await getAgentWsUrl();
    if (!url) return null;
    const socket = new WebSocket(url);
    attachHandlers(socket);
    return socket;
  }, [attachHandlers]);

  useEffect(() => {
    if (!loggedIn || !hydrated) {
      wsRef.current?.close();
      wsRef.current = null;
      return;
    }
    let disposed = false;
    void connect().then((socket) => {
      if (!disposed && socket) wsRef.current = socket;
    });
    return () => {
      disposed = true;
      wsRef.current?.close();
      wsRef.current = null;
      setConnected(false);
    };
  }, [connect, hydrated, loggedIn]);

  const waitUntilOpen = useCallback(async (socket: WebSocket) => {
    if (socket.readyState === WebSocket.OPEN) return true;
    return new Promise<boolean>((resolve) => {
      socket.onopen = () => {
        setConnected(true);
        resolve(true);
      };
      socket.onerror = () => resolve(false);
    });
  }, []);

  const sendMessage = useCallback(
    async (text: string) => {
      const trimmed = text.trim();
      if (!trimmed || busy) return;
      if (!loggedIn) {
        setMessages((previous) => [
          ...previous,
          { id: `s-${Date.now()}`, role: "system", content: "请先登录后再使用 Agent。" },
        ]);
        return;
      }
      let socket = wsRef.current;
      if (!socket || socket.readyState !== WebSocket.OPEN) {
        socket = await connect();
        if (!socket) {
          setMessages((previous) => [
            ...previous,
            { id: `s-${Date.now()}`, role: "system", content: "无法连接 Agent 服务。" },
          ]);
          return;
        }
        wsRef.current = socket;
        if (!(await waitUntilOpen(socket))) {
          setMessages((previous) => [
            ...previous,
            {
              id: `s-${Date.now()}`,
              role: "system",
              content: "Agent 连接失败，请确认后端已启动且 LLM_ENABLED=true。",
            },
          ]);
          return;
        }
      }
      const sessionId = activeSessionIdRef.current;
      if (!sessionId || socket.readyState !== WebSocket.OPEN) return;
      setMessages((previous) => [
        ...previous,
        { id: `u-${Date.now()}`, role: "user", content: trimmed },
      ]);
      onInputConsumed();
      setLastError(null);
      setBusy(true);
      stepsRef.current = [];
      setLiveSteps([]);
      streamingIdRef.current = null;
      const context: Record<string, string | undefined> = {
        page: window.location.pathname,
        title: document.title,
        source: new URLSearchParams(window.location.search).get("context") ?? undefined,
      };
      if (context.source?.startsWith("post:")) {
        context.postSlug = context.source.slice("post:".length);
      }
      socket.send(JSON.stringify({ type: "chat", sessionId, message: trimmed, context }));
    },
    [activeSessionIdRef, busy, connect, loggedIn, onInputConsumed, setMessages, waitUntilOpen],
  );

  const clearBackendMemory = useCallback((sessionId: string) => {
    const socket = wsRef.current;
    if (!sessionId || !socket || socket.readyState !== WebSocket.OPEN) return;
    backendClearIntentRef.current = "backend";
    socket.send(JSON.stringify({ type: "clear", sessionId }));
  }, []);

  const clearConversation = useCallback(() => {
    const sessionId = activeSessionIdRef.current;
    if (!sessionId) return;
    const socket = wsRef.current;
    backendClearIntentRef.current = "user";
    if (!socket || socket.readyState !== WebSocket.OPEN) {
      setMessages([]);
      resetTransient();
      return;
    }
    socket.send(JSON.stringify({ type: "clear", sessionId }));
  }, [activeSessionIdRef, resetTransient, setMessages]);

  const streaming = useMemo(
    () => messages.some((message) => message.streaming),
    [messages],
  );

  return {
    connected,
    busy,
    liveSteps,
    livePhase,
    lastError,
    proactiveNotifications,
    visibleProactiveNotification,
    dismissProactiveNotification,
    sendMessage,
    clearConversation,
    clearBackendMemory,
    resetTransient,
    streaming,
  } as const;
}
