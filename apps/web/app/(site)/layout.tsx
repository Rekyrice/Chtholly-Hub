import { AgentChatProvider } from "@/components/agent/AgentChatProvider";
import SiteChrome from "@/components/site/SiteChrome";

export default function SiteLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <AgentChatProvider>
      <SiteChrome>{children}</SiteChrome>
    </AgentChatProvider>
  );
}
