"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { getAgentWsUrl } from "@/lib/agent/wsUrl";
import { isLoggedIn } from "@/lib/auth/tokens";
import type { AgentEventType, AgentWsEnvelope, ChatMessage } from "@/lib/types/agent";

export type UseAgentChatOptions = {
  /** 为 false 时不建立 WebSocket（浮窗收起时可关闭连接） */
  enabled?: boolean;
};

export function useAgentChat(options: UseAgentChatOptions = {}) {
  const { enabled = true } = options;
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [connected, setConnected] = useState(false);
  const [busy, setBusy] = useState(false);
  const [showSteps, setShowSteps] = useState(true);
  const [liveSteps, setLiveSteps] = useState<string[]>([]);
  const wsRef = useRef<WebSocket | null>(null);
  const streamingIdRef = useRef<string | null>(null);
  const stepsRef = useRef<string[]>([]);

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
        setMessages([]);
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
    if (!enabled || !isLoggedIn()) {
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
  }, [enabled, connect]);

  const clearConversation = () => {
    const ws = wsRef.current;
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    ws.send(JSON.stringify({ type: "clear" }));
  };

  const sendMessage = async (text: string) => {
    const trimmed = text.trim();
    if (!trimmed || busy) return;

    if (!isLoggedIn()) {
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
        return;
      });
    }

    ws = wsRef.current!;
    setMessages((prev) => [...prev, { id: `u-${Date.now()}`, role: "user", content: trimmed }]);
    setInput("");
    setBusy(true);
    stepsRef.current = [];
    setLiveSteps([]);
    streamingIdRef.current = null;
    ws.send(JSON.stringify({ type: "chat", message: trimmed }));
  };

  return {
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
  };
}
