import { apiFetch } from "./apiClient";
import type {
  NotificationListResponse,
  UnreadCountResponse,
} from "@/lib/types/notification";

export const notificationService = {
  list: (page = 1, size = 20) =>
    apiFetch<NotificationListResponse>(
      `/api/v1/notifications?page=${page}&size=${size}`,
    ),

  unreadCount: () =>
    apiFetch<UnreadCountResponse>("/api/v1/notifications/unread-count"),

  markRead: (id: string) =>
    apiFetch<void>(`/api/v1/notifications/${id}/read`, { method: "PATCH" }),

  markAllRead: () =>
    apiFetch<void>("/api/v1/notifications/read-all", { method: "POST" }),
};
