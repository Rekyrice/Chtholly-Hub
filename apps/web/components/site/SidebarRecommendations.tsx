"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { isLoggedIn } from "@/lib/auth/tokens";
import {
  recommendationService,
  type RecommendedFeedItem,
} from "@/lib/services/recommendationService";
import type { FeedItem } from "@/lib/types/post";

type SidebarRecommendationsProps = {
  fallback: FeedItem[];
  degraded?: boolean;
};

/**
 * 侧边栏「推荐内容」：登录后拉个性化推荐，否则展示 hub-feed 兜底。
 */
export default function SidebarRecommendations({
  fallback,
  degraded = false,
}: SidebarRecommendationsProps) {
  const [posts, setPosts] = useState<RecommendedFeedItem[]>(fallback);
  const [personalized, setPersonalized] = useState(false);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    let alive = true;

    const load = async () => {
      if (!isLoggedIn()) {
        if (!alive) return;
        setPosts(fallback);
        setPersonalized(false);
        setLoaded(true);
        return;
      }

      try {
        const response = await recommendationService.getRecommendations(5);
        if (!alive) return;

        if (response.items.length === 0) {
          setPosts(fallback);
          setPersonalized(false);
          setLoaded(true);
          return;
        }

        const hydrated = await recommendationService.hydrateFeedItems(response.items);
        if (!alive) return;

        if (hydrated.length > 0) {
          setPosts(hydrated);
          setPersonalized(response.personalized);
        } else {
          setPosts(fallback);
          setPersonalized(false);
        }
      } catch {
        if (!alive) return;
        setPosts(fallback);
        setPersonalized(false);
      } finally {
        if (alive) setLoaded(true);
      }
    };

    void load();

    const onAuthChange = () => {
      void load();
    };
    window.addEventListener("chtholly-auth-change", onAuthChange);
    return () => {
      alive = false;
      window.removeEventListener("chtholly-auth-change", onAuthChange);
    };
  }, [fallback]);

  const title = personalized ? "为你推荐" : "推荐内容";

  if (degraded) {
    return (
      <div className="widget">
        <h3 className="widget-title">{title}</h3>
        <p className="text-sm text-text-secondary m-0">推荐暂时走丢了，等一下就好。</p>
      </div>
    );
  }

  if (loaded && posts.length === 0) {
    return (
      <div className="widget sidebar-recommendations--empty">
        <h3 className="widget-title">{title}</h3>
        <p className="sidebar-recommendations__empty">
          还没有发现你的兴趣呢，多逛逛告诉珂朵莉你喜欢什么吧~
        </p>
      </div>
    );
  }

  if (posts.length === 0) {
    return null;
  }

  return (
    <div className="widget sidebar-recommendations">
      <h3 className="widget-title">{title}</h3>
      <ul className="list-none p-0 m-0">
        {posts.slice(0, 5).map((post) => (
          <li key={post.id} className="sidebar-recommendations__item">
            <Link
              href={`/post/${post.slug}`}
              className="sidebar-recommendations__link"
            >
              <span className="sidebar-recommendations__title">{post.title}</span>
              {post.reason ? (
                <small className="sidebar-recommendations__reason">{post.reason}</small>
              ) : personalized ? null : (
                <small className="sidebar-recommendations__reason">热门推荐</small>
              )}
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}
