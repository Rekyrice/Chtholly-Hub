import { Suspense } from "react";
import AgentWorkspace from "@/components/agent/AgentWorkspace";
import { siteConfig } from "@/lib/site.config";

export const metadata = {
  title: `Agent · ${siteConfig.name}`,
  description: "与珂朵莉深度对话，搜索站内动漫知识",
};

export default function AgentPage() {
  return (
    <Suspense fallback={<div className="agent-workspace-loading p-8 text-center text-text-secondary">加载工作台…</div>}>
      <AgentWorkspace />
    </Suspense>
  );
}
