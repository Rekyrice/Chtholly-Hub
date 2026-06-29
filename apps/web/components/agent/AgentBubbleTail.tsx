"use client";

import { cn } from "@/lib/utils";

type AgentBubbleTailProps = {
  className?: string;
};

/**
 * 漫画式对话框尾巴（向左下指向珂朵莉）
 * 双层 path：外层描边 + 内层填充，颜色继承气泡 --bubble-fill / --bubble-stroke
 */
export default function AgentBubbleTail({ className }: AgentBubbleTailProps) {
  return (
    <svg
      className={cn("agent-bubble-tail", className)}
      viewBox="0 0 52 60"
      width="52"
      height="60"
      aria-hidden="true"
      focusable="false"
    >
      <path
        className="agent-bubble-tail-stroke"
        d="M46 2
           L46 14
           C38 16, 30 20, 24 28
           C16 38, 10 48, 2 58
           C12 46, 20 34, 30 24
           C36 16, 42 8, 46 2
           Z"
      />
      <path
        className="agent-bubble-tail-fill"
        d="M45 3
           L45 13
           C38 15, 30 19, 24 27
           C17 36, 12 46, 4 56
           C13 44, 21 33, 29 23
           C35 16, 41 9, 45 3
           Z"
      />
    </svg>
  );
}
