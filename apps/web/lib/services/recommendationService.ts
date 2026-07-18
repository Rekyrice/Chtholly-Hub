import { apiFetch } from "./apiClient";
import { postService } from "./postService";
import type { FeedItem, PostDetailResponse } from "@/lib/types/post";
import type {
  RecommendationListResponse,
  RecommendedPostItem,
} from "@/lib/types/recommendation";

const RECOMMENDATION_PREFIX = "/api/v1/recommendations";

/** Feed 卡片 + 推荐理由（用于 Hub / 侧边栏展示） */
export type RecommendedFeedItem = FeedItem & {
  reason?: string;
};

export const recommendationService = {
  /**
   * 拉取推荐列表。未登录时由调用方决定是否跳过；
   * 请求失败时返回空列表，便于 fallback 到热门推荐。
   */
  getRecommendations: async (limit = 10): Promise<RecommendationListResponse> => {
    const safeLimit = Math.min(Math.max(limit, 1), 50);
    try {
      return await apiFetch<RecommendationListResponse>(
        `${RECOMMENDATION_PREFIX}?limit=${safeLimit}`,
      );
    } catch {
      return { items: [], personalized: false };
    }
  },

  /**
   * 将推荐条目 hydrate 为 FeedItem（补齐 slug / 封面等）。
   * 详情失败时用 title 兜底，保证侧边栏仍可展示。
   */
  hydrateFeedItems: async (
    items: RecommendedPostItem[],
  ): Promise<RecommendedFeedItem[]> => {
    if (!items.length) return [];

    const results = await Promise.all(
      items.map(async (item): Promise<RecommendedFeedItem | null> => {
        try {
          const detail = await postService.detailById(item.postId);
          return detailToRecommendedFeed(detail, item);
        } catch {
          if (!item.title) return null;
          return {
            id: String(item.postId),
            slug: String(item.postId),
            title: item.title,
            description: "",
            tags: [],
            authorNickname: "仓库居民",
            reason: item.reason,
          };
        }
      }),
    );

    return results.filter((item): item is RecommendedFeedItem => item != null);
  },
};

function detailToRecommendedFeed(
  detail: PostDetailResponse,
  item: RecommendedPostItem,
): RecommendedFeedItem {
  return {
    id: detail.id,
    slug: detail.slug,
    title: detail.title || item.title,
    description: detail.description ?? "",
    coverImage: detail.images?.[0],
    tags: detail.tags ?? [],
    authorId: detail.authorId,
    authorAvatar: detail.authorAvatar,
    authorNickname: detail.authorNickname || "仓库居民",
    publishTime: detail.publishTime,
    likeCount: detail.likeCount,
    favoriteCount: detail.favoriteCount,
    liked: detail.liked,
    faved: detail.faved,
    isTop: detail.isTop,
    reason: item.reason,
  };
}
