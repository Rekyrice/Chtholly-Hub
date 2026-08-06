"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { Heart, MessageSquareText, Newspaper, ScrollText } from "lucide-react";
import PostCard from "@/components/site/PostCard";
import PostOwnerActions from "@/components/site/PostOwnerActions";
import UserCommentActivityList from "@/components/site/UserCommentActivityList";
import { EmptyState } from "@/components/ui/EmptyState";
import { getStoredAuth } from "@/lib/auth/tokens";
import { commentService } from "@/lib/services/commentService";
import { postService } from "@/lib/services/postService";
import type { UserCommentActivityPage } from "@/lib/types/comment";
import type { FeedItem } from "@/lib/types/post";
import { cn } from "@/lib/utils";

type UserTabKey = "overview" | "posts" | "comments" | "likes";

type UserTabsProps = {
  posts: FeedItem[];
  displayName: string;
  userId: string | number;
  userHandle?: string | null;
  initialComments: UserCommentActivityPage;
  commentsInitialLoadFailed: boolean;
};

const TABS: Array<{ key: UserTabKey; label: string; icon: typeof ScrollText }> = [
  { key: "overview", label: "概览", icon: ScrollText },
  { key: "posts", label: "文章", icon: Newspaper },
  { key: "comments", label: "评论", icon: MessageSquareText },
  { key: "likes", label: "点赞", icon: Heart },
];

type CommentLoadError = {
  message: string;
  mode: "initial" | "more";
};

export default function UserTabs({
  posts,
  displayName,
  userId,
  userHandle,
  initialComments,
  commentsInitialLoadFailed,
}: UserTabsProps) {
  const [activeTab, setActiveTab] = useState<UserTabKey>("overview");
  const [items, setItems] = useState<FeedItem[]>(posts);
  const [isOwnProfile, setIsOwnProfile] = useState(false);
  const [loadingMine, setLoadingMine] = useState(false);
  const [mineError, setMineError] = useState<string | null>(null);
  const [commentItems, setCommentItems] = useState(initialComments.items);
  const [commentPage, setCommentPage] = useState(initialComments.page);
  const [commentsHasMore, setCommentsHasMore] = useState(initialComments.hasMore);
  const [commentError, setCommentError] = useState<CommentLoadError | null>(
    commentsInitialLoadFailed
      ? { message: "回应暂时没有加载出来", mode: "initial" }
      : null,
  );
  const [commentsLoading, setCommentsLoading] = useState(false);
  const commentsRequestEpoch = useRef(0);
  const commentsLoadingRef = useRef(false);
  const recentPosts = useMemo(() => items.slice(0, 3), [items]);
  const recentComments = useMemo(() => commentItems.slice(0, 2), [commentItems]);

  useEffect(() => {
    const epoch = ++commentsRequestEpoch.current;
    commentsLoadingRef.current = false;
    setCommentItems(initialComments.items);
    setCommentPage(initialComments.page);
    setCommentsHasMore(initialComments.hasMore);
    setCommentError(
      commentsInitialLoadFailed
        ? { message: "回应暂时没有加载出来", mode: "initial" }
        : null,
    );
    setCommentsLoading(false);

    return () => {
      if (commentsRequestEpoch.current === epoch) {
        commentsRequestEpoch.current += 1;
      }
      commentsLoadingRef.current = false;
    };
  }, [commentsInitialLoadFailed, initialComments, userId]);

  useEffect(() => {
    let alive = true;

    const syncProfile = () => {
      const current = getStoredAuth()?.user;
      const own =
        current?.id != null && String(current.id) === String(userId) ||
        Boolean(current?.handle && userHandle && current.handle.toLowerCase() === userHandle.toLowerCase());
      setIsOwnProfile(own);

      if (!own) {
        setItems(posts);
        setMineError(null);
        return;
      }

      setLoadingMine(true);
      void postService
        .mine(1, 50)
        .then((response) => {
          if (!alive) return;
          setItems(response.items);
          setMineError(null);
        })
        .catch((err) => {
          if (!alive) return;
          setItems(posts);
          setMineError(err instanceof Error ? err.message : "我的文章加载失败");
        })
        .finally(() => {
          if (alive) setLoadingMine(false);
        });
    };

    syncProfile();
    window.addEventListener("chtholly-auth-change", syncProfile);
    return () => {
      alive = false;
      window.removeEventListener("chtholly-auth-change", syncProfile);
    };
  }, [posts, userHandle, userId]);

  const updatePost = (postId: string, patch: Partial<FeedItem>) => {
    setItems((current) => {
      const next = current.map((post) => (post.id === postId ? { ...post, ...patch } : post));
      // 置顶变更后本地重排，避免等刷新才看到顺序变化
      if ("isTop" in patch) {
        return [...next].sort((a, b) => Number(Boolean(b.isTop)) - Number(Boolean(a.isTop)));
      }
      return next;
    });
  };

  const removePost = (postId: string) => {
    setItems((current) => current.filter((post) => post.id !== postId));
  };

  const loadComments = async (targetPage: number, replace: boolean) => {
    if (commentsLoadingRef.current) return;

    commentsLoadingRef.current = true;
    setCommentsLoading(true);
    const requestEpoch = commentsRequestEpoch.current;

    try {
      const response = await commentService.listByUser(String(userId), targetPage, 20);
      if (commentsRequestEpoch.current !== requestEpoch) return;

      setCommentItems((current) => {
        if (replace) return response.items;
        const knownIds = new Set(current.map((item) => item.id));
        return [...current, ...response.items.filter((item) => !knownIds.has(item.id))];
      });
      setCommentPage(response.page);
      setCommentsHasMore(response.hasMore);
      setCommentError(null);
    } catch {
      if (commentsRequestEpoch.current !== requestEpoch) return;
      setCommentError(
        replace
          ? { message: "回应暂时没有加载出来", mode: "initial" }
          : { message: "更多回应暂时没有加载出来", mode: "more" },
      );
    } finally {
      if (commentsRequestEpoch.current === requestEpoch) {
        commentsLoadingRef.current = false;
        setCommentsLoading(false);
      }
    }
  };

  const retryComments = () => {
    if (commentError?.mode === "initial") {
      void loadComments(1, true);
      return;
    }
    void loadComments(commentPage + 1, false);
  };

  const renderPost = (post: FeedItem) =>
    isOwnProfile ? (
      <ManagedPostCard
        key={post.id}
        post={post}
        ownerUserId={userId}
        onTopChange={(top) => updatePost(post.id, { isTop: top })}
        onVisibilityChange={(visible) => updatePost(post.id, { visible })}
        onDeleted={() => removePost(post.id)}
      />
    ) : (
      <PostCard key={post.id} post={post} />
    );

  return (
    <section className="member-tabs" aria-label="用户内容">
      <div className="member-tabs__nav" role="tablist" aria-label="用户主页栏目">
        {TABS.map((tab) => {
          const Icon = tab.icon;
          return (
            <button
              key={tab.key}
              type="button"
              role="tab"
              aria-selected={activeTab === tab.key}
              className={cn("member-tabs__button", activeTab === tab.key && "member-tabs__button--active")}
              onClick={() => setActiveTab(tab.key)}
            >
              <Icon size={16} />
              <span>{tab.label}</span>
            </button>
          );
        })}
      </div>

      <div className="member-tabs__panel" role="tabpanel">
        {mineError && isOwnProfile && <div className="member-tab-alert">{mineError}</div>}
        {activeTab === "overview" && (
          <div className="member-overview">
            <section className="member-section">
              <div className="member-section__header">
                <p>最近的故事</p>
                <h2>{isOwnProfile ? "我的最近 3 篇文章" : "最近 3 篇文章"}</h2>
              </div>
              {loadingMine ? (
                <div className="member-coming-soon">
                  <p>正在整理你的文章……</p>
                </div>
              ) : recentPosts.length > 0 ? (
                <div className="member-post-list">
                  {recentPosts.map(renderPost)}
                </div>
              ) : (
                <EmptyState
                  className="member-empty"
                  title={isOwnProfile ? "你还没有文章" : "暂时还没有公开文章"}
                  description={
                    isOwnProfile
                      ? "写下第一篇吧。仓库里还有很多空白书页。"
                      : `${displayName} 还没有把故事放到这里。`
                  }
                />
              )}
            </section>
            <section className="member-section">
              <div className="member-section__header">
                <p>最近的回应</p>
                <h2>最近 2 条评论</h2>
              </div>
              <UserCommentActivityList
                items={recentComments}
                error={commentError?.message}
                onRetry={commentError ? retryComments : undefined}
                retrying={commentsLoading}
              />
            </section>
          </div>
        )}

        {activeTab === "posts" && (
          loadingMine ? (
            <div className="member-coming-soon">
              <p>正在整理你的文章……</p>
            </div>
          ) : items.length > 0 ? (
            <div className="member-post-list">
              {items.map(renderPost)}
            </div>
          ) : (
            <EmptyState className="member-empty" title={isOwnProfile ? "暂无文章" : "暂无公开文章"} />
          )
        )}

        {activeTab === "comments" && (
          <div className="member-comment-panel">
            <UserCommentActivityList
              items={commentItems}
              error={commentError?.message}
              onRetry={commentError ? retryComments : undefined}
              retrying={commentsLoading}
            />
            {commentsHasMore && !commentError && (
              <div className="member-comment-panel__more">
                <button
                  type="button"
                  onClick={() => void loadComments(commentPage + 1, false)}
                  disabled={commentsLoading}
                >
                  {commentsLoading ? "正在加载…" : "加载更多"}
                </button>
              </div>
            )}
          </div>
        )}

        {activeTab === "likes" && (
          <ComingSoonCard message="点赞过的文章需要后端开放列表接口。等它好了，再把喜欢的故事摆出来。" />
        )}
      </div>
    </section>
  );
}

