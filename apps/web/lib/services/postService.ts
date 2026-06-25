import { apiFetch } from "./apiClient";
import type { FeedResponse, PostDetailResponse } from "@/lib/types/post";

const POST_PREFIX = "/api/v1/posts";

/** Phase A 只读帖子 API */
export const postService = {
  feed: (page = 1, size = 20, ownerId?: number) =>
    apiFetch<FeedResponse>(
      `${POST_PREFIX}/feed?page=${page}&size=${size}${ownerId ? `&ownerId=${ownerId}` : ""}`,
    ),

  detailBySlug: (slug: string) =>
    apiFetch<PostDetailResponse>(`${POST_PREFIX}/detail/by-slug/${slug}`),
};
