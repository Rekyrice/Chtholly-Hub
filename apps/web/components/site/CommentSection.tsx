"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { Button } from "@/components/ui/Button";
import { isLoggedIn } from "@/lib/auth/tokens";
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
      setError(err instanceof Error ? err.message : "发送失败");
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
  return (
    <div className={cn(isReply && "ml-8 mt-3 pt-3 border-t border-border")}>
      <div className="flex items-baseline gap-2 flex-wrap">
        <span className="text-sm font-medium text-text">{comment.authorNickname}</span>
        <span className="text-xs text-text-secondary">{formatDate(comment.createdAt)}</span>
      </div>
      <p className="mt-1 text-sm leading-relaxed whitespace-pre-wrap text-text-secondary">
        {comment.content}
      </p>
      {canReply && onReply && (
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
  const [loggedIn, setLoggedIn] = useState(false);

  const load = useCallback(async () => {
    try {
      const res = await commentService.list(postId);
      setItems(res.items);
      setTotal(res.total);
    } catch {
      setItems([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, [postId]);

  useEffect(() => {
    void load();
    setLoggedIn(isLoggedIn());
    const sync = () => setLoggedIn(isLoggedIn());
    window.addEventListener("chtholly-auth-change", sync);
    return () => window.removeEventListener("chtholly-auth-change", sync);
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
