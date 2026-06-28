"use client";

import { Settings } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useAgentChatContext } from "@/components/agent/AgentChatProvider";
import { cn } from "@/lib/utils";

function SettingToggle({
  label,
  description,
  checked,
  onChange,
}: {
  label: string;
  description?: string;
  checked: boolean;
  onChange: (value: boolean) => void;
}) {
  return (
    <label className="agent-settings-toggle">
      <span className="agent-settings-toggle-text">
        <span className="agent-settings-toggle-label">{label}</span>
        {description && (
          <span className="agent-settings-toggle-desc">{description}</span>
        )}
      </span>
      <input
        type="checkbox"
        className="agent-settings-toggle-input"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
      />
      <span className="agent-settings-toggle-track" aria-hidden="true" />
    </label>
  );
}

export default function AgentWorkspaceSettings() {
  const {
    showSteps,
    setShowSteps,
    workspaceDark,
    setWorkspaceDark,
    richMarkdown,
    setRichMarkdown,
    messages,
    busy,
    clearConversation,
  } = useAgentChatContext();
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDocClick = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener("mousedown", onDocClick);
    return () => document.removeEventListener("mousedown", onDocClick);
  }, [open]);

  return (
    <div className="relative" ref={rootRef}>
      <button
        type="button"
        className={cn("floating-agent-icon-btn", open && "agent-settings-trigger--open")}
        aria-label="工作台设置"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        <Settings size={16} />
      </button>

      {open && (
        <div className="agent-settings-panel" role="dialog" aria-label="工作台设置">
          <p className="agent-settings-panel-title">工作台设置</p>

          <div className="agent-settings-section">
            <SettingToggle
              label="暗色模式"
              description="对话区与历史会话使用深色背景"
              checked={workspaceDark}
              onChange={setWorkspaceDark}
            />
            <SettingToggle
              label="显示推理步骤"
              description="在回复下方展示工具调用过程"
              checked={showSteps}
              onChange={setShowSteps}
            />
            <SettingToggle
              label="Markdown 富文本"
              description="助手回复支持标题、列表等格式"
              checked={richMarkdown}
              onChange={setRichMarkdown}
            />
          </div>

          <div className="agent-settings-divider" />

          <button
            type="button"
            className="agent-settings-action"
            disabled={busy || messages.length === 0}
            onClick={() => {
              clearConversation();
              setOpen(false);
            }}
          >
            清空当前对话
          </button>
        </div>
      )}
    </div>
  );
}
