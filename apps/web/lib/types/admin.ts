import type { FeedItem } from "@/lib/types/post";

export interface AdminStats {
  totalUsers: number;
  totalPosts: number;
  totalComments: number;
  activeUsers7d: number;
  newPosts7d: number;
  commentsToday?: number;
}

export interface AdminUser {
  id: number;
  handle: string;
  nickname: string;
  avatar?: string | null;
  phone?: string | null;
  email?: string | null;
  role: string;
  banned: boolean;
  bannedAt?: string | null;
  createdAt: string;
}

export interface PageResponse<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
  hasMore: boolean;
}

export type AdminPost = FeedItem & {
  visible?: string;
};
