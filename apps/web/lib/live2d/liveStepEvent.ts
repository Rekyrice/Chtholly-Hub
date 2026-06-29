/** 从 liveSteps 条目解析 Agent 事件类型（与 AgentChatProvider.pushStep 前缀一致） */
export type LiveStepEventKind = "think" | "act" | "observe";

export function parseLiveStepEvent(line: string): LiveStepEventKind | null {
  if (line.startsWith("💭")) return "think";
  if (line.startsWith("🔧")) return "act";
  if (line.startsWith("👁")) return "observe";
  return null;
}
