"use client";

import { MessageCircle, X } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import AgentChatPanel from "@/components/agent/AgentChatPanel";
import { useAgentChat } from "@/hooks/useAgentChat";
import { getStoredAuth } from "@/lib/auth/tokens";
import { cn } from "@/lib/utils";

export default function FloatingAgent() {
  const [loggedIn, setLoggedIn] = useState(false);
  const [open, setOpen] = useState(false);

  const syncAuth = useCallback(() => {
    setLoggedIn(!!getStoredAuth()?.user);
  }, []);

  useEffect(() => {
    syncAuth();
    window.addEventListener("chtholly-auth-change", syncAuth);
    return () => window.removeEventListener("chtholly-auth-change", syncAuth);
  }, [syncAuth]);

  const chat = useAgentChat({ enabled: loggedIn && open });
  const { connected } = chat;

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);

  if (!loggedIn) return null;

  return (
    <div className="floating-agent-root" data-testid="floating-agent">
      {open && (
        <button
          type="button"
          className="floating-agent-backdrop md:hidden"
          aria-label="关闭 Agent 面板"
          onClick={() => setOpen(false)}
        />
      )}

      <div
        className={cn(
          "floating-agent-panel",
          open && "floating-agent-panel--open",
        )}
        aria-hidden={!open}
      >
        {open && (
          <AgentChatPanel
            connected={connected}
            chat={chat}
            onMinimize={() => setOpen(false)}
          />
        )}
      </div>

      <button
        type="button"
        className={cn("floating-agent-fab", open && "floating-agent-fab--open")}
        onClick={() => setOpen((v) => !v)}
        aria-label={open ? "关闭珂朵莉 Agent" : "打开珂朵莉 Agent"}
        aria-expanded={open}
        data-testid="floating-agent-toggle"
      >
        {open ? <X size={24} /> : <MessageCircle size={26} />}
      </button>
    </div>
  );
}
