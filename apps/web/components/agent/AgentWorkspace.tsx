"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect } from "react";
import AgentChatPanel from "@/components/agent/AgentChatPanel";
import AgentLive2DStage from "@/components/agent/AgentLive2DStage";
import AgentSessionSidebar from "@/components/agent/AgentSessionSidebar";
import { useAgentChatContext } from "@/components/agent/AgentChatProvider";
import { Button } from "@/components/ui/Button";
import { EmptyState } from "@/components/ui/EmptyState";

export default function AgentWorkspace() {
  const { loggedIn, activeSessionId, switchSession } = useAgentChatContext();
  const searchParams = useSearchParams();
  const router = useRouter();
  const sessionParam = searchParams.get("session");

  useEffect(() => {
    if (!sessionParam || sessionParam === activeSessionId) return;
    switchSession(sessionParam);
  }, [sessionParam, activeSessionId, switchSession]);

  useEffect(() => {
    if (!sessionParam && activeSessionId) {
      router.replace(`/agent?session=${encodeURIComponent(activeSessionId)}`, {
        scroll: false,
      });
    }
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
    <div className="agent-workspace" data-testid="agent-workspace">
      <div className="agent-workspace-stage">
        <AgentLive2DStage />
      </div>
      <div className="agent-workspace-main">
        <AgentSessionSidebar />
        <div className="agent-workspace-chat-shell">
          <AgentChatPanel variant="workspace" />
        </div>
      </div>
    </div>
  );
}
