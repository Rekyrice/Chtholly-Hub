"use client";

import Link from "next/link";
import { Bell } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { isLoggedIn, purgeExpiredAuth } from "@/lib/auth/tokens";
import { notificationService } from "@/lib/services/notificationService";
import type { NotificationItem } from "@/lib/types/notification";
import { cn, formatDate } from "@/lib/utils";

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
  const [loggedIn, setLoggedIn] = useState(false);
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
    const syncAuth = () => {
      purgeExpiredAuth();
      setLoggedIn(isLoggedIn());
    };
    syncAuth();
    const onAuth = () => {
      syncAuth();
      void refreshUnread();
    };
    void refreshUnread();
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

  if (!loggedIn) {
    return null;
  }

  return (
    <div className="relative" ref={panelRef}>
      <button
        type="button"
        onClick={handleOpen}
        className="relative p-2 rounded-full text-text-secondary hover:bg-cloud transition-colors duration-150"
        aria-label="通知"
      >
        <Bell size={20} />
        {unread > 0 && (
          <span className="absolute -top-0.5 -right-0.5 min-w-[18px] h-[18px] px-1 rounded-full text-[10px] font-medium bg-sky text-on-primary flex items-center justify-center">
            {unread > 99 ? "99+" : unread}
          </span>
        )}
      </button>

      {open && (
        <div className="absolute right-0 mt-2 w-80 max-h-96 overflow-y-auto bg-surface shadow-lg border border-border z-50 rounded-lg">
          <div className="flex items-center justify-between px-4 py-3 border-b border-border">
            <span className="text-sm font-medium text-text">通知</span>
            {unread > 0 && (
              <button
                type="button"
                onClick={handleMarkAll}
                className="text-xs text-sky hover:underline bg-transparent border-0 cursor-pointer transition-colors duration-150"
              >
                全部已读
              </button>
            )}
          </div>

          {loading ? (
            <p className="px-4 py-6 text-sm text-center text-text-secondary">加载中…</p>
          ) : items.length === 0 ? (
            <p className="px-4 py-6 text-sm text-center text-text-secondary">暂无通知</p>
          ) : (
            <ul>
              {items.map((item) => {
                const href = notificationHref(item);
                const unreadItem = !item.readAt;
                const inner = (
                  <>
                    <p
                      className={cn(
                        "text-sm leading-snug",
                        unreadItem ? "text-text font-medium" : "text-text-secondary font-normal",
                      )}
                    >
                      {item.message}
                    </p>
                    <p className="mt-1 text-xs text-text-secondary">
                      {formatDate(item.createdAt)}
                    </p>
                  </>
                );

                return (
                  <li
                    key={item.id}
                    className={cn(
                      "border-b border-border last:border-b-0",
                      unreadItem && "notification-unread",
                    )}
                  >
                    {href ? (
                      <Link
                        href={href}
                        onClick={() => void handleItemClick(item)}
                        className="block px-4 py-3 hover:bg-cloud transition-colors duration-150"
                      >
                        {inner}
                      </Link>
                    ) : (
                      <button
                        type="button"
                        onClick={() => void handleItemClick(item)}
                        className="block w-full text-left px-4 py-3 hover:bg-cloud transition-colors duration-150"
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
