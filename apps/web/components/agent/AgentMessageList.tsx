"use client";

import { useEffect, useMemo, useRef, type RefObject } from "react";
import { AgentRichMessage, AgentSteps, stepTone } from "@/components/agent/AgentRichMessage";
import { useMangaMessageScroll } from "@/lib/hooks/useMangaMessageScroll";
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
  mangaLayout?: boolean;
  showAssistantAvatar?: boolean;
  scrollContainerRef?: RefObject<HTMLElement | null>;
  onSuggestion?: (text: string) => void;
};

function MessageBubble({
  msg,
  showSteps,
  rich,
  isSpeaking,
  isNew,
  bubbleRef,
  showAssistantAvatar,
}: {
  msg: ChatMessage;
  showSteps: boolean;
  rich?: boolean;
  isSpeaking?: boolean;
  isNew?: boolean;
  bubbleRef?: RefObject<HTMLDivElement | null>;
  showAssistantAvatar?: boolean;
}) {
  if (msg.role === "user") {
    return (
      <div
        className={cn(
          "agent-message-row agent-message-row--user",
          isNew && "agent-message-row--user-enter",
        )}
      >
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
    <div
      className={cn(
        "agent-message-row agent-message-row--assistant",
        showAssistantAvatar && "agent-message-row--with-avatar",
        isNew && "agent-message-row--assistant-enter",
      )}
    >
      {showAssistantAvatar && (
        <div className="agent-msg-avatar flex-none" aria-hidden="true">
          <span className="agent-avatar-sm">C</span>
        </div>
      )}
      <div
        ref={isSpeaking ? bubbleRef : undefined}
        className={cn(
          "agent-bubble-assistant max-w-full text-sm leading-relaxed",
          isSpeaking && "agent-bubble-assistant--speaking",
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
  mangaLayout = false,
  showAssistantAvatar = true,
  scrollContainerRef,
  onSuggestion,
}: AgentMessageListProps) {
  const bottomRef = useRef<HTMLDivElement>(null);
  const speakingBubbleRef = useRef<HTMLDivElement>(null);
  const seenIdsRef = useRef(new Set<string>());
  const empty = messages.length === 0 && !busy;

  const lastAssistantIndex = useMemo(() => {
    for (let i = messages.length - 1; i >= 0; i -= 1) {
      if (messages[i].role === "assistant") return i;
    }
    return -1;
  }, [messages]);

  const speakingMessageId =
    lastAssistantIndex >= 0 ? messages[lastAssistantIndex].id : null;

  const streaming = messages.some((m) => m.streaming);

  useMangaMessageScroll({
    enabled: mangaLayout && !!scrollContainerRef,
    scrollContainerRef: scrollContainerRef ?? { current: null },
    speakingBubbleRef,
    speakingMessageId,
    streaming,
  });

  useEffect(() => {
    if (!mangaLayout || !scrollContainerRef?.current) return;
    const last = messages[messages.length - 1];
    if (last?.role !== "user") return;
    const container = scrollContainerRef.current;
    container.scrollTo({ top: container.scrollHeight, behavior: "smooth" });
  }, [messages, mangaLayout, scrollContainerRef]);

  useEffect(() => {
    if (mangaLayout) return;
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, busy, liveSteps, mangaLayout]);

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
        const isSpeaking = index === lastAssistantIndex;
        const isNew = !seenIdsRef.current.has(msg.id);
        if (isNew) seenIdsRef.current.add(msg.id);

        return (
          <MessageBubble
            key={msg.id}
            msg={msg}
            showSteps={showSteps}
            rich={rich}
            isSpeaking={isSpeaking}
            isNew={isNew}
            bubbleRef={speakingBubbleRef}
            showAssistantAvatar={showAssistantAvatar}
          />
        );
      })}

      {busy && showSteps && liveSteps.length > 0 && (
        <div
          className={cn(
            "agent-message-row agent-message-row--assistant",
            showAssistantAvatar && "agent-message-row--with-avatar",
          )}
        >
          {showAssistantAvatar && (
            <div className="agent-steps-avatar flex-none" aria-hidden="true">
              <span className="agent-avatar-sm">C</span>
            </div>
          )}
          <div className="agent-live-steps flex-1 min-w-0 px-3 py-2 text-xs rounded-xl border border-border bg-cloud">
            <p className="font-medium mb-1 text-text">推理中…</p>
            <ul className="space-y-1">
              {liveSteps.map((line, i) => (
                <li key={i} className={cn("whitespace-pre-wrap leading-relaxed", stepTone(line))}>
                  {line}
                </li>
              ))}
            </ul>
          </div>
        </div>
      )}

      {busy && liveSteps.length === 0 && !messages.some((m) => m.streaming) && (
        <div
          className={cn(
            "agent-message-row agent-message-row--assistant",
            showAssistantAvatar && "agent-message-row--with-avatar",
          )}
        >
          {showAssistantAvatar && (
            <div className="agent-msg-avatar flex-none" aria-hidden="true">
              <span className="agent-avatar-sm">C</span>
            </div>
          )}
          <p className="text-xs text-text-secondary px-2">珂朵莉思考中…</p>
        </div>
      )}
      <div ref={bottomRef} />
    </>
  );
}
