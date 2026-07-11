"use client";

import { usePathname } from "next/navigation";
import { ProactiveNotification } from "@/components/ProactiveNotification";
import { AgentChatProvider } from "@/components/agent/AgentChatProvider";
import FloatingAgent from "@/components/agent/FloatingAgent";
import { getAgentRuntimePolicy } from "@/components/agent/agentRuntimePolicy";

export default function AuthenticatedAgentRuntime() {
  const pathname = usePathname();
  const policy = getAgentRuntimePolicy(pathname);

  if (!policy.proactive && !policy.floating) return null;

  return (
    <AgentChatProvider>
      {policy.proactive && <ProactiveNotification />}
      {policy.floating && <FloatingAgent />}
    </AgentChatProvider>
  );
}
