import { apiFetch } from "./apiClient";
import type {
  CommentItem,
  CommentListResponse,
  CreateCommentRequest,
} from "@/lib/types/comment";

export const commentService = {
  list: (postId: string) =>
    apiFetch<CommentListResponse>(`/api/v1/posts/${postId}/comments`),

  create: (postId: string, body: CreateCommentRequest) =>
    apiFetch<CommentItem>(`/api/v1/posts/${postId}/comments`, {
      method: "POST",
      body,
    }),
};
