import { apiFetch } from "./apiClient";
import type {
  CommentItem,
  CommentListResponse,
  CreateCommentRequest,
  UserCommentActivityPage,
} from "@/lib/types/comment";

export const commentService = {
  list: (postId: string) =>
    apiFetch<CommentListResponse>(`/api/v1/posts/${postId}/comments`),

  listByUser: (userId: string, page = 1, size = 20) =>
    apiFetch<UserCommentActivityPage>(
      `/api/v1/users/${encodeURIComponent(userId)}/comments?page=${page}&size=${size}`,
    ),

  create: (postId: string, body: CreateCommentRequest) =>
    apiFetch<CommentItem>(`/api/v1/posts/${postId}/comments`, {
      method: "POST",
      body,
    }),
};
