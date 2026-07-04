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
import { agentService } from "@/lib/services/agentService";
import type { AgentExperience } from "@/lib/types/agent";
import { useMinWidth } from "@/lib/hooks/useMinWidth";
import { Button } from "@/components/ui/Button";
import { EmptyState } from "@/components/ui/EmptyState";
import { cn } from "@/lib/utils";

type AgentThought = {
  id: string;
  message: string;
  timestamp: string;
};

export default function AgentWorkspace() {
  const {
    loggedIn,
    activeSessionId,
    sessions,
    switchSession,
    workspaceDark,
    proactiveNotifications,
  } = useAgentChatContext();
  const searchParams = useSearchParams();
  const router = useRouter();
  const sessionParam = searchParams.get("session");
  const contextParam = searchParams.get("context");
  const appliedUrlSessionRef = useRef(false);
  const [sessionsCollapsed, setSessionsCollapsed] = useState(false);
  const [panelsHydrated, setPanelsHydrated] = useState(false);
  const [recentThoughts, setRecentThoughts] = useState<AgentThought[]>([]);
  const isDesktopLayout = useMinWidth(992);
  const sessionsCollapsedEffective = sessionsCollapsed && isDesktopLayout;

  useEffect(() => {
    setSessionsCollapsed(loadSessionsCollapsedPreference());
    setPanelsHydrated(true);
  }, []);

  useEffect(() => {
    if (!loggedIn) return;
    let cancelled = false;
    agentService
      .recentExperiences(3)
      .then((experiences) => {
        if (cancelled) return;
        setRecentThoughts(experiences.map(mapExperienceToThought));
      })
      .catch(() => {
        if (!cancelled) setRecentThoughts([]);
      });
    return () => {
      cancelled = true;
    };
  }, [loggedIn]);

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
    const nextParams = new URLSearchParams();
    nextParams.set("session", activeSessionId);
    if (contextParam) {
      nextParams.set("context", contextParam);
    }
    router.replace(`/agent?${nextParams.toString()}`, {
      scroll: false,
    });
  }, [sessionParam, contextParam, activeSessionId, router]);

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

  const liveThoughts = proactiveNotifications
    .filter((notification) => notification.type === "thought")
    .map((notification) => ({
      id: `live-${notification.timestamp}-${notification.message}`,
      message: notification.message,
      timestamp: notification.timestamp,
    }));
  const thoughts = mergeThoughts(liveThoughts, recentThoughts).slice(0, 3);

  return (
    <div
      className={cn(
        "agent-workspace",
        workspaceDark && "agent-workspace--dark",
        panelsHydrated && sessionsCollapsedEffective && "agent-workspace--sessions-collapsed",
      )}
      data-testid="agent-workspace"
    >
      <section className="agent-thoughts" aria-label="珂朵莉最近在想">
        <div className="agent-thoughts__header">
          <h3>珂朵莉最近在想</h3>
        </div>
        <div className="agent-thoughts__list">
          {thoughts.length > 0 ? (
            thoughts.map((thought) => (
              <article key={thought.id} className="agent-thought-item">
                <span className="thought-time">{formatRelativeTime(thought.timestamp)}</span>
                <p>{thought.message}</p>
              </article>
            ))
          ) : (
            <p className="agent-thought-empty">仓库现在很安静。</p>
          )}
        </div>
      </section>

      <section className="agent-workspace-main agent-layout" aria-label="珂朵莉对话舞台">
        <div className="agent-layout-left">
          <div className="agent-chat-area">
            <AgentChatPanel variant="workspace" className="h-full min-h-0" />
          </div>
        </div>
        <div className="agent-live2d-zone agent-workspace-main-live2d">
          <AgentLive2DStage />
        </div>
      </section>

      <AgentSessionSidebar
        collapsed={sessionsCollapsedEffective}
        onToggleCollapse={toggleSessions}
        showCollapseControl={isDesktopLayout}
      />
    </div>
  );
}

function mapExperienceToThought(experience: AgentExperience): AgentThought {
  return {
    id: `experience-${experience.createdAt}-${experience.text}`,
    message: experience.text,
    timestamp: experience.createdAt,
  };
}

function mergeThoughts(...groups: AgentThought[][]): AgentThought[] {
  const seen = new Set<string>();
  return groups
    .flat()
    .filter((thought) => {
      const key = `${thought.timestamp}:${thought.message}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    })
    .sort((a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime());
}

function formatRelativeTime(timestamp: string) {
  const time = new Date(timestamp).getTime();
  if (!Number.isFinite(time)) return "刚刚";

  const diffMs = Date.now() - time;
  const diffMinutes = Math.max(0, Math.floor(diffMs / 60_000));
  if (diffMinutes < 1) return "刚刚";
  if (diffMinutes < 60) return `${diffMinutes} 分钟前`;

  const diffHours = Math.floor(diffMinutes / 60);
  if (diffHours < 24) return `${diffHours} 小时前`;

  const diffDays = Math.floor(diffHours / 24);
  return `${diffDays} 天前`;
}
