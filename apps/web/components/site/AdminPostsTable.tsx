"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { adminService } from "@/lib/services/adminService";
import { postService } from "@/lib/services/postService";
import type { AdminPost } from "@/lib/types/admin";

const PAGE_SIZE = 20;
const VISIBILITY_OPTIONS = [
  { value: "public", label: "公开" },
  { value: "followers", label: "粉丝可见" },
  { value: "school", label: "同校可见" },
  { value: "private", label: "私密" },
  { value: "unlisted", label: "不列出" },
] as const;

export default function AdminPostsTable() {
  const [page, setPage] = useState(1);
  const [posts, setPosts] = useState<AdminPost[]>([]);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busyPostId, setBusyPostId] = useState<string | null>(null);

  const load = useCallback(async (nextPage: number) => {
    setLoading(true);
    setError(null);
    try {
      const response = await postService.feed(nextPage, PAGE_SIZE);
      setPosts(
        response.items.map((post) => ({
          ...post,
          visible: "visible" in post ? String(post.visible ?? "public") : "public",
        })),
      );
      setHasMore(response.hasMore);
    } catch (err) {
      setError(err instanceof Error ? err.message : "文章列表加载失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load(page);
  }, [load, page]);

  const updatePost = (postId: string, patch: Partial<AdminPost>) => {
    setPosts((current) => current.map((post) => (post.id === postId ? { ...post, ...patch } : post)));
  };

  const handleVisibility = async (post: AdminPost, visibility: string) => {
    if (visibility === post.visible) return;
    setBusyPostId(post.id);
    try {
      await adminService.setPostVisibility(post.id, visibility);
      updatePost(post.id, { visible: visibility });
    } catch (err) {
      setError(err instanceof Error ? err.message : "可见性修改失败");
    } finally {
      setBusyPostId(null);
    }
  };

  const handleDelete = async (post: AdminPost) => {
    const ok = window.confirm(`确定删除《${post.title}》吗？这个操作不能撤销。`);
    if (!ok) return;
    setBusyPostId(post.id);
    try {
      await adminService.deletePost(post.id);
      setPosts((current) => current.filter((item) => item.id !== post.id));
    } catch (err) {
      setError(err instanceof Error ? err.message : "删除文章失败");
    } finally {
      setBusyPostId(null);
    }
  };

  return (
    <div className="admin-page">
      <header className="admin-page__header">
        <div>
          <p className="admin-page__eyebrow">Content</p>
          <h1>内容管理</h1>
          <p>管理公开内容的可见性，并处理需要下架的文章。</p>
        </div>
      </header>

      {error && <div className="admin-alert">{error}</div>}

      <section className="admin-panel">
        <div className="admin-toolbar">
          <span className="admin-toolbar__meta">文章列表来自当前公开 feed，管理操作走 Admin API。</span>
        </div>

        <div className="admin-table-wrap" aria-busy={loading}>
          <table className="admin-table">
            <thead>
              <tr>
                <th>文章</th>
                <th>作者</th>
                <th>互动</th>
                <th>可见性</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr>
                  <td colSpan={5} className="admin-table__empty">
                    加载中...
                  </td>
                </tr>
              ) : posts.length === 0 ? (
                <tr>
                  <td colSpan={5} className="admin-table__empty">
                    暂无文章
                  </td>
                </tr>
              ) : (
                posts.map((post) => (
                  <tr key={post.id}>
                    <td>
                      <div className="admin-post-cell">
                        <Link href={`/posts/${post.slug}`}>{post.title}</Link>
                        <small>{post.description || "没有摘要"}</small>
                      </div>
                    </td>
                    <td>{post.authorNickname || post.authorHandle || "-"}</td>
                    <td>
                      <div className="admin-muted-stack">
                        <span>{post.likeCount ?? 0} 赞</span>
                        <small>{post.commentCount ?? 0} 评论 · {post.favoriteCount ?? 0} 收藏</small>
                      </div>
                    </td>
                    <td>
                      <select
                        className="admin-select"
                        value={post.visible ?? "public"}
                        disabled={busyPostId === post.id}
                        onChange={(event) => void handleVisibility(post, event.target.value)}
                      >
                        {VISIBILITY_OPTIONS.map((option) => (
                          <option key={option.value} value={option.value}>
                            {option.label}
                          </option>
                        ))}
                      </select>
                    </td>
                    <td>
                      <button
                        type="button"
                        className="admin-btn admin-btn--danger"
                        disabled={busyPostId === post.id}
                        onClick={() => void handleDelete(post)}
                      >
                        删除
                      </button>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>

        <div className="admin-pagination">
          <button type="button" disabled={page <= 1 || loading} onClick={() => setPage((p) => p - 1)}>
            上一页
          </button>
          <span>第 {page} 页</span>
          <button type="button" disabled={!hasMore || loading} onClick={() => setPage((p) => p + 1)}>
            下一页
          </button>
        </div>
      </section>
    </div>
  );
}
