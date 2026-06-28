"use client";

import { Plus } from "lucide-react";
import AgentSessionItem from "@/components/agent/AgentSessionItem";
import AgentSidebarCollapseBtn from "@/components/agent/AgentSidebarCollapseBtn";
import { useAgentChatContext } from "@/components/agent/AgentChatProvider";
import { cn } from "@/lib/utils";

type AgentSessionSidebarProps = {
  collapsed: boolean;
  onToggleCollapse: () => void;
  showCollapseControl?: boolean;
};

export default function AgentSessionSidebar({
  collapsed,
  onToggleCollapse,
  showCollapseControl = true,
}: AgentSessionSidebarProps) {
  const {
    sessions,
    activeSessionId,
    switchSession,
    createSession,
    renameSession,
    deleteSession,
  } = useAgentChatContext();

  return (
    <aside
      className={cn("agent-session-sidebar", collapsed && "agent-session-sidebar--collapsed")}
      data-testid="agent-session-sidebar"
    >
      {showCollapseControl && (
        <AgentSidebarCollapseBtn
          side="right"
          collapsed={collapsed}
          onToggle={onToggleCollapse}
          label={collapsed ? "展开历史会话" : "收起历史会话"}
          testId="agent-sessions-collapse-toggle"
        />
      )}

      {collapsed ? (
        <div className="agent-sidebar-collapsed-hint agent-sidebar-collapsed-hint--sessions">
          <span className="agent-sidebar-collapsed-label">历史</span>
        </div>
      ) : (
        <>
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
        </>
      )}
    </aside>
  );
}
