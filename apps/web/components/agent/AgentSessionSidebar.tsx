"use client";

import { Plus } from "lucide-react";
import { useAgentChatContext } from "@/components/agent/AgentChatProvider";
import { cn } from "@/lib/utils";

export default function AgentSessionSidebar() {
  const { sessions, activeSessionId, switchSession, createSession } = useAgentChatContext();

  return (
    <aside className="agent-session-sidebar" data-testid="agent-session-sidebar">
      <div className="flex items-center justify-between px-3 py-2 border-b border-border">
        <span className="text-xs font-medium text-text-secondary uppercase tracking-wide">
          历史会话
        </span>
        <button
          type="button"
          className="floating-agent-icon-btn"
          aria-label="新建会话"
          onClick={() => createSession()}
        >
          <Plus size={16} />
        </button>
      </div>
      <ul className="agent-session-list">
        {sessions.map((session) => (
          <li key={session.id}>
            <button
              type="button"
              className={cn(
                "agent-session-item",
                session.id === activeSessionId && "agent-session-item--active",
              )}
              onClick={() => switchSession(session.id)}
            >
              <span className="block truncate text-sm">{session.title}</span>
              <span className="block text-xs text-text-secondary mt-0.5">
                {new Date(session.updatedAt).toLocaleDateString("zh-CN")}
              </span>
            </button>
          </li>
        ))}
      </ul>
    </aside>
  );
}
