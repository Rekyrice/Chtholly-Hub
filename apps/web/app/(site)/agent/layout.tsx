import "../../styles/agent.css";
import { ProactiveNotification } from "@/components/ProactiveNotification";
import { AgentChatProvider } from "@/components/agent/AgentChatProvider";

export default function AgentLayout({ children }: { children: React.ReactNode }) {
  return (
    <AgentChatProvider>
      {children}
      <ProactiveNotification />
    </AgentChatProvider>
  );
}
