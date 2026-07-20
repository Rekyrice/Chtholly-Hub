import { apiFetch } from "./apiClient";
import type {
  DraftEditDecisionResponse,
  DraftEditPreviewRequest,
  DraftEditPreviewResponse,
  FeedResponse,
  PostDetailResponse,
  RelatedPostSummary,
} from "@/lib/types/post";

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
  feed: (page = 1, size = 20, ownerId?: number | string, tag?: string) => {
    const params = new URLSearchParams({
      page: String(page),
      size: String(size),
    });
    if (ownerId != null) params.set("ownerId", String(ownerId));
    if (tag) params.set("tag", tag);
    return apiFetch<FeedResponse>(`${POST_PREFIX}/feed?${params.toString()}`);
  },

  mine: (page = 1, size = 20) =>
    apiFetch<FeedResponse>(`${POST_PREFIX}/mine?page=${page}&size=${size}`),

  followingFeed: (page = 1, size = 20) =>
    apiFetch<FeedResponse>(`${POST_PREFIX}/feed/following?page=${page}&size=${size}`),

  detailBySlug: (slug: string) =>
    apiFetch<PostDetailResponse>(`${POST_PREFIX}/detail/by-slug/${slug}`),

  detailById: (id: string | number) =>
    apiFetch<PostDetailResponse>(`${POST_PREFIX}/detail/${encodeURIComponent(String(id))}`),

  related: (id: string | number) =>
    apiFetch<RelatedPostSummary[]>(`${POST_PREFIX}/${encodeURIComponent(String(id))}/related`),

  createDraft: () =>
    apiFetch<PostDraftCreateResponse>(`${POST_PREFIX}/drafts`, { method: "POST" }),

  confirmContent: (id: string, body: PostContentConfirmRequest) =>
    apiFetch<void>(`${POST_PREFIX}/${id}/content/confirm`, {
      method: "POST",
      body,
    }),

  createDraftEditPreview: (draftId: string, body: DraftEditPreviewRequest) =>
    apiFetch<DraftEditPreviewResponse>(
      `${POST_PREFIX}/${encodeURIComponent(draftId)}/draft-edit/previews`,
      { method: "POST", body },
    ),

  confirmDraftEditPreview: (draftId: string, previewId: string, previewHash: string) =>
    apiFetch<DraftEditDecisionResponse>(
      `${POST_PREFIX}/${encodeURIComponent(draftId)}/draft-edit/previews/${encodeURIComponent(previewId)}/confirm`,
      { method: "POST", body: { previewHash } },
    ),

  rejectDraftEditPreview: (draftId: string, previewId: string, previewHash: string) =>
    apiFetch<DraftEditDecisionResponse>(
      `${POST_PREFIX}/${encodeURIComponent(draftId)}/draft-edit/previews/${encodeURIComponent(previewId)}/reject`,
      { method: "POST", body: { previewHash } },
    ),

  patchMetadata: (id: string, body: PostPatchRequest) =>
    apiFetch<void>(`${POST_PREFIX}/${id}`, {
      method: "PATCH",
      body,
    }),

  publish: (id: string) =>
    apiFetch<void>(`${POST_PREFIX}/${id}/publish`, { method: "POST" }),

  setTop: (id: string, top: boolean) =>
    apiFetch<void>(`${POST_PREFIX}/${id}/top`, {
      method: "PATCH",
      body: { isTop: top },
    }),

  setVisibility: (id: string, visibility: string) =>
    apiFetch<void>(`${POST_PREFIX}/${id}/visibility`, {
      method: "PATCH",
      body: { visible: visibility },
    }),

  remove: (id: string) =>
    apiFetch<void>(`${POST_PREFIX}/${id}`, { method: "DELETE" }),
};
