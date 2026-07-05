import type { FeedItem } from "@/lib/types/post";
import type { TagItem } from "@/lib/types/tag";

/** 搜索响应（与后端 SearchResponse 对齐） */
export type SearchResponse = {
  items: FeedItem[];
  nextAfter: string | null;
  hasMore: boolean;
  /** ES 不可用或查询失败时为 true */
  degraded?: boolean;
};

/** 搜索联想 */
export type SuggestResponse = {
  items: string[];
};

export type HubFeedStatus = "ok" | "degraded";

export type AgentExperienceItem = {
  text: string;
  valueScore: number;
  importance: number;
  createdAt?: string;
  source?: string;
};

export type HubFeedResponse = {
  latestPosts: FeedItem[];
  latestPostsStatus: HubFeedStatus;
  hotTags: TagItem[];
  hotTagsStatus: HubFeedStatus;
  recommendations: FeedItem[];
  recommendationsStatus: HubFeedStatus;
  experiences: AgentExperienceItem[];
  experiencesStatus: HubFeedStatus;
};
