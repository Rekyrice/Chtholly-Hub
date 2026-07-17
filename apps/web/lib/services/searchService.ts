import { apiFetch } from "./apiClient";
import type {
  HubFeedResponse,
  SearchRequest,
  SearchResponse,
  SuggestResponse,
} from "@/lib/types/search";

const SEARCH_PREFIX = "/api/v1/search";

export const searchService = {
  search: ({ q, size = 20, tags, sort = "relevance", after }: SearchRequest) => {
    const params = new URLSearchParams();
    params.set("q", q);
    params.set("size", String(size));
    if (tags?.length) params.set("tags", tags.join(","));
    params.set("sort", sort);
    if (after) params.set("after", after);
    return apiFetch<SearchResponse>(`${SEARCH_PREFIX}?${params.toString()}`);
  },

  suggest: (prefix: string, size = 8) =>
    apiFetch<SuggestResponse>(
      `${SEARCH_PREFIX}/suggest?prefix=${encodeURIComponent(prefix)}&size=${size}`,
    ),

  hubFeed: (interestTags?: string[], page = 1, size = 8) => {
    const params = new URLSearchParams();
    if (interestTags?.length) params.set("interestTags", interestTags.join(","));
    params.set("page", String(page));
    params.set("size", String(size));
    const query = params.toString();
    return apiFetch<HubFeedResponse>(`${SEARCH_PREFIX}/hub-feed${query ? `?${query}` : ""}`);
  },
};
