"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import AgentChatPanel from "@/components/agent/AgentChatPanel";
import AgentLive2DStage from "@/components/agent/AgentLive2DStage";
import AgentSessionSidebar from "@/components/agent/AgentSessionSidebar";
import AgentSkillModeSelector, {
  getAgentSkillMode,
  parseAgentTaskType,
} from "@/components/agent/AgentSkillModeSelector";
import { useAgentChatContext } from "@/components/agent/AgentChatProvider";
import {
  loadSessionsCollapsedPreference,
  saveSessionsCollapsedPreference,
} from "@/lib/agent/sessions";
import { agentService } from "@/lib/services/agentService";
import type { AgentExperience } from "@/lib/types/agent";
import type { AgentTaskType } from "@/lib/types/agent";
import { useMinWidth } from "@/lib/hooks/useMinWidth";
import { Button } from "@/components/ui/Button";
import { EmptyState } from "@/components/ui/EmptyState";
import { cn } from "@/lib/utils";

type AgentThought = {
  id: string;
  message: string;
  timestamp: string;
};

const SESSIONS_COLLAPSED_EVENT = "chtholly-agent-sessions-collapsed-change";

function subscribeSessionsCollapsed(onStoreChange: () => void) {
  window.addEventListener(SESSIONS_COLLAPSED_EVENT, onStoreChange);
  return () => window.removeEventListener(SESSIONS_COLLAPSED_EVENT, onStoreChange);
}

export default function AgentWorkspace() {
  const {
    loggedIn,
    activeSessionId,
    activeSessionContext,
    sessions,
    switchSession,
    activateContextSession,
    workspaceDark,
    proactiveNotifications,
  } = useAgentChatContext();
  const searchParams = useSearchParams();
  const router = useRouter();
  const sessionParam = searchParams.get("session");
  const taskTypeParam = searchParams.get("taskType");
  const contextParam = searchParams.get("context");
  const contextTitleParam = searchParams.get("postTitle");
  const contextPostIdParam = searchParams.get("postId");
  const [taskType, setTaskType] = useState<AgentTaskType | null>(
    () => parseAgentTaskType(taskTypeParam),
  );
  const appliedUrlSessionRef = useRef(false);
  const appliedArticleContextRef = useRef("");
  const sessionsCollapsed = useSyncExternalStore(
    subscribeSessionsCollapsed,
    loadSessionsCollapsedPreference,
    () => false,
  );
  const [recentThoughts, setRecentThoughts] = useState<AgentThought[]>([]);
  const isDesktopLayout = useMinWidth(992);
  const sessionsCollapsedEffective = sessionsCollapsed && isDesktopLayout;
  const activeSkillMode = getAgentSkillMode(taskType);

  useEffect(() => {
    const nextTaskType = parseAgentTaskType(taskTypeParam);
    setTaskType((current) => current === nextTaskType ? current : nextTaskType);
  }, [taskTypeParam]);

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
    saveSessionsCollapsedPreference(!sessionsCollapsed);
    window.dispatchEvent(new Event(SESSIONS_COLLAPSED_EVENT));
  }, [sessionsCollapsed]);

  useEffect(() => {
    if (appliedUrlSessionRef.current || !sessionParam || sessions.length === 0) return;
    appliedUrlSessionRef.current = true;
    if (sessions.some((s) => s.id === sessionParam) && sessionParam !== activeSessionId) {
      switchSession(sessionParam);
    }
  }, [sessionParam, sessions, activeSessionId, switchSession]);

  useEffect(() => {
    if (
      !loggedIn
      || sessionParam
      || !contextParam?.startsWith("post:")
      || sessions.length === 0
      || appliedArticleContextRef.current === contextParam
    ) {
      return;
    }
    appliedArticleContextRef.current = contextParam;
    const articleSessionId = activateContextSession({
      contextKey: contextParam,
      contextTitle: contextTitleParam?.trim() || "当前文章",
      postId: contextPostIdParam || undefined,
    });
    const nextParams = new URLSearchParams(searchParams.toString());
    nextParams.set("session", articleSessionId);
    router.replace(`/agent?${nextParams.toString()}`, { scroll: false });
  }, [
    activateContextSession,
    contextParam,
    contextPostIdParam,
    contextTitleParam,
    loggedIn,
    router,
    searchParams,
    sessionParam,
    sessions.length,
  ]);

  useEffect(() => {
    if (
      !activeSessionId
      || sessionParam === activeSessionId
      || (!sessionParam && contextParam?.startsWith("post:"))
    ) return;
    const nextParams = new URLSearchParams(searchParams.toString());
    nextParams.set("session", activeSessionId);
    router.replace(`/agent?${nextParams.toString()}`, {
      scroll: false,
    });
  }, [sessionParam, activeSessionId, contextParam, router, searchParams]);

  const changeTaskType = useCallback((nextTaskType: AgentTaskType | null) => {
    setTaskType(nextTaskType);
    const nextParams = new URLSearchParams(searchParams.toString());
    if (nextTaskType) {
      nextParams.set("taskType", nextTaskType);
    } else {
      nextParams.delete("taskType");
    }
    const query = nextParams.toString();
    router.replace(query ? `/agent?${query}` : "/agent", { scroll: false });
  }, [router, searchParams]);

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
        sessionsCollapsedEffective && "agent-workspace--sessions-collapsed",
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

      <section className="agent-workspace-main" aria-label="珂朵莉对话舞台">
        <div className="agent-workspace-main-live2d">
          <AgentLive2DStage />
        </div>
        <div className="agent-workspace-main-chat">
          {activeSessionContext?.contextKey.startsWith("post:") && (
            <div className="agent-article-context" role="status">
              正在陪读《{activeSessionContext.contextTitle}》
            </div>
          )}
          <AgentSkillModeSelector value={taskType} onChange={changeTaskType} />
          <AgentChatPanel
            variant="workspace"
            taskType={taskType}
            placeholder={activeSkillMode?.placeholder}
          />
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
