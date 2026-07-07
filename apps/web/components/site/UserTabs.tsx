"use client";

import { useEffect, useMemo, useState } from "react";
import { Heart, MessageSquareText, Newspaper, ScrollText } from "lucide-react";
import PostCard from "@/components/site/PostCard";
import PostOwnerActions from "@/components/site/PostOwnerActions";
import { EmptyState } from "@/components/ui/EmptyState";
import { getStoredAuth } from "@/lib/auth/tokens";
import { postService } from "@/lib/services/postService";
import type { FeedItem } from "@/lib/types/post";
import { cn } from "@/lib/utils";

type UserTabKey = "overview" | "posts" | "comments" | "likes";

type UserTabsProps = {
  posts: FeedItem[];
  displayName: string;
  userId: string | number;
  userHandle?: string | null;
};

const TABS: Array<{ key: UserTabKey; label: string; icon: typeof ScrollText }> = [
  { key: "overview", label: "概览", icon: ScrollText },
  { key: "posts", label: "文章", icon: Newspaper },
  { key: "comments", label: "评论", icon: MessageSquareText },
  { key: "likes", label: "点赞", icon: Heart },
];

export default function UserTabs({ posts, displayName, userId, userHandle }: UserTabsProps) {
  const [activeTab, setActiveTab] = useState<UserTabKey>("overview");
  const [items, setItems] = useState<FeedItem[]>(posts);
  const [isOwnProfile, setIsOwnProfile] = useState(false);
  const [loadingMine, setLoadingMine] = useState(false);
  const [mineError, setMineError] = useState<string | null>(null);
  const recentPosts = useMemo(() => items.slice(0, 3), [items]);

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
    setItems((current) => current.map((post) => (post.id === postId ? { ...post, ...patch } : post)));
  };

  const removePost = (postId: string) => {
    setItems((current) => current.filter((post) => post.id !== postId));
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
              <ComingSoonCard message="按用户筛选评论的接口还没接上。等后端准备好，这里会显示最近的回应。" />
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
          <ComingSoonCard message="评论列表需要后端提供按用户筛选的接口。这里先替她留着位置。" />
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
