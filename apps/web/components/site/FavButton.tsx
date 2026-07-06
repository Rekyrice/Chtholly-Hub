"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { Bookmark, Loader2 } from "lucide-react";
import { isLoggedIn } from "@/lib/auth/tokens";
import { ApiError } from "@/lib/services/apiClient";
import { actionService } from "@/lib/services/actionService";
import { cn } from "@/lib/utils";

type FavButtonProps = {
  entityType: string;
  entityId: string;
  initialFaved?: boolean;
  initialCount?: number;
  className?: string;
};

export default function FavButton({
  entityType,
  entityId,
  initialFaved = false,
  initialCount = 0,
  className,
}: FavButtonProps) {
  const router = useRouter();
  const [faved, setFaved] = useState(initialFaved);
  const [count, setCount] = useState(initialCount);
  const [loading, setLoading] = useState(false);
  const [pulse, setPulse] = useState(false);

  useEffect(() => {
    setFaved(initialFaved);
  }, [initialFaved]);

  useEffect(() => {
    setCount(initialCount);
  }, [initialCount]);

  const handleClick = useCallback(async () => {
    if (loading) return;
    if (!isLoggedIn()) {
      router.push("/login");
      return;
    }

    const previousFaved = faved;
    const previousCount = count;
    const nextFaved = !faved;
    const delta = nextFaved ? 1 : -1;

    setLoading(true);
    setFaved(nextFaved);
    setCount(Math.max(0, previousCount + delta));
    setPulse(true);

    try {
      const response = nextFaved
        ? await actionService.fav({ entityType, entityId })
        : await actionService.unfav({ entityType, entityId });

      const confirmedFaved = response.faved ?? nextFaved;
      setFaved(confirmedFaved);
      setCount(Math.max(0, previousCount + (response.changed ? delta : 0)));
    } catch (error) {
      setFaved(previousFaved);
      setCount(previousCount);
      if (error instanceof ApiError && error.status === 401) {
        router.push("/login");
      }
    } finally {
      setLoading(false);
      window.setTimeout(() => setPulse(false), 240);
    }
  }, [count, entityId, entityType, faved, loading, router]);

  return (
    <button
      type="button"
      onClick={handleClick}
      disabled={loading}
      aria-pressed={faved}
      aria-label={faved ? "取消收藏" : "收藏"}
      className={cn(
        "article-action-button fav-button",
        faved && "fav-button--active",
        pulse && "article-action-button--pulse",
        className,
      )}
    >
      {loading ? (
        <Loader2 size={18} className="animate-spin" />
      ) : (
        <Bookmark size={18} fill={faved ? "currentColor" : "none"} />
      )}
      <span>{count}</span>
    </button>
  );
}
