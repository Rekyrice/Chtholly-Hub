"use client";

import { cn } from "@/lib/utils";

type AgentBubbleTailProps = {
  className?: string;
};

/** 漫画式对话框尾巴：粗颈 + 左下指向，与气泡左下角衔接 */
export default function AgentBubbleTail({ className }: AgentBubbleTailProps) {
  return (
    <svg
      className={cn("agent-bubble-tail", className)}
      viewBox="0 0 56 68"
      width="56"
      height="68"
      aria-hidden="true"
      focusable="false"
    >
      <path
        className="agent-bubble-tail-stroke"
        d="M50 2
           L50 22
           L36 22
           C26 24, 18 32, 10 46
           C6 54, 2 62, 2 66
           C8 56, 16 42, 24 32
           C30 24, 38 20, 48 20
           L50 20
           Z"
      />
      <path
        className="agent-bubble-tail-fill"
        d="M49 3
           L49 21
           L36 21
           C27 23, 19 31, 12 44
           C8 51, 4 58, 4 63
           C10 53, 17 41, 24 31
           C29 24, 37 21, 48 21
           L49 21
           Z"
      />
    </svg>
  );
}
