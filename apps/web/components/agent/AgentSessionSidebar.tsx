"use client";

import { Plus } from "lucide-react";
import AgentSessionItem from "@/components/agent/AgentSessionItem";
import { useAgentChatContext } from "@/components/agent/AgentChatProvider";

export default function AgentSessionSidebar() {
  const {
    sessions,
    activeSessionId,
    switchSession,
    createSession,
    renameSession,
    deleteSession,
  } = useAgentChatContext();

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
          <AgentSessionItem
            key={session.id}
            session={session}
            active={session.id === activeSessionId}
            onSelect={() => switchSession(session.id)}
            onRename={(title) => renameSession(session.id, title)}
            onDelete={() => deleteSession(session.id)}
          />
        ))}
      </ul>
    </aside>
  );
}
