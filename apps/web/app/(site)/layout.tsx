import { AgentChatProvider } from "@/components/agent/AgentChatProvider";
import { ProactiveNotification } from "@/components/ProactiveNotification";
import SiteChrome from "@/components/site/SiteChrome";

export default function SiteLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <AgentChatProvider>
      <SiteChrome>{children}</SiteChrome>
      <ProactiveNotification />
    </AgentChatProvider>
  );
}
