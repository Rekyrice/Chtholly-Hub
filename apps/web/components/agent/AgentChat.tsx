"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { getAgentWsUrl } from "@/lib/agent/wsUrl";
import { isLoggedIn } from "@/lib/auth/tokens";
import { siteConfig } from "@/lib/site.config";
import type { AgentEventType, AgentWsEnvelope, ChatMessage } from "@/lib/types/agent";

const SUGGESTIONS = [
  "站内有没有写过珂朵莉？",
  "帮我搜一下动漫相关的文章",
  "re0 有几季",
];

export default function AgentChat() {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [connected, setConnected] = useState(false);
  const [busy, setBusy] = useState(false);
  const [showSteps, setShowSteps] = useState(true);
  const [liveSteps, setLiveSteps] = useState<string[]>([]);
  const wsRef = useRef<WebSocket | null>(null);
  const streamingIdRef = useRef<string | null>(null);
  const stepsRef = useRef<string[]>([]);
  const bottomRef = useRef<HTMLDivElement>(null);

  const scrollBottom = () => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  };

  useEffect(() => {
    scrollBottom();
  }, [messages, busy, liveSteps]);

  const connect = useCallback(async () => {
    const url = await getAgentWsUrl();
    if (!url) return null;
    const ws = new WebSocket(url);
    ws.onopen = () => setConnected(true);
    ws.onclose = () => setConnected(false);
    ws.onerror = () => setConnected(false);
    return ws;
  }, []);

  useEffect(() => {
    if (!isLoggedIn()) return;
    let closed = false;
    void connect().then((ws) => {
      if (closed || !ws) return;
      wsRef.current = ws;
    });
    return () => {
      closed = true;
      wsRef.current?.close();
      wsRef.current = null;
    };
  }, [connect]);

  const pushStep = (line: string) => {
    stepsRef.current = [...stepsRef.current, line];
    setLiveSteps([...stepsRef.current]);
  };

  const formatActInput = (input: unknown) => {
    if (!input || typeof input !== "object") return "";
    try {
      return JSON.stringify(input);
    } catch {
      return String(input);
    }
  };

  const handleEnvelope = (env: AgentWsEnvelope) => {
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
      pushStep(`📋 ${content.length > 200 ? content.slice(0, 200) + "…" : content}`);
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
          { id, role: "assistant", content: chunk, streaming: true, steps: [...stepsRef.current] },
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
  };

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
      await new Promise<void>((resolve, reject) => {
        ws!.onopen = () => {
          setConnected(true);
          resolve();
        };
        ws!.onerror = () => reject(new Error("connect failed"));
        ws!.onmessage = (ev) => {
          try {
            handleEnvelope(JSON.parse(ev.data) as AgentWsEnvelope);
          } catch {
            // 忽略解析错误
          }
        };
        ws!.onclose = () => setConnected(false);
      }).catch(() => {
        setMessages((prev) => [
          ...prev,
          { id: `s-${Date.now()}`, role: "system", content: "Agent 连接失败，请确认后端已启动且 LLM_ENABLED=true。" },
        ]);
        return;
      });
    }

    ws = wsRef.current!;
    ws.onmessage = (ev) => {
      try {
        handleEnvelope(JSON.parse(ev.data) as AgentWsEnvelope);
      } catch {
        // 忽略
      }
    };

    setMessages((prev) => [...prev, { id: `u-${Date.now()}`, role: "user", content: trimmed }]);
    setInput("");
    setBusy(true);
    stepsRef.current = [];
    setLiveSteps([]);
    streamingIdRef.current = null;
    ws.send(JSON.stringify({ type: "chat", message: trimmed }));
  };

  const renderSteps = (steps: string[]) => (
    <details className="mt-2 text-xs opacity-80" open={showSteps}>
      <summary className="cursor-pointer select-none">推理步骤 ({steps.length})</summary>
      <pre className="mt-1 whitespace-pre-wrap font-sans leading-relaxed">{steps.join("\n")}</pre>
    </details>
  );

  if (!isLoggedIn()) {
    return (
      <div className="post-card p-8 text-center">
        <p className="text-sm mb-4" style={{ color: "#757575" }}>
          与珂朵莉对话需要先登录。
        </p>
        <Link
          href="/login"
          className="inline-block px-5 py-2 text-sm text-white"
          style={{ backgroundColor: siteConfig.theme.primary }}
        >
          去登录
        </Link>
      </div>
    );
  }

  return (
    <div className="post-card flex flex-col" style={{ minHeight: "70vh" }}>
      <div
        className="px-6 py-4 border-b flex items-center justify-between"
        style={{ borderColor: "#f0f0f0" }}
      >
        <div>
          <h1 className="text-lg font-medium" style={{ color: "#424242" }}>
            珂朵莉 Agent
          </h1>
          <p className="text-xs mt-0.5" style={{ color: "#9e9e9e" }}>
            ReAct 推理 · 流式回答 · 会话记忆 · Bangumi / 站内检索
            {connected ? " · 已连接" : " · 未连接"}
          </p>
        </div>
        <div className="flex items-center gap-3">
          <button
            type="button"
            disabled={busy || messages.length === 0}
            onClick={clearConversation}
            className="text-xs px-2 py-1 border rounded disabled:opacity-40"
            style={{ borderColor: "#e0e0e0", color: "#757575" }}
          >
            清空对话
          </button>
          <label className="flex items-center gap-1.5 text-xs cursor-pointer" style={{ color: "#757575" }}>
          <input
            type="checkbox"
            checked={showSteps}
            onChange={(e) => setShowSteps(e.target.checked)}
          />
          显示推理过程
        </label>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto px-6 py-4 space-y-4">
        {messages.length === 0 && !busy && (
          <div className="text-center py-8">
            <p className="text-sm mb-4" style={{ color: "#9e9e9e" }}>
              问我站内文章、动漫话题吧。
            </p>
            <div className="flex flex-wrap gap-2 justify-center">
              {SUGGESTIONS.map((s) => (
                <button
                  key={s}
                  type="button"
                  disabled={busy}
                  onClick={() => void sendMessage(s)}
                  className="px-3 py-1.5 text-xs border rounded-full hover:border-[#009688] disabled:opacity-50"
                  style={{ borderColor: "#e0e0e0", color: "#616161" }}
                >
                  {s}
                </button>
              ))}
            </div>
          </div>
        )}

        {messages.map((msg) => (
          <div
            key={msg.id}
            className={`flex ${msg.role === "user" ? "justify-end" : "justify-start"}`}
          >
            <div
              className="max-w-[85%] px-4 py-3 text-sm leading-relaxed whitespace-pre-wrap"
              style={{
                backgroundColor:
                  msg.role === "user"
                    ? siteConfig.theme.primary
                    : msg.role === "system"
                      ? "#fff3e0"
                      : "#f5f5f5",
                color: msg.role === "user" ? "#fff" : "#424242",
                borderRadius: msg.role === "user" ? "12px 12px 0 12px" : "12px 12px 12px 0",
              }}
            >
              {msg.content}
              {msg.streaming && (
                <span className="inline-block w-1.5 h-4 ml-0.5 align-middle animate-pulse" style={{ backgroundColor: "#009688" }} />
              )}
              {showSteps && msg.steps && msg.steps.length > 0 && renderSteps(msg.steps)}
            </div>
          </div>
        ))}

        {busy && showSteps && liveSteps.length > 0 && (
          <div className="px-4 py-3 text-xs rounded border" style={{ borderColor: "#e0e0e0", color: "#616161", background: "#fafafa" }}>
            <p className="font-medium mb-1">推理中…</p>
            <pre className="whitespace-pre-wrap font-sans leading-relaxed">{liveSteps.join("\n")}</pre>
          </div>
        )}

        {busy && liveSteps.length === 0 && !messages.some((m) => m.streaming) && (
          <p className="text-xs px-2" style={{ color: "#9e9e9e" }}>
            珂朵莉思考中…
          </p>
        )}
        <div ref={bottomRef} />
      </div>

      <form
        className="px-6 py-4 border-t flex gap-2"
        style={{ borderColor: "#f0f0f0" }}
        onSubmit={(e) => {
          e.preventDefault();
          void sendMessage(input);
        }}
      >
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="输入问题…"
          disabled={busy}
          className="flex-1 px-3 py-2 text-sm border outline-none focus:border-[#009688] disabled:opacity-50"
          style={{ borderColor: "#e0e0e0" }}
        />
        <button
          type="submit"
          disabled={busy || !input.trim()}
          className="px-5 py-2 text-sm text-white disabled:opacity-50"
          style={{ backgroundColor: siteConfig.theme.primary }}
        >
          发送
        </button>
      </form>
    </div>
  );
}
