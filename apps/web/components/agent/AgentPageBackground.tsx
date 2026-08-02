"use client";

import { useEffect, useState } from "react";
import {
  AGENT_WALLPAPERS,
  useWallpaperRotation,
} from "@/lib/hooks/useWallpaperRotation";

type AgentWallpaper = (typeof AGENT_WALLPAPERS)[number];

const WALLPAPER_FADE_FALLBACK_MS = 1_100;

/** Agent 工作台全页背景：壁纸轮换 + 半透明蒙版 */
export default function AgentPageBackground() {
  const targetWallpaper = useWallpaperRotation();
  const [currentWallpaper, setCurrentWallpaper] = useState<AgentWallpaper>(
    AGENT_WALLPAPERS[0],
  );
  const [incomingWallpaper, setIncomingWallpaper] = useState<AgentWallpaper | null>(null);

  useEffect(() => {
    if (targetWallpaper === currentWallpaper) return;

    const image = new Image();
    let active = true;
    const detachCallbacks = () => {
      image.onload = null;
      image.onerror = null;
    };

    image.onload = () => {
      if (!active) return;
      active = false;
      detachCallbacks();
      setIncomingWallpaper(targetWallpaper);
    };
    image.onerror = () => {
      if (!active) return;
      active = false;
      detachCallbacks();
      setIncomingWallpaper(null);
    };
    image.src = targetWallpaper;

    return () => {
      active = false;
      detachCallbacks();
    };
  }, [currentWallpaper, targetWallpaper]);

  useEffect(() => {
    if (incomingWallpaper === null || incomingWallpaper !== targetWallpaper) return;

    const fallbackTimer = window.setTimeout(() => {
      setCurrentWallpaper(incomingWallpaper);
      setIncomingWallpaper((pending) =>
        pending === incomingWallpaper ? null : pending,
      );
    }, WALLPAPER_FADE_FALLBACK_MS);

    return () => {
      window.clearTimeout(fallbackTimer);
    };
  }, [incomingWallpaper, targetWallpaper]);

  const promoteIncoming = (wallpaper: AgentWallpaper) => {
    if (incomingWallpaper !== wallpaper || targetWallpaper !== wallpaper) return;
    setCurrentWallpaper(wallpaper);
    setIncomingWallpaper(null);
  };

  return (
    <div className="agent-page-background" aria-hidden="true">
      <div
        className="agent-page-background__layer agent-page-background__layer--current"
        style={{ backgroundImage: `url(${currentWallpaper})` }}
      />
      {incomingWallpaper !== null && incomingWallpaper === targetWallpaper && (
        <div
          className="agent-page-background__layer agent-page-background__layer--incoming"
          style={{ backgroundImage: `url(${incomingWallpaper})` }}
          onAnimationEnd={() => promoteIncoming(incomingWallpaper)}
        />
      )}
    </div>
  );
}
