import { AgentChatProvider } from "@/components/agent/AgentChatProvider";
import SiteChrome from "@/components/site/SiteChrome";
import SiteNotFound from "./(site)/not-found";

export default function NotFound() {
  return (
    <AgentChatProvider>
      <SiteChrome>
        <SiteNotFound />
      </SiteChrome>
    </AgentChatProvider>
  );
}
