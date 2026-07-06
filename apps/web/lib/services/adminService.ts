import { apiFetch } from "./apiClient";
import type { AdminStats, AdminUser, PageResponse } from "@/lib/types/admin";

const ADMIN_PREFIX = "/api/v1/admin";

type RawAdminStats = {
  totalUsers?: number;
  totalPosts?: number;
  totalComments?: number;
  activeUsers7d?: number;
  newPosts7d?: number;
  userCount?: number;
  postCount?: number;
  commentCount?: number;
  usersCreatedToday?: number;
  postsPublishedToday?: number;
  commentsCreatedToday?: number;
};

type RawAdminUser = Omit<AdminUser, "banned"> & {
  banned?: boolean;
  bannedAt?: string | null;
};

function normalizeStats(raw: RawAdminStats): AdminStats {
  return {
    totalUsers: raw.totalUsers ?? raw.userCount ?? 0,
    totalPosts: raw.totalPosts ?? raw.postCount ?? 0,
    totalComments: raw.totalComments ?? raw.commentCount ?? 0,
    activeUsers7d: raw.activeUsers7d ?? raw.usersCreatedToday ?? 0,
    newPosts7d: raw.newPosts7d ?? raw.postsPublishedToday ?? 0,
    commentsToday: raw.commentsCreatedToday,
  };
}

function normalizeUser(user: RawAdminUser): AdminUser {
  return {
    ...user,
    handle: user.handle ?? "",
    nickname: user.nickname ?? user.handle ?? `User ${user.id}`,
    role: String(user.role ?? "USER"),
    banned: user.banned ?? Boolean(user.bannedAt),
  };
}

export const adminService = {
  getStats: async () => normalizeStats(await apiFetch<RawAdminStats>(`${ADMIN_PREFIX}/stats`)),

  getUsers: async (page = 1, size = 20, keyword?: string) => {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    if (keyword?.trim()) params.set("keyword", keyword.trim());
    const response = await apiFetch<PageResponse<RawAdminUser>>(
      `${ADMIN_PREFIX}/users?${params.toString()}`,
    );
    return {
      ...response,
      items: response.items.map(normalizeUser),
    };
  },

  setUserRole: (userId: number, role: string) =>
    apiFetch<void>(`${ADMIN_PREFIX}/users/${userId}/role`, {
      method: "PATCH",
      body: { role },
    }),

  banUser: (userId: number) =>
    apiFetch<void>(`${ADMIN_PREFIX}/users/${userId}/ban`, { method: "POST" }),

  unbanUser: (userId: number) =>
    apiFetch<void>(`${ADMIN_PREFIX}/users/${userId}/ban`, { method: "DELETE" }),

  setPostVisibility: (postId: string, visibility: string) =>
    apiFetch<void>(`${ADMIN_PREFIX}/posts/${postId}/visibility`, {
      method: "PATCH",
      body: { visible: visibility },
    }),

  deletePost: (postId: string) =>
    apiFetch<void>(`${ADMIN_PREFIX}/posts/${postId}`, { method: "DELETE" }),

  deleteComment: (postId: string, commentId: string) =>
    apiFetch<void>(`${ADMIN_PREFIX}/posts/${postId}/comments/${commentId}`, {
      method: "DELETE",
    }),
};
