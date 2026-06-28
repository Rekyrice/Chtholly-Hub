"use client";

import { ChevronLeft, ChevronRight } from "lucide-react";
import { cn } from "@/lib/utils";

type AgentSidebarCollapseBtnProps = {
  side: "left" | "right";
  collapsed: boolean;
  onToggle: () => void;
  label: string;
  testId: string;
};

export default function AgentSidebarCollapseBtn({
  side,
  collapsed,
  onToggle,
  label,
  testId,
}: AgentSidebarCollapseBtnProps) {
  const Icon = side === "left" ? (collapsed ? ChevronRight : ChevronLeft) : collapsed ? ChevronLeft : ChevronRight;

  return (
    <button
      type="button"
      className={cn(
        "agent-sidebar-collapse-btn",
        side === "left" ? "agent-sidebar-collapse-btn--left" : "agent-sidebar-collapse-btn--right",
      )}
      onClick={onToggle}
      aria-label={label}
      aria-expanded={!collapsed}
      data-testid={testId}
    >
      <Icon size={16} strokeWidth={2.25} />
    </button>
  );
}
