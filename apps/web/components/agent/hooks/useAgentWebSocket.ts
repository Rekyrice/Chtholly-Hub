"use client";

import { useCallback, useEffect, useMemo, useRef, useState, type Dispatch, type SetStateAction } from "react";
import { getAgentWsUrl } from "@/lib/agent/wsUrl";
import type { AgentSessionContext } from "@/lib/agent/sessions";
import type {
  AgentEventType,
  AgentSendOptions,
  AgentTaskType,
  AgentWsEnvelope,
  ChatMessage,
  ProactiveNotificationItem,
} from "@/lib/types/agent";
import type { AgentLivePhase } from "@/lib/types/live2d";

const TURN_INTERRUPTED_MESSAGE = "Agent 连接已中断，请重新发送。";

type UseAgentWebSocketOptions = {
  loggedIn: boolean;
  hydrated: boolean;
  activeSessionIdRef: { current: string };
  activeSessionContextRef: { current: AgentSessionContext | null };
  messages: ChatMessage[];
  setMessages: Dispatch<SetStateAction<ChatMessage[]>>;
  onInputConsumed: () => void;
};

type BuildAgentChatPayloadInput = {
  sessionId: string;
  message: string;
  pathname: string;
  title: string;
  search: string;
  taskType?: AgentTaskType;
  sessionContext?: AgentSessionContext | null;
};

type BackendClearIntent = {
  kind: "backend" | "user";
  socket: WebSocket;
  requestId: string;
  turnGenerationAtSend: number;
};

