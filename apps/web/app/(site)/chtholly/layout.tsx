import "../../styles/agent.css";
import { AgentChatProvider } from "@/components/agent/AgentChatProvider";

export default function ChthollyLayout({ children }: { children: React.ReactNode }) {
  return <AgentChatProvider>{children}</AgentChatProvider>;
}
