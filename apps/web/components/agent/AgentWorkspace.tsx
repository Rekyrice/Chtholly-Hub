"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import AgentChatPanel from "@/components/agent/AgentChatPanel";
import AgentLive2DStage from "@/components/agent/AgentLive2DStage";
import AgentSessionSidebar from "@/components/agent/AgentSessionSidebar";
import AgentSidebarCollapseBtn from "@/components/agent/AgentSidebarCollapseBtn";
import { useAgentChatContext } from "@/components/agent/AgentChatProvider";
import {
  loadSessionsCollapsedPreference,
  loadStageCollapsedPreference,
  saveSessionsCollapsedPreference,
  saveStageCollapsedPreference,
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
  const [stageCollapsed, setStageCollapsed] = useState(false);
  const [sessionsCollapsed, setSessionsCollapsed] = useState(false);
  const [panelsHydrated, setPanelsHydrated] = useState(false);
  const isDesktopLayout = useMinWidth(992);
  const stageCollapsedEffective = stageCollapsed && isDesktopLayout;
  const sessionsCollapsedEffective = sessionsCollapsed && isDesktopLayout;

  useEffect(() => {
    setStageCollapsed(loadStageCollapsedPreference());
    setSessionsCollapsed(loadSessionsCollapsedPreference());
    setPanelsHydrated(true);
  }, []);

  const toggleStage = useCallback(() => {
    setStageCollapsed((prev) => {
      const next = !prev;
      saveStageCollapsedPreference(next);
      return next;
    });
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
        panelsHydrated && stageCollapsedEffective && "agent-workspace--stage-collapsed",
        panelsHydrated && sessionsCollapsedEffective && "agent-workspace--sessions-collapsed",
      )}
      data-testid="agent-workspace"
    >
      <div
        className={cn(
          "agent-workspace-stage",
          stageCollapsedEffective && "agent-workspace-stage--collapsed",
        )}
      >
        <div className="agent-workspace-stage-body">
          <AgentLive2DStage />
        </div>
        {stageCollapsedEffective && (
          <div className="agent-sidebar-collapsed-hint agent-sidebar-collapsed-hint--stage">
            <span className="agent-avatar-sm" aria-hidden="true">
              C
            </span>
          </div>
        )}
        {isDesktopLayout && (
          <AgentSidebarCollapseBtn
            side="left"
            collapsed={stageCollapsedEffective}
            onToggle={toggleStage}
            label={stageCollapsedEffective ? "展开 Live2D 展示区" : "收起 Live2D 展示区"}
            testId="agent-stage-collapse-toggle"
          />
        )}
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