function createRequestId() {
  const cryptoApi = globalThis.crypto;
  if (typeof cryptoApi?.randomUUID === "function") return cryptoApi.randomUUID();

  const bytes = new Uint8Array(16);
  cryptoApi.getRandomValues(bytes);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function buildAgentChatPayload({
  sessionId,
  message,
  pathname,
  title,
  search,
  taskType,
  sessionContext,
}: BuildAgentChatPayloadInput) {
  const source = sessionContext === undefined
    ? new URLSearchParams(search).get("context") ?? undefined
    : sessionContext?.contextKey;
  const context: Record<string, string> = {
    page: pathname,
    title: sessionContext?.contextTitle ?? title,
  };
  if (source) context.source = source;
  if (source?.startsWith("post:")) {
    context.postSlug = source.slice("post:".length);
  }
  if (sessionContext?.postId) context.postId = sessionContext.postId;
  return {
    type: "chat" as const,
    requestId: createRequestId(),
    sessionId,
    message,
    ...(taskType ? { taskType } : {}),
    context,
  };
}

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
  activeSessionContextRef,
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
  const connectionPromiseRef = useRef<Promise<WebSocket | null> | null>(null);
  const connectionGenerationRef = useRef(0);
  const streamingIdRef = useRef<string | null>(null);
  const currentRequestIdRef = useRef<string | null>(null);
  const currentTurnIdRef = useRef<string | null>(null);
  const currentTurnSocketRef = useRef<WebSocket | null>(null);
  const busyRef = useRef(false);
  const sendLockRef = useRef(false);
  const turnGenerationRef = useRef(0);
  const proactiveInstanceSequenceRef = useRef(0);
  const stepsRef = useRef<string[]>([]);
  const backendClearIntentsRef = useRef(new Map<string, BackendClearIntent>());

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
    proactiveInstanceSequenceRef.current += 1;
    const notification: ProactiveNotificationItem = {
      instanceId: `proactive-${proactiveInstanceSequenceRef.current}`,
      type: String(data.type ?? "thought"),
      message,
      timestamp: String(data.timestamp ?? new Date().toISOString()),
      channel: data.channel ? String(data.channel) : undefined,
    };
    setProactiveNotifications((previous) => [...previous, notification].slice(-20));
    setVisibleProactiveNotification(notification);
  }, []);

  const resetTransient = useCallback(() => {
    turnGenerationRef.current += 1;
    stepsRef.current = [];
    setLiveSteps([]);
    streamingIdRef.current = null;
    currentRequestIdRef.current = null;
    currentTurnIdRef.current = null;
    currentTurnSocketRef.current = null;
    busyRef.current = false;
    backendClearIntentsRef.current.clear();
    setBusy(false);
    setLivePhase("idle");
    setLastError(null);
  }, []);

  const interruptCurrentTurn = useCallback((socket: WebSocket) => {
    if (!busyRef.current || currentTurnSocketRef.current !== socket) return;

    busyRef.current = false;
    currentRequestIdRef.current = null;
    currentTurnIdRef.current = null;
    currentTurnSocketRef.current = null;
    const streamId = streamingIdRef.current;
    const steps = [...stepsRef.current];
    streamingIdRef.current = null;
    stepsRef.current = [];
    for (const [requestId, intent] of backendClearIntentsRef.current) {
      if (intent.socket === socket) backendClearIntentsRef.current.delete(requestId);
    }
    setMessages((previous) => [
      ...previous.map((message) => (
        message.id === streamId
          ? { ...message, streaming: false, completionState: "interrupted" as const, steps }
          : message
      )),
      {
        id: `s-${Date.now()}`,
        role: "system",
        content: TURN_INTERRUPTED_MESSAGE,
      },
    ]);
    setLiveSteps([]);
    setBusy(false);
    setLivePhase("idle");
    setLastError(null);
  }, [setMessages]);

  const finishTurnWithError = useCallback((data: Record<string, unknown>) => {
    const code = String(data.code ?? data.reason ?? "");
    const message = code === "RATE_LIMITED"
      ? "发送过于频繁，请稍后再试。"
      : String(data.message ?? "出错了");
    const streamId = streamingIdRef.current;
    const steps = [...stepsRef.current];
    setLivePhase("error");
    setLastError(message);
    streamingIdRef.current = null;
    currentRequestIdRef.current = null;
    currentTurnIdRef.current = null;
    currentTurnSocketRef.current = null;
    busyRef.current = false;
    setMessages((previous) => [
      ...previous.map((item) => (
        item.id === streamId
          ? { ...item, streaming: false, completionState: "interrupted" as const, steps }
          : item
      )),
      { id: `e-${Date.now()}`, role: "system", content: message, steps },
    ]);
    stepsRef.current = [];
    setLiveSteps([]);
    setBusy(false);
    const completedGeneration = turnGenerationRef.current;
    window.setTimeout(() => {
      if (turnGenerationRef.current !== completedGeneration || busyRef.current) return;
      setLivePhase("idle");
      setLastError(null);
    }, 3000);
  }, [setMessages]);

  const handleEnvelope = useCallback(
    (envelope: AgentWsEnvelope, sourceSocket: WebSocket) => {
      const type = envelope.type as AgentEventType;
      const data = envelope.data ?? {};
      if (type === "proactive") {
        if (sourceSocket !== wsRef.current) return;
        pushProactiveNotification(data);
        return;
      }
      if (type === "cleared") {
        if (wsRef.current !== sourceSocket) return;
        let intent: BackendClearIntent | undefined;
        if (typeof envelope.requestId === "string" && envelope.requestId) {
          intent = backendClearIntentsRef.current.get(envelope.requestId);
          if (!intent || intent.socket !== sourceSocket) return;
          backendClearIntentsRef.current.delete(envelope.requestId);
        } else {
          const candidates = [...backendClearIntentsRef.current.values()]
            .filter((candidate) => candidate.socket === sourceSocket);
          if (candidates.length !== 1) return;
          [intent] = candidates;
          backendClearIntentsRef.current.delete(intent.requestId);
        }
        if (intent.kind !== "user") return;
        if (busyRef.current || turnGenerationRef.current !== intent.turnGenerationAtSend) return;
        setMessages([]);
        resetTransient();
        return;
      }
      if (type === "rejected") {
        if (
          sourceSocket !== currentTurnSocketRef.current
          || envelope.requestId !== currentRequestIdRef.current
          || currentTurnIdRef.current
        ) return;
        finishTurnWithError(data);
        return;
      }
      if (type === "accepted") {
        if (
          sourceSocket !== currentTurnSocketRef.current
          || envelope.requestId !== currentRequestIdRef.current
          || typeof envelope.turnId !== "string"
          || !envelope.turnId
        ) return;
        if (currentTurnIdRef.current && currentTurnIdRef.current !== envelope.turnId) return;
        currentTurnIdRef.current = envelope.turnId;
        return;
      }
      if (
        sourceSocket !== currentTurnSocketRef.current
        || !currentRequestIdRef.current
        || !currentTurnIdRef.current
        || envelope.requestId !== currentRequestIdRef.current
        || envelope.turnId !== currentTurnIdRef.current
      ) return;
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
                  ? { ...message, content, streaming: false, completionState: "done" as const, steps }
                  : message,
              )
            : [
                ...previous,
                {
                  id: `a-${Date.now()}`,
                  role: "assistant",
                  content,
                  streaming: false,
                  completionState: "done" as const,
                  steps,
                },
              ],
        );
        streamingIdRef.current = null;
        currentRequestIdRef.current = null;
        currentTurnIdRef.current = null;
        currentTurnSocketRef.current = null;
        busyRef.current = false;
        stepsRef.current = [];
        setLiveSteps([]);
        setBusy(false);
        const completedGeneration = turnGenerationRef.current;
        window.setTimeout(() => {
          if (turnGenerationRef.current === completedGeneration && !busyRef.current) {
            setLivePhase("idle");
          }
        }, 2500);
        return;
      }
      if (type === "error") {
        finishTurnWithError(data);
        return;
      }
    },
    [finishTurnWithError, pushProactiveNotification, pushStep, resetTransient, setMessages],
  );

  const attachHandlers = useCallback(
    (socket: WebSocket) => {
      socket.onopen = () => {
        if (wsRef.current === socket) setConnected(true);
        else socket.close();
      };
      const handleDisconnect = () => {
        if (wsRef.current === socket) {
          wsRef.current = null;
          setConnected(false);
        }
        for (const [requestId, intent] of backendClearIntentsRef.current) {
          if (intent.socket === socket) backendClearIntentsRef.current.delete(requestId);
        }
        interruptCurrentTurn(socket);
      };
      socket.onclose = handleDisconnect;
      socket.onerror = handleDisconnect;
      socket.onmessage = (event) => {
        try {
          handleEnvelope(JSON.parse(event.data) as AgentWsEnvelope, socket);
        } catch {
          // 非协议消息不应打断后续流式事件。
        }
      };
    },
    [handleEnvelope, interruptCurrentTurn],
  );

  const connect = useCallback(() => {
    const currentSocket = wsRef.current;
    if (
      currentSocket
      && (
        currentSocket.readyState === WebSocket.CONNECTING
        || currentSocket.readyState === WebSocket.OPEN
      )
    ) return Promise.resolve(currentSocket);
    if (connectionPromiseRef.current) return connectionPromiseRef.current;

    const connectionGeneration = connectionGenerationRef.current;
    const connection = getAgentWsUrl().then((url) => {
      if (!url || connectionGenerationRef.current !== connectionGeneration) return null;
      const socket = new WebSocket(url);
      attachHandlers(socket);
      return socket;
    });
    connectionPromiseRef.current = connection;
    const clearPendingConnection = () => {
      if (connectionPromiseRef.current === connection) {
        connectionPromiseRef.current = null;
      }
    };
    void connection.then(clearPendingConnection, clearPendingConnection);
    return connection;
  }, [attachHandlers]);

  useEffect(() => {
    if (!loggedIn || !hydrated) {
      connectionGenerationRef.current += 1;
      connectionPromiseRef.current = null;
      wsRef.current?.close();
      wsRef.current = null;
      return;
    }
    let disposed = false;
    void connect().then((socket) => {
      if (!disposed && socket) {
        const activeTurnSocket = currentTurnSocketRef.current;
        const registeredSocket = wsRef.current;
        if (
          (!activeTurnSocket || activeTurnSocket === socket)
          && (
            !registeredSocket
            || registeredSocket === socket
            || registeredSocket.readyState !== WebSocket.OPEN
          )
        ) wsRef.current = socket;
      }
    });
    return () => {
      disposed = true;
      connectionGenerationRef.current += 1;
      connectionPromiseRef.current = null;
      wsRef.current?.close();
      wsRef.current = null;
      setConnected(false);
    };
  }, [connect, hydrated, loggedIn]);

  const waitUntilOpen = useCallback(async (socket: WebSocket) => {
    if (socket.readyState === WebSocket.OPEN) return true;
    return new Promise<boolean>((resolve) => {
      const previousOnOpen = socket.onopen;
      const previousOnError = socket.onerror;
      const previousOnClose = socket.onclose;
      let settled = false;
      const cleanup = () => {
        if (socket.onopen === handleOpen) socket.onopen = previousOnOpen;
        if (socket.onerror === handleError) socket.onerror = previousOnError;
        if (socket.onclose === handleClose) socket.onclose = previousOnClose;
      };
      const settle = (opened: boolean) => {
        if (settled) return;
        settled = true;
        cleanup();
        resolve(opened);
      };
      const handleOpen: NonNullable<WebSocket["onopen"]> = (event) => {
        previousOnOpen?.call(socket, event);
        setConnected(true);
        settle(true);
      };
      const handleError: NonNullable<WebSocket["onerror"]> = (event) => {
        previousOnError?.call(socket, event);
        settle(false);
      };
      const handleClose: NonNullable<WebSocket["onclose"]> = (event) => {
        previousOnClose?.call(socket, event);
        settle(false);
      };
      socket.onopen = handleOpen;
      socket.onerror = handleError;
      socket.onclose = handleClose;
    });
  }, []);

  const sendMessage = useCallback(
    async (text: string, options?: AgentSendOptions) => {
      const trimmed = text.trim();
      if (!trimmed || busyRef.current || sendLockRef.current) return;
      if (!loggedIn) {
        setMessages((previous) => [
          ...previous,
          { id: `s-${Date.now()}`, role: "system", content: "请先登录后再使用 Agent。" },
        ]);
        return;
      }
      sendLockRef.current = true;
      try {
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
        turnGenerationRef.current += 1;
        setMessages((previous) => [
          ...previous,
          { id: `u-${Date.now()}`, role: "user", content: trimmed },
        ]);
        onInputConsumed();
        setLastError(null);
        const payload = buildAgentChatPayload({
          sessionId,
          message: trimmed,
          pathname: window.location.pathname,
          title: document.title,
          search: window.location.search,
          taskType: options?.taskType,
          sessionContext: activeSessionContextRef.current,
        });
        currentRequestIdRef.current = payload.requestId;
        currentTurnIdRef.current = null;
        currentTurnSocketRef.current = socket;
        busyRef.current = true;
        setBusy(true);
        stepsRef.current = [];
        setLiveSteps([]);
        streamingIdRef.current = null;
        try {
          socket.send(JSON.stringify(payload));
        } catch {
          if (wsRef.current === socket) {
            wsRef.current = null;
            setConnected(false);
          }
          interruptCurrentTurn(socket);
        }
      } finally {
        sendLockRef.current = false;
      }
    },
    [
      activeSessionContextRef,
      activeSessionIdRef,
      connect,
      interruptCurrentTurn,
      loggedIn,
      onInputConsumed,
      setMessages,
      waitUntilOpen,
    ],
  );

  const clearBackendMemory = useCallback((sessionId: string) => {
    const socket = wsRef.current;
    if (!sessionId || !socket || socket.readyState !== WebSocket.OPEN) return;
    const requestId = createRequestId();
    backendClearIntentsRef.current.set(requestId, {
      kind: "backend",
      socket,
      requestId,
      turnGenerationAtSend: turnGenerationRef.current,
    });
    try {
      socket.send(JSON.stringify({ type: "clear", requestId, sessionId }));
    } catch {
      backendClearIntentsRef.current.delete(requestId);
    }
  }, []);

  const clearConversation = useCallback(() => {
    const sessionId = activeSessionIdRef.current;
    if (!sessionId || busyRef.current) return;
    const socket = wsRef.current;
    if (!socket || socket.readyState !== WebSocket.OPEN) {
      setMessages([]);
      resetTransient();
      return;
    }
    const requestId = createRequestId();
    backendClearIntentsRef.current.set(requestId, {
      kind: "user",
      socket,
      requestId,
      turnGenerationAtSend: turnGenerationRef.current,
    });
    try {
      socket.send(JSON.stringify({ type: "clear", requestId, sessionId }));
    } catch {
      backendClearIntentsRef.current.delete(requestId);
    }
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
