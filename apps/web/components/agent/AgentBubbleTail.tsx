"use client";

import { cn } from "@/lib/utils";

type AgentBubbleTailProps = {
  className?: string;
};

/** 漫画式对话框尾巴，指向左侧珂朵莉方向 */
export default function AgentBubbleTail({ className }: AgentBubbleTailProps) {
  return (
    <svg
      className={cn("agent-bubble-tail", className)}
      viewBox="0 0 40 48"
      width="40"
      height="48"
      aria-hidden="true"
      focusable="false"
    >
      {/* 外层描边 */}
      <path
        className="agent-bubble-tail-stroke"
        d="M34 2
           C34 10, 26 14, 22 18
           C14 24, 8 34, 2 46
           C10 36, 18 24, 26 16
           C30 10, 32 6, 34 2
           Z"
      />
      {/* 内层填充 */}
      <path
        className="agent-bubble-tail-fill"
        d="M33 3
           C33 10, 26 13, 22 17
           C15 22, 10 32, 4 44
           C11 34, 17 24, 25 16
           C29 11, 31 7, 33 3
           Z"
      />
    </svg>
  );
}
