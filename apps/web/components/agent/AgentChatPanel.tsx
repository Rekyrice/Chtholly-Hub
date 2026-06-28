"use client";

import { Minus, Send, Settings } from "lucide-react";
import { useEffect, useRef } from "react";
import { useAgentChat } from "@/hooks/useAgentChat";
import { cn } from "@/lib/utils";
import type { ChatMessage } from "@/lib/types/agent";

type AgentChatState = ReturnType<typeof useAgentChat>;

type AgentChatPanelProps = {
  connected: boolean;
  onMinimize?: () => void;
  chat: AgentChatState;
};

const SUGGESTIONS = [
  "站内有没有写过珂朵莉？",
  "帮我搜一下动漫相关的文章",
  "re0 有几季",
];

function stepTone(line: string) {
  if (line.startsWith("💭")) return "agent-step-think";
  if (line.startsWith("🔧")) return "agent-step-act";
  return "agent-step-observe";
}

function AgentSteps({ steps }: { steps: string[] }) {
  return (
    <details className="agent-steps mt-2 text-xs">
      <summary className="cursor-pointer select-none text-text-secondary">
        推理步骤 ({steps.length})
      </summary>
      <ul className="agent-steps-body mt-1 space-y-1">
        {steps.map((line, i) => (
          <li key={i} className={cn("whitespace-pre-wrap font-sans leading-relaxed", stepTone(line))}>
            {line}
          </li>
        ))}
      </ul>
    </details>
  );
}

function MessageBubble({ msg, showSteps }: { msg: ChatMessage; showSteps: boolean }) {
  if (msg.role === "user") {
    return (
      <div className="flex justify-end">
        <div className="agent-bubble-user max-w-[85%] px-4 py-2.5 text-sm leading-relaxed whitespace-pre-wrap">
          {msg.content}
        </div>
      </div>
    );
  }

  if (msg.role === "system") {
    return (
      <div className="flex justify-center">
        <p className="text-xs text-text-secondary px-3 py-1">{msg.content}</p>
      </div>
    );
  }

  return (
    <div className="flex justify-start gap-2 items-end">
      <span className="agent-avatar-sm shrink-0" aria-hidden="true">
        C
      </span>
      <div className="agent-bubble-assistant max-w-[85%] px-4 py-2.5 text-sm leading-relaxed whitespace-pre-wrap">
        {msg.content}
        {msg.streaming && <span className="agent-stream-cursor" aria-hidden="true" />}
        {showSteps && msg.steps && msg.steps.length > 0 && <AgentSteps steps={msg.steps} />}
      </div>
    </div>
  );
}

export default function AgentChatPanel({ connected, onMinimize, chat }: AgentChatPanelProps) {
  const {
    messages,
    input,
    setInput,
    busy,
    showSteps,
    setShowSteps,
    liveSteps,
    sendMessage,
    clearConversation,
  } = chat;

  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, busy, liveSteps]);

  const empty = messages.length === 0 && !busy;

  return (
    <div className="floating-agent-panel-inner flex flex-col h-full" data-testid="agent-chat-panel">
      <header className="floating-agent-header shrink-0">
        <div className="flex items-center gap-2.5 min-w-0">
          <span className="agent-avatar-md shrink-0" aria-hidden="true">
            C
          </span>
          <div className="min-w-0">
            <p className="text-sm font-medium text-text truncate">珂朵莉</p>
            <p className="flex items-center gap-1.5 text-xs text-text-secondary">
              <span
                className={cn("agent-status-dot", connected && "agent-status-dot--online")}
                aria-hidden="true"
              />
              {connected ? "在线" : "离线"}
            </p>
          </div>
        </div>
        <div className="flex items-center gap-1 shrink-0">
          <button
            type="button"
            className="floating-agent-icon-btn"
            onClick={() => setShowSteps((v) => !v)}
            aria-label={showSteps ? "隐藏推理步骤" : "显示推理步骤"}
            title="推理步骤"
          >
            <Settings size={16} />
          </button>
          <button
            type="button"
            className="floating-agent-icon-btn"
            onClick={onMinimize}
            aria-label="最小化"
          >
            <Minus size={16} />
          </button>
        </div>
      </header>

      <button
        type="button"
        className="floating-agent-drag-handle md:hidden"
        onClick={onMinimize}
        aria-label="关闭面板"
      />

      <div className="floating-agent-messages flex-1 overflow-y-auto px-4 py-3 space-y-3">
        {empty && (
          <div className="text-center py-6">
            <p className="text-sm text-text-secondary mb-4">问我站内文章、动漫话题吧</p>
            <div className="flex flex-col gap-2 items-stretch">
              {SUGGESTIONS.map((s) => (
                <button
                  key={s}
                  type="button"
                  disabled={busy}
                  onClick={() => void sendMessage(s)}
                  className="agent-suggestion-chip disabled:opacity-50"
                >
                  {s}
                </button>
              ))}
            </div>
          </div>
        )}

        {messages.map((msg) => (
          <MessageBubble key={msg.id} msg={msg} showSteps={showSteps} />
        ))}

        {busy && showSteps && liveSteps.length > 0 && (
          <div className="agent-live-steps px-3 py-2 text-xs rounded-xl border border-border bg-cloud">
            <p className="font-medium mb-1 text-text">推理中…</p>
            <ul className="space-y-1">
              {liveSteps.map((line, i) => (
                <li key={i} className={cn("whitespace-pre-wrap leading-relaxed", stepTone(line))}>
                  {line}
                </li>
              ))}
            </ul>
          </div>
        )}

        {busy && liveSteps.length === 0 && !messages.some((m) => m.streaming) && (
          <p className="text-xs text-text-secondary px-2">珂朵莉思考中…</p>
        )}
        <div ref={bottomRef} />
      </div>

      <form
        className="floating-agent-input shrink-0"
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
          className="floating-agent-input-field flex-1 text-sm disabled:opacity-50"
          data-testid="agent-input"
        />
        <button
          type="submit"
          disabled={busy || !input.trim()}
          className="floating-agent-send-btn disabled:opacity-50"
          aria-label="发送"
          data-testid="agent-send"
        >
          <Send size={16} className="text-on-primary" />
        </button>
      </form>

      {messages.length > 0 && (
        <div className="px-4 pb-2 shrink-0">
          <button
            type="button"
            disabled={busy}
            onClick={clearConversation}
            className="text-xs text-text-secondary hover:text-sky transition-colors duration-150 disabled:opacity-50"
          >
            清空对话
          </button>
        </div>
      )}
    </div>
  );
}
