import AgentChat from "@/components/agent/AgentChat";
import { siteConfig } from "@/lib/site.config";

export const metadata = {
  title: `Agent · ${siteConfig.name}`,
  description: "与珂朵莉对话，搜索站内动漫知识",
};

export default function AgentPage() {
  return <AgentChat />;
}
