import type { FeedItem } from "@/lib/types/post";

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
