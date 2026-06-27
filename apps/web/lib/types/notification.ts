export type NotificationType =
  | "COMMENT_POST"
  | "COMMENT_REPLY"
  | "LIKE_POST"
  | "FOLLOW";

export interface NotificationItem {
  id: string;
  type: NotificationType;
  actorUserId: string | null;
  actorNickname: string | null;
  actorAvatar: string | null;
  postId: string | null;
  postSlug: string | null;
  postTitle: string | null;
  commentId: string | null;
  message: string;
  createdAt: string;
  readAt: string | null;
}

export interface NotificationListResponse {
  items: NotificationItem[];
  total: number;
  unreadCount: number;
}

export interface UnreadCountResponse {
  unreadCount: number;
}
