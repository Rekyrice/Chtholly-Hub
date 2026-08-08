"use client";

import type { AgentTaskType } from "@/lib/types/agent";
import { cn } from "@/lib/utils";

export const AGENT_SKILL_MODES: ReadonlyArray<{
  taskType: AgentTaskType;
  label: string;
  description: string;
  placeholder: string;
}> = [
  {
    taskType: "page-explain",
    label: "页面解释",
    description: "解释当前文章或明确概念",
    placeholder: "想解释页面里的哪一部分？",
  },
  {
    taskType: "evidence-outline",
    label: "资料大纲",
    description: "按主题组织文章结构与资料",
    placeholder: "告诉我主题，我会整理一份资料大纲…",
  },
  {
    taskType: "draft-fact-check",
    label: "草稿事实核查",
    description: "逐条核查草稿中的事实主张",
    placeholder: "把要核查的草稿或主张贴在这里…",
  },
];

export function parseAgentTaskType(value: string | null): AgentTaskType | null {
  return AGENT_SKILL_MODES.some((mode) => mode.taskType === value)
    ? value as AgentTaskType
    : null;
}

export function getAgentSkillMode(taskType: AgentTaskType | null) {
  return AGENT_SKILL_MODES.find((mode) => mode.taskType === taskType) ?? null;
}

type AgentSkillModeSelectorProps = {
  value: AgentTaskType | null;
  onChange: (value: AgentTaskType | null) => void;
};

export default function AgentSkillModeSelector({
  value,
  onChange,
}: AgentSkillModeSelectorProps) {
  return (
    <div className="agent-skill-mode-bar">
      <div className="agent-skill-mode-selector" role="group" aria-label="Agent 任务模式">
        {AGENT_SKILL_MODES.map((mode) => (
          <button
            key={mode.taskType}
            type="button"
            className={cn(
              "agent-skill-mode-button",
              value === mode.taskType && "agent-skill-mode-button--active",
            )}
            aria-pressed={value === mode.taskType}
            title={mode.description}
            onClick={() => onChange(value === mode.taskType ? null : mode.taskType)}
          >
            {mode.label}
          </button>
        ))}
      </div>
      {value && (
        <button
          type="button"
          className="agent-skill-mode-cancel"
          onClick={() => onChange(null)}
        >
          退出任务模式
        </button>
      )}
    </div>
  );
}
