"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { Heart, Loader2 } from "lucide-react";
import { isLoggedIn } from "@/lib/auth/tokens";
import { ApiError } from "@/lib/services/apiClient";
import { actionService } from "@/lib/services/actionService";
import { cn } from "@/lib/utils";

type LikeButtonProps = {
  entityType: string;
  entityId: string;
  initialLiked?: boolean;
  initialCount?: number;
  className?: string;
};

export default function LikeButton({
  entityType,
  entityId,
  initialLiked = false,
  initialCount = 0,
  className,
}: LikeButtonProps) {
  const router = useRouter();
  const [liked, setLiked] = useState(initialLiked);
  const [count, setCount] = useState(initialCount);
  const [loading, setLoading] = useState(false);
  const [pulse, setPulse] = useState(false);

  useEffect(() => {
    setLiked(initialLiked);
  }, [initialLiked]);

  useEffect(() => {
    setCount(initialCount);
  }, [initialCount]);

  const handleClick = useCallback(async () => {
    if (loading) return;
    if (!isLoggedIn()) {
      router.push("/login");
      return;
    }

    const previousLiked = liked;
    const previousCount = count;
    const nextLiked = !liked;
    const delta = nextLiked ? 1 : -1;

    setLoading(true);
    setLiked(nextLiked);
    setCount(Math.max(0, previousCount + delta));
    setPulse(true);

    try {
      const response = nextLiked
        ? await actionService.like({ entityType, entityId })
        : await actionService.unlike({ entityType, entityId });

      const confirmedLiked = response.liked ?? nextLiked;
      setLiked(confirmedLiked);
      setCount(Math.max(0, previousCount + (response.changed ? delta : 0)));
    } catch (error) {
      setLiked(previousLiked);
      setCount(previousCount);
      if (error instanceof ApiError && error.status === 401) {
        router.push("/login");
      }
    } finally {
      setLoading(false);
      window.setTimeout(() => setPulse(false), 240);
    }
  }, [count, entityId, entityType, liked, loading, router]);

  return (
    <button
      type="button"
      onClick={handleClick}
      disabled={loading}
      aria-pressed={liked}
      aria-label={liked ? "取消点赞" : "点赞"}
      className={cn(
        "article-action-button like-button",
        liked && "like-button--active",
        pulse && "article-action-button--pulse",
        className,
      )}
    >
      {loading ? (
        <Loader2 size={18} className="animate-spin" />
      ) : (
        <Heart size={18} fill={liked ? "currentColor" : "none"} />
      )}
      <span>{count}</span>
    </button>
  );
}
