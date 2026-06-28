"use client";

import { MoreVertical } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import type { AgentSessionRecord } from "@/lib/agent/sessions";
import { cn } from "@/lib/utils";

type AgentSessionItemProps = {
  session: AgentSessionRecord;
  active: boolean;
  onSelect: () => void;
  onRename: (title: string) => void;
  onDelete: () => void;
};

export default function AgentSessionItem({
  session,
  active,
  onSelect,
  onRename,
  onDelete,
}: AgentSessionItemProps) {
  const [menuOpen, setMenuOpen] = useState(false);
  const [renaming, setRenaming] = useState(false);
  const [draftTitle, setDraftTitle] = useState(session.title);
  const menuRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    setDraftTitle(session.title);
  }, [session.title]);

  useEffect(() => {
    if (!renaming) return;
    inputRef.current?.focus();
    inputRef.current?.select();
  }, [renaming]);

  useEffect(() => {
    if (!menuOpen) return;
    const onDocClick = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setMenuOpen(false);
      }
    };
    document.addEventListener("mousedown", onDocClick);
    return () => document.removeEventListener("mousedown", onDocClick);
  }, [menuOpen]);

  const commitRename = () => {
    const trimmed = draftTitle.trim();
    if (trimmed) {
      onRename(trimmed);
    } else {
      setDraftTitle(session.title);
    }
    setRenaming(false);
    setMenuOpen(false);
  };

  return (
    <li
      className={cn("agent-session-row", active && "agent-session-row--active")}
      data-testid={`agent-session-${session.id}`}
    >
      <button type="button" className="agent-session-item" onClick={onSelect}>
        {renaming ? (
          <input
            ref={inputRef}
            className="agent-session-rename-input"
            value={draftTitle}
            maxLength={48}
            onChange={(e) => setDraftTitle(e.target.value)}
            onClick={(e) => e.stopPropagation()}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                commitRename();
              }
              if (e.key === "Escape") {
                e.preventDefault();
                setDraftTitle(session.title);
                setRenaming(false);
              }
            }}
            onBlur={commitRename}
            aria-label="重命名会话"
          />
        ) : (
          <>
            <span className="block truncate text-sm">{session.title}</span>
            <span className="block text-xs text-text-secondary mt-0.5">
              {new Date(session.updatedAt).toLocaleDateString("zh-CN")}
            </span>
          </>
        )}
      </button>

      {!renaming && (
        <div className="agent-session-menu-wrap" ref={menuRef}>
          <button
            type="button"
            className="agent-session-menu-btn"
            aria-label="会话操作"
            aria-expanded={menuOpen}
            onClick={(e) => {
              e.stopPropagation();
              setMenuOpen((v) => !v);
            }}
          >
            <MoreVertical size={15} />
          </button>

          {menuOpen && (
            <div className="agent-session-menu" role="menu">
              <button
                type="button"
                className="agent-session-menu-item"
                role="menuitem"
                onClick={(e) => {
                  e.stopPropagation();
                  setMenuOpen(false);
                  setRenaming(true);
                }}
              >
                重命名
              </button>
              <button
                type="button"
                className="agent-session-menu-item agent-session-menu-item--danger"
                role="menuitem"
                onClick={(e) => {
                  e.stopPropagation();
                  setMenuOpen(false);
                  onDelete();
                }}
              >
                删除
              </button>
            </div>
          )}
        </div>
      )}
    </li>
  );
}
