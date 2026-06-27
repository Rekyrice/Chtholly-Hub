import { apiFetch } from "./apiClient";
import type { FeedResponse, PostDetailResponse } from "@/lib/types/post";

const POST_PREFIX = "/api/v1/posts";

export type PostDraftCreateResponse = { id: string };

export type PostContentConfirmRequest = {
  objectKey: string;
  etag: string;
  sha256: string;
  size: number;
};

export type PostPatchRequest = {
  title?: string;
  tags?: string[];
  description?: string;
  visible?: string;
};

export const postService = {
  feed: (page = 1, size = 20, ownerId?: number, tag?: string) => {
    const params = new URLSearchParams({
      page: String(page),
      size: String(size),
    });
    if (ownerId != null) params.set("ownerId", String(ownerId));
    if (tag) params.set("tag", tag);
    return apiFetch<FeedResponse>(`${POST_PREFIX}/feed?${params.toString()}`);
  },

  detailBySlug: (slug: string) =>
    apiFetch<PostDetailResponse>(`${POST_PREFIX}/detail/by-slug/${slug}`),

  createDraft: () =>
    apiFetch<PostDraftCreateResponse>(`${POST_PREFIX}/drafts`, { method: "POST" }),

  confirmContent: (id: string, body: PostContentConfirmRequest) =>
    apiFetch<void>(`${POST_PREFIX}/${id}/content/confirm`, {
      method: "POST",
      body,
    }),

  patchMetadata: (id: string, body: PostPatchRequest) =>
    apiFetch<void>(`${POST_PREFIX}/${id}`, {
      method: "PATCH",
      body,
    }),

  publish: (id: string) =>
    apiFetch<void>(`${POST_PREFIX}/${id}/publish`, { method: "POST" }),
};
