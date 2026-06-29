"use client";

import { cn } from "@/lib/utils";

type AgentBubbleTailProps = {
  className?: string;
};

/** 漫画式对话框尾巴：短颈向左指向同舞台内的珂朵莉 */
export default function AgentBubbleTail({ className }: AgentBubbleTailProps) {
  return (
    <svg
      className={cn("agent-bubble-tail", className)}
      viewBox="0 0 44 52"
      width="44"
      height="52"
      aria-hidden="true"
      focusable="false"
    >
      <path
        className="agent-bubble-tail-stroke"
        d="M40 2
           L40 14
           L28 14
           C20 15, 14 20, 8 30
           L2 48
           C8 36, 14 26, 22 20
           C26 16, 32 14, 38 14
           L40 14
           Z"
      />
      <path
        className="agent-bubble-tail-fill"
        d="M39 3
           L39 13
           L28 13
           C21 14, 15 19, 10 28
           L4 45
           C9 34, 15 25, 22 19
           C26 16, 31 14, 38 13
           L39 13
           Z"
      />
    </svg>
  );
}
