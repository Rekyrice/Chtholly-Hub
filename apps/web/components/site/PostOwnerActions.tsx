"use client";

import { MoreHorizontal, Pin, PinOff, Trash2 } from "lucide-react";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useRef, useState } from "react";
import { getStoredAuth, isLoggedIn } from "@/lib/auth/tokens";
import { postService } from "@/lib/services/postService";
import { cn } from "@/lib/utils";

type PostOwnerActionsProps = {
  postId: string;
  authorId?: string | number | null;
  title?: string;
  initialTop?: boolean;
  initialVisibility?: string | null;
  compact?: boolean;
  redirectAfterDelete?: string;
  onTopChange?: (top: boolean) => void;
  onVisibilityChange?: (visibility: string) => void;
  onDeleted?: () => void;
};

const VISIBILITY_OPTIONS = [
  { value: "public", label: "公开" },
  { value: "followers", label: "粉丝可见" },
  { value: "school", label: "同校可见" },
  { value: "private", label: "私密" },
  { value: "unlisted", label: "不列出" },
] as const;

function isSameUser(a?: string | number | null, b?: string | number | null) {
  return a != null && b != null && String(a) === String(b);
}

export default function PostOwnerActions({
  postId,
  authorId,
  title = "这篇文章",
  initialTop = false,
  initialVisibility = "public",
  compact = false,
  redirectAfterDelete,
  onTopChange,
  onVisibilityChange,
  onDeleted,
}: PostOwnerActionsProps) {
  const router = useRouter();
  const menuRef = useRef<HTMLDivElement>(null);
  const [isOwner, setIsOwner] = useState(false);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [top, setTop] = useState(initialTop);
  const [visibility, setVisibility] = useState(initialVisibility ?? "public");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const syncOwner = () => {
      const current = getStoredAuth()?.user;
      setIsOwner(Boolean(isLoggedIn() && isSameUser(current?.id, authorId)));
    };

    syncOwner();
    window.addEventListener("chtholly-auth-change", syncOwner);
    return () => window.removeEventListener("chtholly-auth-change", syncOwner);
  }, [authorId]);

  useEffect(() => {
    if (!open) return;
    const onPointerDown = (event: MouseEvent) => {
      if (!menuRef.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener("mousedown", onPointerDown);
    return () => document.removeEventListener("mousedown", onPointerDown);
  }, [open]);

  const currentVisibilityLabel = useMemo(
    () => VISIBILITY_OPTIONS.find((option) => option.value === visibility)?.label ?? visibility,
    [visibility],
  );

  if (!isOwner) {
    return null;
  }

  const handleTop = async () => {
    const next = !top;
    setBusy(true);
    setError(null);
    try {
      await postService.setTop(postId, next);
      setTop(next);
      onTopChange?.(next);
    } catch (err) {
      setError(err instanceof Error ? err.message : "置顶状态修改失败");
    } finally {
      setBusy(false);
    }
  };

  const handleVisibility = async (next: string) => {
    if (next === visibility) return;
    setBusy(true);
    setError(null);
    try {
      await postService.setVisibility(postId, next);
      setVisibility(next);
      onVisibilityChange?.(next);
    } catch (err) {
      setError(err instanceof Error ? err.message : "可见性修改失败");
    } finally {
      setBusy(false);
    }
  };

  const handleDelete = async () => {
    const ok = window.confirm(`确定删除《${title}》吗？这个操作不能撤销。`);
    if (!ok) return;
    setBusy(true);
    setError(null);
    try {
      await postService.remove(postId);
      onDeleted?.();
      setOpen(false);
      if (redirectAfterDelete) {
        router.push(redirectAfterDelete);
      } else {
        router.refresh();
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "删除文章失败");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className={cn("post-owner-actions", compact && "post-owner-actions--compact")} ref={menuRef}>
      <button
        type="button"
        className="post-owner-actions__trigger"
        onClick={() => setOpen((next) => !next)}
        aria-haspopup="menu"
        aria-expanded={open}
        disabled={busy}
      >
        <MoreHorizontal size={17} />
        <span>{compact ? "管理" : "文章操作"}</span>
      </button>

      {open && (
        <div className="post-owner-actions__menu" role="menu">
          <button type="button" role="menuitem" disabled={busy} onClick={() => void handleTop()}>
            {top ? <PinOff size={15} /> : <Pin size={15} />}
            <span>{top ? "取消置顶" : "置顶文章"}</span>
          </button>

          <label className="post-owner-actions__field">
            <span>可见性</span>
            <select
              value={visibility}
              disabled={busy}
              onChange={(event) => void handleVisibility(event.target.value)}
            >
              {VISIBILITY_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
            <small>当前：{currentVisibilityLabel}</small>
          </label>

          <button
            type="button"
            role="menuitem"
            className="post-owner-actions__danger"
            disabled={busy}
            onClick={() => void handleDelete()}
          >
            <Trash2 size={15} />
            <span>删除文章</span>
          </button>

          {error && <p className="post-owner-actions__error">{error}</p>}
        </div>
      )}
    </div>
  );
}
