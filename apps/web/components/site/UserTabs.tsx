"use client";

import { useMemo, useState } from "react";
import { Heart, MessageSquareText, Newspaper, ScrollText } from "lucide-react";
import PostCard from "@/components/site/PostCard";
import { EmptyState } from "@/components/ui/EmptyState";
import type { FeedItem } from "@/lib/types/post";
import { cn } from "@/lib/utils";

type UserTabKey = "overview" | "posts" | "comments" | "likes";

type UserTabsProps = {
  posts: FeedItem[];
  displayName: string;
};

const TABS: Array<{ key: UserTabKey; label: string; icon: typeof ScrollText }> = [
  { key: "overview", label: "概览", icon: ScrollText },
  { key: "posts", label: "文章", icon: Newspaper },
  { key: "comments", label: "评论", icon: MessageSquareText },
  { key: "likes", label: "点赞", icon: Heart },
];

export default function UserTabs({ posts, displayName }: UserTabsProps) {
  const [activeTab, setActiveTab] = useState<UserTabKey>("overview");
  const recentPosts = useMemo(() => posts.slice(0, 3), [posts]);

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
        {activeTab === "overview" && (
          <div className="member-overview">
            <section className="member-section">
              <div className="member-section__header">
                <p>最近的故事</p>
                <h2>最近 3 篇文章</h2>
              </div>
              {recentPosts.length > 0 ? (
                <div className="member-post-list">
                  {recentPosts.map((post) => <PostCard key={post.id} post={post} />)}
                </div>
              ) : (
                <EmptyState
                  className="member-empty"
                  title="暂时还没有公开文章"
                  description={`${displayName} 还没有把故事放到这里。`}
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
          posts.length > 0 ? (
            <div className="member-post-list">
              {posts.map((post) => <PostCard key={post.id} post={post} />)}
            </div>
          ) : (
            <EmptyState className="member-empty" title="暂无公开文章" />
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

function ComingSoonCard({ message }: { message: string }) {
  return (
    <div className="member-coming-soon">
      <p>{message}</p>
    </div>
  );
}
