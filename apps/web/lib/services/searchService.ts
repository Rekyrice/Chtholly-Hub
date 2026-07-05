import { apiFetch } from "./apiClient";
import type { HubFeedResponse, SearchResponse, SuggestResponse } from "@/lib/types/search";

const SEARCH_PREFIX = "/api/v1/search";

export const searchService = {
  search: (q: string, size = 20, after?: string) => {
    const params = new URLSearchParams({ q, size: String(size) });
    if (after) params.set("after", after);
    return apiFetch<SearchResponse>(`${SEARCH_PREFIX}?${params.toString()}`);
  },

  suggest: (prefix: string, size = 8) =>
    apiFetch<SuggestResponse>(
      `${SEARCH_PREFIX}/suggest?prefix=${encodeURIComponent(prefix)}&size=${size}`,
    ),

  hubFeed: (interestTags?: string[]) => {
    const params = new URLSearchParams();
    if (interestTags?.length) params.set("interestTags", interestTags.join(","));
    const query = params.toString();
    return apiFetch<HubFeedResponse>(`${SEARCH_PREFIX}/hub-feed${query ? `?${query}` : ""}`);
  },
};
