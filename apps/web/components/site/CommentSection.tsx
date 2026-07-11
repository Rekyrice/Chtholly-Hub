"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { extractErrorMessage } from "@/lib/hooks/useErrorMessage";
import { ChthollyIllustration } from "@/components/site/ChthollyIllustration";
import { Button } from "@/components/ui/Button";
import { useStoredAuth } from "@/lib/auth/auth-store";
import { commentService } from "@/lib/services/commentService";
import type { CommentItem } from "@/lib/types/comment";
import { cn, formatDate } from "@/lib/utils";

interface Props {
  postId: string;
}

function CommentForm({
  placeholder,
  submitLabel,
  onSubmit,
  onCancel,
}: {
  placeholder: string;
  submitLabel: string;
  onSubmit: (content: string) => Promise<void>;
  onCancel?: () => void;
}) {
  const [content, setContent] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const text = content.trim();
    if (!text) return;
    setSubmitting(true);
    setError(null);
    try {
      await onSubmit(text);
      setContent("");
    } catch (err) {
      setError(extractErrorMessage(err, "发送失败"));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="mt-3">
      <textarea
        value={content}
        onChange={(e) => setContent(e.target.value)}
        placeholder={placeholder}
        rows={3}
        maxLength={2000}
        className="field-input text-sm resize-y min-h-[80px]"
      />
      {error && <p className="mt-1 text-xs text-error">{error}</p>}
      <div className="mt-2 flex gap-2">
        <Button type="submit" size="sm" loading={submitting} disabled={!content.trim()}>
          {submitLabel}
        </Button>
        {onCancel && (
          <Button type="button" variant="ghost" size="sm" onClick={onCancel}>
            取消
          </Button>
        )}
      </div>
    </form>
  );
}

function CommentBubble({
  comment,
  isReply,
  onReply,
  canReply,
}: {
  comment: CommentItem;
  isReply?: boolean;
  onReply?: () => void;
  canReply?: boolean;
}) {
  const isChtholly = comment.chtholly;

  return (
    <div
      className={cn(
        isReply && "ml-8 mt-3 pt-3 border-t border-border",
        isChtholly && "comment-chtholly",
      )}
    >
      <div className="comment-header flex items-center gap-2 flex-wrap">
        {isChtholly && <ChthollyIllustration size="xs" state="calm" />}
        <span className="comment-author text-sm font-medium text-text">
          {isChtholly ? "珂朵莉" : comment.authorNickname || "用户"}
        </span>
        {isChtholly && <span className="comment-badge">珂朵莉的想法</span>}
        <span className="comment-time text-xs text-text-secondary">{formatDate(comment.createdAt)}</span>
      </div>
      <p className="comment-text mt-1 text-sm leading-relaxed whitespace-pre-wrap text-text-secondary">
        {comment.content}
      </p>
      {canReply && onReply && !isChtholly && (
        <button
          type="button"
          onClick={onReply}
          className="mt-1 text-xs text-sky hover:underline bg-transparent border-0 cursor-pointer transition-colors duration-150"
        >
          回复
        </button>
      )}
    </div>
  );
}

export default function CommentSection({ postId }: Props) {
  const [items, setItems] = useState<CommentItem[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [replyTarget, setReplyTarget] = useState<string | null>(null);
  const loggedIn = useStoredAuth() !== null;

  const load = useCallback(async (isAlive: () => boolean = () => true) => {
    try {
      const res = await commentService.list(postId);
      if (!isAlive()) return;
      setItems(res.items);
      setTotal(res.total);
    } catch {
      if (!isAlive()) return;
      setItems([]);
      setTotal(0);
    } finally {
      if (isAlive()) setLoading(false);
    }
  }, [postId]);

  useEffect(() => {
    let alive = true;
    const timer = window.setTimeout(() => void load(() => alive), 0);
    return () => {
      alive = false;
      window.clearTimeout(timer);
    };
  }, [load]);

  const handleCreate = async (content: string, parentId?: string) => {
    await commentService.create(postId, { content, parentId });
    setReplyTarget(null);
    await load();
  };

  return (
    <section className="post-card mt-8 p-6 lg:p-8">
      <h2 className="text-lg font-medium mb-4 text-text">
        评论 {total > 0 ? `(${total})` : ""}
      </h2>

      {loading ? (
        <p className="text-sm text-text-secondary">加载中…</p>
      ) : items.length === 0 ? (
        <p className="text-sm mb-4 text-text-secondary">暂无评论，来抢沙发吧。</p>
      ) : (
        <div className="space-y-5 mb-6">
          {items.map((comment) => (
            <div key={comment.id}>
              <CommentBubble
                comment={comment}
                canReply={loggedIn}
                onReply={() =>
                  setReplyTarget(replyTarget === comment.id ? null : comment.id)
                }
              />
              {comment.replies.map((reply) => (
                <CommentBubble key={reply.id} comment={reply} isReply />
              ))}
              {replyTarget === comment.id && loggedIn && (
                <CommentForm
                  placeholder="写下你的回复…"
                  submitLabel="回复"
                  onCancel={() => setReplyTarget(null)}
                  onSubmit={(content) => handleCreate(content, comment.id)}
                />
              )}
            </div>
          ))}
        </div>
      )}

      {loggedIn ? (
        <CommentForm
          placeholder="写下你的评论…"
          submitLabel="发表评论"
          onSubmit={(content) => handleCreate(content)}
        />
      ) : (
        <p className="text-sm text-text-secondary">
          <Link href="/login" className="text-sky hover:text-sky-deep transition-colors duration-150">
            登录
          </Link>
          后参与讨论
        </p>
      )}
    </section>
  );
}
