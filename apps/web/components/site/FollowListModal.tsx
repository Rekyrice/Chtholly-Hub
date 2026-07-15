"use client";

import Image from "next/image";
import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { X } from "lucide-react";
import FollowButton from "@/components/site/FollowButton";
import { relationService } from "@/lib/services/relationService";
import type { PageResponse, ProfileResponse, UserId } from "@/lib/types/relation";
import { cn } from "@/lib/utils";

type FollowListTab = "following" | "followers";

type FollowListModalProps = {
  userId: UserId;
  open: boolean;
  initialTab: FollowListTab;
  onClose: () => void;
};

export type RequestCommitGate = {
  mount: () => void;
  next: () => number;
  invalidate: () => void;
  unmount: () => void;
  canCommit: (version: number) => boolean;
};

export function createRequestCommitGate(): RequestCommitGate {
  let mounted = false;
  let generation = 0;
  return {
    mount: () => {
      mounted = true;
    },
    next: () => {
      generation += 1;
      return generation;
    },
    invalidate: () => {
      generation += 1;
    },
    unmount: () => {
      mounted = false;
      generation += 1;
    },
    canCommit: (version) => mounted && generation === version,
  };
}

function userInitial(user: ProfileResponse) {
  return (user.nickname || user.handle || "?").charAt(0);
}

export default function FollowListModal({
  userId,
  open,
  initialTab,
  onClose,
}: FollowListModalProps) {
  if (!open) return null;
  return (
    <FollowListModalContent
      key={`${String(userId)}:${initialTab}`}
      userId={userId}
      initialTab={initialTab}
      onClose={onClose}
    />
  );
}

function FollowListModalContent({
  userId,
  initialTab,
  onClose,
}: Omit<FollowListModalProps, "open">) {
  const [tab, setTab] = useState<FollowListTab>(initialTab);
  const [items, setItems] = useState<ProfileResponse[]>([]);
  const [cursor, setCursor] = useState<string | null | undefined>(null);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const sentinelRef = useRef<HTMLDivElement | null>(null);
  const [requestCommitGate] = useState(createRequestCommitGate);

  useEffect(() => {
    requestCommitGate.mount();
    return () => requestCommitGate.unmount();
  }, [requestCommitGate]);

  const loadPage = useCallback(
    async (nextCursor?: string | null, replace = false, isAlive: () => boolean = () => true) => {
      const requestVersion = requestCommitGate.next();
      const isCurrentRequest = () =>
        requestCommitGate.canCommit(requestVersion) && isAlive();
      setLoading(true);
      setError(null);
      try {
        const request =
          tab === "following"
            ? relationService.following(userId, 20, nextCursor)
            : relationService.followers(userId, 20, nextCursor);
        const page: PageResponse<ProfileResponse> = await request;
        if (!isCurrentRequest()) return;
        setItems((current) => (replace ? page.items : [...current, ...page.items]));
        setCursor(page.nextCursor);
        setHasMore(page.hasMore);
      } catch {
        if (!isCurrentRequest()) return;
        if (replace) setItems([]);
        setError("列表暂时没有加载出来，稍后再试试。");
        setHasMore(false);
      } finally {
        if (isCurrentRequest()) setLoading(false);
      }
    },
    [requestCommitGate, tab, userId],
  );

  useEffect(() => {
    let alive = true;
    const timer = window.setTimeout(() => void loadPage(null, true, () => alive), 0);
    return () => {
      alive = false;
      window.clearTimeout(timer);
    };
  }, [loadPage, tab]);

  const selectTab = (nextTab: FollowListTab) => {
    if (nextTab === tab) return;
    requestCommitGate.invalidate();
    setItems([]);
    setCursor(null);
    setHasMore(false);
    setTab(nextTab);
  };

  useEffect(() => {
    if (!hasMore || loading) return;
    const sentinel = sentinelRef.current;
    if (!sentinel) return;
    const observer = new IntersectionObserver((entries) => {
      if (entries[0]?.isIntersecting && hasMore && !loading) {
        void loadPage(cursor, false);
      }
    });
    observer.observe(sentinel);
    return () => observer.disconnect();
  }, [cursor, hasMore, loadPage, loading]);

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [onClose]);

  return (
    <div className="follow-modal" role="dialog" aria-modal="true" aria-label="关注列表">
      <button type="button" className="follow-modal__backdrop" onClick={onClose} aria-label="关闭" />
      <div className="follow-modal__panel">
        <div className="follow-modal__header">
          <div className="follow-modal__tabs" role="tablist" aria-label="关注列表类型">
            <button
              type="button"
              role="tab"
              aria-selected={tab === "following"}
              className={cn("follow-modal__tab", tab === "following" && "follow-modal__tab--active")}
              onClick={() => selectTab("following")}
            >
              关注
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={tab === "followers"}
              className={cn("follow-modal__tab", tab === "followers" && "follow-modal__tab--active")}
              onClick={() => selectTab("followers")}
            >
              粉丝
            </button>
          </div>
          <button type="button" className="follow-modal__close" onClick={onClose} aria-label="关闭">
            <X size={18} />
          </button>
        </div>

        <div className="follow-modal__body">
          {error ? (
            <p className="follow-modal__empty">{error}</p>
          ) : items.length === 0 && !loading ? (
            <p className="follow-modal__empty">
              {tab === "following" ? "还没有关注的人。" : "还没有粉丝。"}
            </p>
          ) : (
            <div className="follow-list">
              {items.map((user) => (
                <div key={String(user.id)} className="follow-list__item">
                  <Link href={`/user/${encodeURIComponent(user.handle)}`} className="follow-list__identity">
                    {user.avatar ? (
                      <Image
                        src={user.avatar}
                        alt={user.nickname || user.handle}
                        width={44}
                        height={44}
                        className="follow-list__avatar"
                      />
                    ) : (
                      <span className="follow-list__avatar follow-list__avatar--initial">
                        {userInitial(user)}
                      </span>
                    )}
                    <span className="follow-list__text">
                      <span className="follow-list__name">{user.nickname || user.handle}</span>
                      <span className="follow-list__meta">@{user.handle}</span>
                      {user.bio && <span className="follow-list__bio">{user.bio}</span>}
                    </span>
                  </Link>
                  <FollowButton userId={user.id} size="sm" showCounter={false} />
                </div>
              ))}
              {loading && <p className="follow-modal__loading">加载中...</p>}
              <div ref={sentinelRef} className="follow-modal__sentinel" />
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
