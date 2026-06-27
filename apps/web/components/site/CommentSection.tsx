"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { isLoggedIn } from "@/lib/auth/tokens";
import { commentService } from "@/lib/services/commentService";
import { siteConfig } from "@/lib/site.config";
import type { CommentItem } from "@/lib/types/comment";
import { formatDate } from "@/lib/utils";

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
        className="w-full px-3 py-2 text-sm border outline-none focus:border-[#009688] resize-y"
        style={{ borderColor: "#e0e0e0", color: "#424242" }}
      />
      {error && (
        <p className="mt-1 text-xs" style={{ color: "#e53935" }}>
          {error}
        </p>
      )}
      <div className="mt-2 flex gap-2">
        <button
          type="submit"
          disabled={submitting || !content.trim()}
          className="px-4 py-1.5 text-sm text-white disabled:opacity-50"
          style={{ backgroundColor: siteConfig.theme.primary }}
        >
          {submitting ? "发送中…" : submitLabel}
        </button>
        {onCancel && (
          <button
            type="button"
            onClick={onCancel}
            className="px-3 py-1.5 text-sm"
            style={{ color: "#757575" }}
          >
            取消
          </button>
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
    <div
      className={isReply ? "ml-8 mt-3 pt-3 border-t" : ""}
      style={isReply ? { borderColor: "#f0f0f0" } : undefined}
    >
      <div className="flex items-baseline gap-2 flex-wrap">
        <span className="text-sm font-medium" style={{ color: "#424242" }}>
          {comment.authorNickname}
        </span>
        <span className="text-xs" style={{ color: "#9e9e9e" }}>
          {formatDate(comment.createdAt)}
        </span>
      </div>
      <p
        className="mt-1 text-sm leading-relaxed whitespace-pre-wrap"
        style={{ color: "#616161" }}
      >
        {comment.content}
      </p>
      {canReply && onReply && (
        <button
          type="button"
          onClick={onReply}
          className="mt-1 text-xs hover:underline"
          style={{ color: siteConfig.theme.primary }}
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
      <h2 className="text-lg font-medium mb-4" style={{ color: "#424242" }}>
        评论 {total > 0 ? `(${total})` : ""}
      </h2>

      {loading ? (
        <p className="text-sm" style={{ color: "#9e9e9e" }}>
          加载中…
        </p>
      ) : items.length === 0 ? (
        <p className="text-sm mb-4" style={{ color: "#9e9e9e" }}>
          暂无评论，来抢沙发吧。
        </p>
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
        <p className="text-sm" style={{ color: "#757575" }}>
          <Link href="/login" style={{ color: siteConfig.theme.primary }}>
            登录
          </Link>
          后参与讨论
        </p>
      )}
    </section>
  );
}
