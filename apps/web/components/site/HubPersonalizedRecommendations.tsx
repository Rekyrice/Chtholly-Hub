"use client";

import { useEffect, useState } from "react";
import ChthollyRecommendation from "@/components/site/ChthollyRecommendation";
import { isLoggedIn } from "@/lib/auth/tokens";
import {
  recommendationService,
  type RecommendedFeedItem,
} from "@/lib/services/recommendationService";
import type { FeedItem } from "@/lib/types/post";

type HubPersonalizedRecommendationsProps = {
  /** SSR / hub-feed 热门推荐，作为未登录或 API 失败时的兜底 */
  fallback: FeedItem[];
};

/**
 * 客户端拉取个性化推荐；JWT 在 localStorage，无法在 RSC/ISR 中完成。
 */
export default function HubPersonalizedRecommendations({
  fallback,
}: HubPersonalizedRecommendationsProps) {
  const [posts, setPosts] = useState<RecommendedFeedItem[]>(() => withHotReason(fallback));
  const [personalized, setPersonalized] = useState(false);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    let alive = true;

    const load = async () => {
      // 未登录直接用 hub-feed 热门，避免多余请求
      if (!isLoggedIn()) {
        if (!alive) return;
        setPosts(withHotReason(fallback));
        setPersonalized(false);
        setLoaded(true);
        return;
      }

      try {
        const response = await recommendationService.getRecommendations(6);
        if (!alive) return;

        if (response.items.length === 0) {
          setPosts(withHotReason(fallback));
          setPersonalized(false);
          setLoaded(true);
          return;
        }

        const hydrated = await recommendationService.hydrateFeedItems(response.items);
        if (!alive) return;

        if (hydrated.length > 0) {
          setPosts(
            response.personalized
              ? hydrated
              : hydrated.map((item) => ({
                  ...item,
                  reason: item.reason || "热门推荐",
                })),
          );
          setPersonalized(response.personalized);
        } else {
          setPosts(withHotReason(fallback));
          setPersonalized(false);
        }
      } catch {
        if (!alive) return;
        setPosts(withHotReason(fallback));
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

  const emptyInterest = loaded && posts.length === 0;

  return (
    <ChthollyRecommendation
      posts={posts}
      personalized={personalized}
      emptyInterest={emptyInterest}
    />
  );
}

function withHotReason(posts: FeedItem[]): RecommendedFeedItem[] {
  return posts.map((post) => ({ ...post, reason: "热门推荐" }));
}
