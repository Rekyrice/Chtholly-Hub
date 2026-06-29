"use client";

import { useEffect, useRef } from "react";
import { AgentRichMessage, AgentSteps, stepTone } from "@/components/agent/AgentRichMessage";
import { cn } from "@/lib/utils";
import type { ChatMessage } from "@/lib/types/agent";

const SUGGESTIONS = [
  "站内有没有写过珂朵莉？",
  "帮我搜一下动漫相关的文章",
  "re0 有几季",
];

type AgentMessageListProps = {
  messages: ChatMessage[];
  busy: boolean;
  showSteps: boolean;
  liveSteps: string[];
  rich?: boolean;
  onSuggestion?: (text: string) => void;
};

function MessageBubble({
  msg,
  showSteps,
  rich,
  leadAssistant,
}: {
  msg: ChatMessage;
  showSteps: boolean;
  rich?: boolean;
  leadAssistant?: boolean;
}) {
  if (msg.role === "user") {
    return (
      <div className="agent-message-row agent-message-row--user">
        <div className="agent-bubble-user max-w-full text-sm leading-relaxed whitespace-pre-wrap">
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
    <div className="agent-message-row">
      <div
        className={cn(
          "agent-bubble-assistant max-w-full text-sm leading-relaxed",
          leadAssistant && "agent-bubble-assistant--lead",
        )}
      >
        {rich && !msg.streaming ? (
          <AgentRichMessage content={msg.content} />
        ) : (
          <span className="whitespace-pre-wrap">{msg.content}</span>
        )}
        {msg.streaming && <span className="agent-stream-cursor" aria-hidden="true" />}
        {showSteps && msg.steps && msg.steps.length > 0 && <AgentSteps steps={msg.steps} />}
      </div>
    </div>
  );
}

export default function AgentMessageList({
  messages,
  busy,
  showSteps,
  liveSteps,
  rich = false,
  onSuggestion,
}: AgentMessageListProps) {
  const bottomRef = useRef<HTMLDivElement>(null);
  const empty = messages.length === 0 && !busy;

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, busy, liveSteps]);

  return (
    <>
      {empty && onSuggestion && (
        <div className="text-center py-6">
          <p className="text-sm text-text-secondary mb-4">问我站内文章、动漫话题吧</p>
          <div className="flex flex-col gap-2 items-stretch">
            {SUGGESTIONS.map((s) => (
              <button
                key={s}
                type="button"
                disabled={busy}
                onClick={() => onSuggestion(s)}
                className="agent-suggestion-chip disabled:opacity-50"
              >
                {s}
              </button>
            ))}
          </div>
        </div>
      )}

      {messages.map((msg, index) => {
        const prev = messages[index - 1];
        const leadAssistant =
          msg.role === "assistant" && (!prev || prev.role !== "assistant");

        return (
          <MessageBubble
            key={msg.id}
            msg={msg}
            showSteps={showSteps}
            rich={rich}
            leadAssistant={leadAssistant}
          />
        );
      })}

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
    </>
  );
}
