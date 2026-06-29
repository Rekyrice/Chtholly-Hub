"use client";

import { useRef } from "react";
import Link from "next/link";
import { ExternalLink, Minus, Send, Settings } from "lucide-react";
import AgentMessageList from "@/components/agent/AgentMessageList";
import AgentWorkspaceSettings from "@/components/agent/AgentWorkspaceSettings";
import { useAgentChatContext } from "@/components/agent/AgentChatProvider";
import { cn } from "@/lib/utils";

type AgentChatPanelProps = {
  variant?: "float" | "workspace";
  onMinimize?: () => void;
  onExpand?: () => void;
  className?: string;
};

export default function AgentChatPanel({
  variant = "float",
  onMinimize,
  onExpand,
  className,
}: AgentChatPanelProps) {
  const {
    activeSessionId,
    messages,
    input,
    setInput,
    connected,
    busy,
    showSteps,
    setShowSteps,
    richMarkdown,
    liveSteps,
    sendMessage,
    clearConversation,
    fillAndSend,
  } = useAgentChatContext();

  const isWorkspace = variant === "workspace";
  const scrollContainerRef = useRef<HTMLDivElement>(null);

  return (
    <div
      className={cn(
        "floating-agent-panel-inner flex flex-col h-full min-h-0",
        isWorkspace && "agent-workspace-chat",
        className,
      )}
      data-testid="agent-chat-panel"
    >
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
          {variant === "float" && (
            <Link
              href={`/agent?session=${encodeURIComponent(activeSessionId)}`}
              className="floating-agent-icon-btn"
              aria-label="展开完整页面"
              title="展开完整页面"
              onClick={onExpand}
            >
              <ExternalLink size={16} />
            </Link>
          )}
          {isWorkspace ? (
            <AgentWorkspaceSettings />
          ) : (
            <button
              type="button"
              className="floating-agent-icon-btn"
              onClick={() => setShowSteps((v) => !v)}
              aria-label={showSteps ? "隐藏推理步骤" : "显示推理步骤"}
              title="推理步骤"
            >
              <Settings size={16} />
            </button>
          )}
          {onMinimize && (
            <button
              type="button"
              className="floating-agent-icon-btn"
              onClick={onMinimize}
              aria-label="最小化"
            >
              <Minus size={16} />
            </button>
          )}
        </div>
      </header>

      {variant === "float" && (
        <button
          type="button"
          className="floating-agent-drag-handle md:hidden"
          onClick={onMinimize}
          aria-label="关闭面板"
        />
      )}

      <div
        ref={scrollContainerRef}
        className={cn(
          "floating-agent-messages flex-1 overflow-y-auto px-4 py-3 space-y-3",
          isWorkspace && "agent-messages-manga",
        )}
      >
        <AgentMessageList
          messages={messages}
          busy={busy}
          showSteps={showSteps}
          liveSteps={liveSteps}
          rich={isWorkspace && richMarkdown}
          mangaLayout={isWorkspace}
          scrollContainerRef={scrollContainerRef}
          onSuggestion={fillAndSend}
        />
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

      {!isWorkspace && messages.length > 0 && (
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
