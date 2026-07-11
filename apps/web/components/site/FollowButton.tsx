"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { Check, HeartHandshake, Loader2, UserPlus, UserX } from "lucide-react";
import { useStoredAuth } from "@/lib/auth/auth-store";
import { isLoggedIn } from "@/lib/auth/tokens";
import { relationService } from "@/lib/services/relationService";
import type { RelationStatus, UserCounter, UserId } from "@/lib/types/relation";
import { cn } from "@/lib/utils";

type FollowButtonSize = "sm" | "md";

type FollowButtonProps = {
  userId: UserId;
  initialStatus?: RelationStatus;
  initialCounter?: UserCounter;
  size?: FollowButtonSize;
  showCounter?: boolean;
  className?: string;
  onStatusChange?: (status: RelationStatus, counter?: UserCounter) => void;
};

const DEFAULT_STATUS: RelationStatus = {
  following: false,
  followedBy: false,
  mutual: false,
};

function isSameUser(a?: UserId | null, b?: UserId | null) {
  return a != null && b != null && String(a) === String(b);
}

export default function FollowButton({
  userId,
  initialStatus,
  initialCounter,
  ...props
}: FollowButtonProps) {
  const stateKey = [
    String(userId),
    initialStatus?.following,
    initialStatus?.followedBy,
    initialStatus?.mutual,
    initialCounter?.followings,
    initialCounter?.followers,
    initialCounter?.posts,
    initialCounter?.likedPosts,
    initialCounter?.favedPosts,
  ].join(":");
  return (
    <FollowButtonState
      key={stateKey}
      userId={userId}
      initialStatus={initialStatus}
      initialCounter={initialCounter}
      {...props}
    />
  );
}

function FollowButtonState({
  userId,
  initialStatus,
  initialCounter,
  size = "md",
  showCounter = true,
  className,
  onStatusChange,
}: FollowButtonProps) {
  const router = useRouter();
  const [status, setStatus] = useState<RelationStatus>(initialStatus ?? DEFAULT_STATUS);
  const [counter, setCounter] = useState<UserCounter | undefined>(initialCounter);
  const [loading, setLoading] = useState(false);
  const [hovering, setHovering] = useState(false);
  const auth = useStoredAuth();
  const self = isSameUser(auth?.user?.id, userId);

  useEffect(() => {
    if (!auth || self) return;
    let alive = true;
    void relationService.status(userId)
      .then((nextStatus) => {
        if (alive) setStatus(nextStatus);
      })
      .catch(() => {
        if (alive) setStatus(initialStatus ?? DEFAULT_STATUS);
      });
    return () => {
      alive = false;
    };
  }, [auth, initialStatus, self, userId]);

  useEffect(() => {
    if (initialCounter) return;
    let alive = true;
    void relationService.counter(userId)
      .then((nextCounter) => {
        if (alive) setCounter(nextCounter);
      })
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [initialCounter, userId]);

  const label = useMemo(() => {
    if (status.following && hovering) return "取消关注";
    if (status.mutual) return "互相关注";
    if (status.following) return "已关注";
    return "关注";
  }, [hovering, status.following, status.mutual]);

  const icon = useMemo(() => {
    if (loading) return <Loader2 size={size === "sm" ? 13 : 15} className="animate-spin" />;
    if (status.following && hovering) return <UserX size={size === "sm" ? 13 : 15} />;
    if (status.mutual) return <HeartHandshake size={size === "sm" ? 13 : 15} />;
    if (status.following) return <Check size={size === "sm" ? 13 : 15} />;
    return <UserPlus size={size === "sm" ? 13 : 15} />;
  }, [hovering, loading, size, status.following, status.mutual]);

  const handleClick = useCallback(async () => {
    if (self || loading) return;
    if (!isLoggedIn()) {
      router.push("/login");
      return;
    }

    const previous = status;
    const previousCounter = counter;
    const nextFollowing = !status.following;
    const optimistic: RelationStatus = {
      following: nextFollowing,
      followedBy: status.followedBy,
      mutual: nextFollowing && status.followedBy,
    };
    const optimisticCounter = counter
      ? {
          ...counter,
          followers: Math.max(0, counter.followers + (nextFollowing ? 1 : -1)),
        }
      : undefined;

    setLoading(true);
    setStatus(optimistic);
    setCounter(optimisticCounter);
    try {
      if (nextFollowing) {
        await relationService.follow(userId);
      } else {
        await relationService.unfollow(userId);
      }
      const [freshStatus, freshCounter] = await Promise.all([
        relationService.status(userId),
        relationService.counter(userId).catch(() => optimisticCounter),
      ]);
      setStatus(freshStatus);
      setCounter(freshCounter);
      onStatusChange?.(freshStatus, freshCounter);
    } catch {
      setStatus(previous);
      setCounter(previousCounter);
      router.push("/login");
    } finally {
      setLoading(false);
    }
  }, [counter, loading, onStatusChange, router, self, status, userId]);

  if (self) {
    return null;
  }

  return (
    <div className={cn("follow-widget", size === "sm" && "follow-widget--compact", className)}>
      <button
        type="button"
        onClick={handleClick}
        onMouseEnter={() => setHovering(true)}
        onMouseLeave={() => setHovering(false)}
        disabled={loading}
        className={cn(
          "follow-button",
          size === "sm" && "follow-button--sm",
          status.following && "follow-button--following",
          status.mutual && "follow-button--mutual",
          status.following && hovering && "follow-button--danger",
        )}
      >
        {icon}
        <span>{label}</span>
      </button>
      {showCounter && counter && (
        <div className="follow-counter-line" aria-label="用户关注数据">
          <span>{counter.followers} 粉丝</span>
          <span>{counter.followings} 关注</span>
        </div>
      )}
    </div>
  );
}