function ManagedPostCard({
  post,
  ownerUserId,
  onTopChange,
  onVisibilityChange,
  onDeleted,
}: {
  post: FeedItem;
  ownerUserId: string | number;
  onTopChange: (top: boolean) => void;
  onVisibilityChange: (visibility: string) => void;
  onDeleted: () => void;
}) {
  return (
    <div className="member-managed-post">
      <div className="member-managed-post__bar">
        <div className="member-managed-post__badges">
          <span>{statusLabel(post.status)}</span>
          <span>{visibilityLabel(post.visible)}</span>
          {post.isTop && <span>已置顶</span>}
        </div>
        <PostOwnerActions
          postId={post.id}
          authorId={post.authorId ?? ownerUserId}
          title={post.title}
          initialTop={post.isTop}
          initialVisibility={post.visible}
          compact
          onTopChange={onTopChange}
          onVisibilityChange={onVisibilityChange}
          onDeleted={onDeleted}
        />
      </div>
      <PostCard post={post} />
    </div>
  );
}

function statusLabel(status?: string) {
  if (status === "draft") return "草稿";
  if (status === "deleted") return "已删除";
  return "已发布";
}

function visibilityLabel(visible?: string) {
  switch (visible) {
    case "private":
      return "私密";
    case "followers":
      return "粉丝可见";
    case "school":
      return "同校可见";
    case "unlisted":
      return "不列出";
    default:
      return "公开";
  }
}

function ComingSoonCard({ message }: { message: string }) {
  return (
    <div className="member-coming-soon">
      <p>{message}</p>
    </div>
  );
}
