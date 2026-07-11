"use client";

import { usePathname } from "next/navigation";
import { ProactiveNotification } from "@/components/ProactiveNotification";
import { AgentChatProvider } from "@/components/agent/AgentChatProvider";
import FloatingAgent from "@/components/agent/FloatingAgent";

type AgentRuntimeVisibility = {
  proactive: boolean;
  floating: boolean;
};

export function getAgentRuntimeVisibility(pathname: string): AgentRuntimeVisibility {
  if (pathname === "/agent" || pathname.startsWith("/agent/")) {
    return { proactive: false, floating: false };
  }

  return {
    proactive: true,
    floating: pathname !== "/" && pathname !== "/write",
  };
}

export default function AuthenticatedAgentRuntime() {
  const pathname = usePathname();
  const visibility = getAgentRuntimeVisibility(pathname);

  if (!visibility.proactive && !visibility.floating) return null;

  return (
    <AgentChatProvider>
      {visibility.proactive && <ProactiveNotification />}
      {visibility.floating && <FloatingAgent />}
    </AgentChatProvider>
  );
}
