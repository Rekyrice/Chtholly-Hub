"use client";

import Link from "next/link";
import { Bell } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { isLoggedIn } from "@/lib/auth/tokens";
import { notificationService } from "@/lib/services/notificationService";
import { siteConfig } from "@/lib/site.config";
import type { NotificationItem } from "@/lib/types/notification";
import { formatDate } from "@/lib/utils";

function notificationHref(item: NotificationItem): string | null {
  if (item.postSlug) {
    return `/post/${item.postSlug}`;
  }
  if (item.type === "FOLLOW" && item.actorUserId) {
    return null;
  }
  return null;
}

export default function NotificationBell() {
  const [open, setOpen] = useState(false);
  const [items, setItems] = useState<NotificationItem[]>([]);
  const [unread, setUnread] = useState(0);
  const [loading, setLoading] = useState(false);
  const panelRef = useRef<HTMLDivElement>(null);

  const refreshUnread = useCallback(async () => {
    if (!isLoggedIn()) {
      setUnread(0);
      return;
    }
    try {
      const res = await notificationService.unreadCount();
      setUnread(res.unreadCount);
    } catch {
      setUnread(0);
    }
  }, []);

  const loadList = useCallback(async () => {
    if (!isLoggedIn()) return;
    setLoading(true);
    try {
      const res = await notificationService.list(1, 15);
      setItems(res.items);
      setUnread(res.unreadCount);
    } catch {
      setItems([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refreshUnread();
    const onAuth = () => void refreshUnread();
    window.addEventListener("chtholly-auth-change", onAuth);
    return () => window.removeEventListener("chtholly-auth-change", onAuth);
  }, [refreshUnread]);

  useEffect(() => {
    if (!open) return;
    void loadList();
  }, [open, loadList]);

  useEffect(() => {
    if (!open) return;
    const onDocClick = (e: MouseEvent) => {
      if (panelRef.current && !panelRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener("mousedown", onDocClick);
    return () => document.removeEventListener("mousedown", onDocClick);
  }, [open]);

  const handleOpen = () => {
    if (!isLoggedIn()) return;
    setOpen((v) => !v);
  };

  const handleItemClick = async (item: NotificationItem) => {
    if (!item.readAt) {
      try {
        await notificationService.markRead(item.id);
        setUnread((c) => Math.max(0, c - 1));
        setItems((prev) =>
          prev.map((n) =>
            n.id === item.id ? { ...n, readAt: new Date().toISOString() } : n,
          ),
        );
      } catch {
        // 忽略标记失败
      }
    }
    setOpen(false);
  };

  const handleMarkAll = async () => {
    try {
      await notificationService.markAllRead();
      setUnread(0);
      setItems((prev) =>
        prev.map((n) => ({ ...n, readAt: n.readAt ?? new Date().toISOString() })),
      );
    } catch {
      // 忽略
    }
  };

  if (!isLoggedIn()) {
    return null;
  }

  return (
    <div className="relative" ref={panelRef}>
      <button
        type="button"
        onClick={handleOpen}
        className="relative p-2 rounded-full hover:bg-black/5"
        aria-label="通知"
      >
        <Bell size={20} style={{ color: "#616161" }} />
        {unread > 0 && (
          <span
            className="absolute -top-0.5 -right-0.5 min-w-[18px] h-[18px] px-1 rounded-full text-[10px] font-medium text-white flex items-center justify-center"
            style={{ backgroundColor: siteConfig.theme.primary }}
          >
            {unread > 99 ? "99+" : unread}
          </span>
        )}
      </button>

      {open && (
        <div
          className="absolute right-0 mt-2 w-80 max-h-96 overflow-y-auto bg-white shadow-lg border z-50"
          style={{ borderColor: "#e0e0e0" }}
        >
          <div
            className="flex items-center justify-between px-4 py-3 border-b"
            style={{ borderColor: "#f0f0f0" }}
          >
            <span className="text-sm font-medium" style={{ color: "#424242" }}>
              通知
            </span>
            {unread > 0 && (
              <button
                type="button"
                onClick={handleMarkAll}
                className="text-xs hover:underline"
                style={{ color: siteConfig.theme.primary }}
              >
                全部已读
              </button>
            )}
          </div>

          {loading ? (
            <p className="px-4 py-6 text-sm text-center" style={{ color: "#9e9e9e" }}>
              加载中…
            </p>
          ) : items.length === 0 ? (
            <p className="px-4 py-6 text-sm text-center" style={{ color: "#9e9e9e" }}>
              暂无通知
            </p>
          ) : (
            <ul>
              {items.map((item) => {
                const href = notificationHref(item);
                const unreadItem = !item.readAt;
                const inner = (
                  <>
                    <p
                      className="text-sm leading-snug"
                      style={{
                        color: unreadItem ? "#424242" : "#757575",
                        fontWeight: unreadItem ? 500 : 400,
                      }}
                    >
                      {item.message}
                    </p>
                    <p className="mt-1 text-xs" style={{ color: "#9e9e9e" }}>
                      {formatDate(item.createdAt)}
                    </p>
                  </>
                );

                return (
                  <li
                    key={item.id}
                    className="border-b last:border-b-0"
                    style={{
                      borderColor: "#f5f5f5",
                      backgroundColor: unreadItem ? "#f9fdfc" : "transparent",
                    }}
                  >
                    {href ? (
                      <Link
                        href={href}
                        onClick={() => void handleItemClick(item)}
                        className="block px-4 py-3 hover:bg-black/[0.02]"
                      >
                        {inner}
                      </Link>
                    ) : (
                      <button
                        type="button"
                        onClick={() => void handleItemClick(item)}
                        className="block w-full text-left px-4 py-3 hover:bg-black/[0.02]"
                      >
                        {inner}
                      </button>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
