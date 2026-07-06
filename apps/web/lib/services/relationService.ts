import { apiFetch } from "./apiClient";
import type {
  PageResponse,
  ProfileResponse,
  RelationStatus,
  UserCounter,
  UserId,
} from "@/lib/types/relation";

const RELATION_PREFIX = "/api/v1/relation";

export const relationService = {
  follow: (toUserId: UserId) =>
    apiFetch<boolean>(`${RELATION_PREFIX}/follow?toUserId=${encodeURIComponent(String(toUserId))}`, {
      method: "POST",
    }),

  unfollow: (toUserId: UserId) =>
    apiFetch<boolean>(`${RELATION_PREFIX}/unfollow?toUserId=${encodeURIComponent(String(toUserId))}`, {
      method: "POST",
    }),

  status: (toUserId: UserId) =>
    apiFetch<RelationStatus>(`${RELATION_PREFIX}/status?toUserId=${encodeURIComponent(String(toUserId))}`),

  following: (userId: UserId, size = 20, cursor?: string | null) => {
    const params = new URLSearchParams({ userId: String(userId), size: String(size) });
    if (cursor) params.set("cursor", cursor);
    return apiFetch<PageResponse<ProfileResponse>>(`${RELATION_PREFIX}/following?${params.toString()}`);
  },

  followers: (userId: UserId, size = 20, cursor?: string | null) => {
    const params = new URLSearchParams({ userId: String(userId), size: String(size) });
    if (cursor) params.set("cursor", cursor);
    return apiFetch<PageResponse<ProfileResponse>>(`${RELATION_PREFIX}/followers?${params.toString()}`);
  },

  counter: (userId: UserId) =>
    apiFetch<UserCounter>(`${RELATION_PREFIX}/counter?userId=${encodeURIComponent(String(userId))}`),
};
