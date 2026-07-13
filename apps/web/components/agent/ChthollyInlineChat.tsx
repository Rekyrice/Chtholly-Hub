"use client";

import { useState } from "react";
import Link from "next/link";
import { ExternalLink } from "lucide-react";
import AgentChatPanel from "@/components/agent/AgentChatPanel";
import { useAgentChatContext } from "@/components/agent/AgentChatProvider";

export default function ChthollyInlineChat() {
  const { activeSessionId, loggedIn } = useAgentChatContext();
  const [expanded, setExpanded] = useState(false);

  if (!expanded) {
    return (
      <button type="button" className="room-chat-btn" onClick={() => setExpanded(true)}>
        和珂朵莉聊天
      </button>
    );
  }

  if (!loggedIn) {
    return (
      <div className="room-inline-chat-login" role="status">
        <div>
          <strong>继续聊天前先登录</strong>
          <p>登录后会保留会话，也可以继续进入完整工作台。</p>
        </div>
        <div className="room-inline-chat-login__actions">
          <Link href="/login" className="room-chat-btn">去登录</Link>
          <button type="button" onClick={() => setExpanded(false)}>暂不登录</button>
        </div>
      </div>
    );
  }

  const fullWorkspaceLink = (
    <Link
      href={`/agent?session=${encodeURIComponent(activeSessionId)}`}
      className="room-inline-chat__workspace-link"
      aria-label="全屏打开"
    >
      <ExternalLink size={15} aria-hidden="true" />
      全屏打开
    </Link>
  );

  return (
    <div className="room-inline-chat" aria-label="珂朵莉轻量对话">
      <AgentChatPanel
        variant="room"
        headerAction={fullWorkspaceLink}
        onMinimize={() => setExpanded(false)}
      />
    </div>
  );
}
