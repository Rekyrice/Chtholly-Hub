"use client";

import { X } from "lucide-react";
import { useEffect } from "react";
import { useAgentChatContext } from "@/components/agent/AgentChatProvider";
import { ChthollyIllustration } from "@/components/site/ChthollyIllustration";

export function ProactiveNotification() {
  const { visibleProactiveNotification, dismissProactiveNotification } = useAgentChatContext();

  useEffect(() => {
    if (!visibleProactiveNotification) return undefined;
    const timer = window.setTimeout(dismissProactiveNotification, 8000);
    return () => window.clearTimeout(timer);
  }, [dismissProactiveNotification, visibleProactiveNotification]);

  if (!visibleProactiveNotification) return null;

  return (
    <div className="proactive-notification" role="alert" aria-live="polite">
      <ChthollyIllustration size="xs" state="speaking" className="proactive-notification__avatar" />
      <div className="proactive-notification__content">
        <p>{visibleProactiveNotification.message}</p>
      </div>
      <button
        type="button"
        className="proactive-notification__close"
        onClick={dismissProactiveNotification}
        aria-label="关闭"
      >
        <X aria-hidden size={16} />
      </button>
    </div>
  );
}
