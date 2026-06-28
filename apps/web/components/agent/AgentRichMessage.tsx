"use client";

import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { cn } from "@/lib/utils";

export function AgentRichMessage({ content }: { content: string }) {
  return (
    <div className="agent-rich-markdown text-sm leading-relaxed">
      <ReactMarkdown remarkPlugins={[remarkGfm]}>{content}</ReactMarkdown>
    </div>
  );
}

export function stepTone(line: string) {
  if (line.startsWith("💭")) return "agent-step-think";
  if (line.startsWith("🔧")) return "agent-step-act";
  return "agent-step-observe";
}

export function AgentSteps({ steps }: { steps: string[] }) {
  return (
    <details className="agent-steps mt-2 text-xs">
      <summary className="cursor-pointer select-none text-text-secondary">
        推理步骤 ({steps.length})
      </summary>
      <ul className="agent-steps-body mt-1 space-y-1">
        {steps.map((line, i) => (
          <li key={i} className={cn("whitespace-pre-wrap font-sans leading-relaxed", stepTone(line))}>
            {line}
          </li>
        ))}
      </ul>
    </details>
  );
}
