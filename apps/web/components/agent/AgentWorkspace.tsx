"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import AgentChatPanel from "@/components/agent/AgentChatPanel";
import AgentLive2DStage from "@/components/agent/AgentLive2DStage";
import AgentSessionSidebar from "@/components/agent/AgentSessionSidebar";
import { useAgentChatContext } from "@/components/agent/AgentChatProvider";
import {
  loadSessionsCollapsedPreference,
  saveSessionsCollapsedPreference,
} from "@/lib/agent/sessions";
import { useMinWidth } from "@/lib/hooks/useMinWidth";
import { Button } from "@/components/ui/Button";
import { EmptyState } from "@/components/ui/EmptyState";
import { cn } from "@/lib/utils";

export default function AgentWorkspace() {
  const { loggedIn, activeSessionId, sessions, switchSession, workspaceDark } =
    useAgentChatContext();
  const searchParams = useSearchParams();
  const router = useRouter();
  const sessionParam = searchParams.get("session");
  const appliedUrlSessionRef = useRef(false);
  const [sessionsCollapsed, setSessionsCollapsed] = useState(false);
  const [panelsHydrated, setPanelsHydrated] = useState(false);
  const isDesktopLayout = useMinWidth(992);
  const sessionsCollapsedEffective = sessionsCollapsed && isDesktopLayout;

  useEffect(() => {
    setSessionsCollapsed(loadSessionsCollapsedPreference());
    setPanelsHydrated(true);
  }, []);

  const toggleSessions = useCallback(() => {
    setSessionsCollapsed((prev) => {
      const next = !prev;
      saveSessionsCollapsedPreference(next);
      return next;
    });
  }, []);

  useEffect(() => {
    if (appliedUrlSessionRef.current || !sessionParam || sessions.length === 0) return;
    appliedUrlSessionRef.current = true;
    if (sessions.some((s) => s.id === sessionParam) && sessionParam !== activeSessionId) {
      switchSession(sessionParam);
    }
  }, [sessionParam, sessions, activeSessionId, switchSession]);

  useEffect(() => {
    if (!activeSessionId || sessionParam === activeSessionId) return;
    router.replace(`/agent?session=${encodeURIComponent(activeSessionId)}`, {
      scroll: false,
    });
  }, [sessionParam, activeSessionId, router]);

  if (!loggedIn) {
    return (
      <EmptyState
        className="post-card p-8 max-w-lg mx-auto"
        title="与珂朵莉深度对话需要先登录"
        action={
          <Link href="/login">
            <Button>去登录</Button>
          </Link>
        }
      />
    );
  }

  return (
    <div
      className={cn(
        "agent-workspace",
        workspaceDark && "agent-workspace--dark",
        panelsHydrated && sessionsCollapsedEffective && "agent-workspace--sessions-collapsed",
      )}
      data-testid="agent-workspace"
    >
      <div className="agent-workspace-stage">
        <div className="agent-workspace-stage-body">
          <AgentLive2DStage />
        </div>
      </div>

      <div className="agent-workspace-chat-shell">
        <AgentChatPanel variant="workspace" />
      </div>

      <AgentSessionSidebar
        collapsed={sessionsCollapsedEffective}
        onToggleCollapse={toggleSessions}
        showCollapseControl={isDesktopLayout}
      />
    </div>
  );
}
