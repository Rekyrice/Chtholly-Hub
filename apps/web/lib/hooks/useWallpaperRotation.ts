"use client";

import { useEffect, useState } from "react";

export const AGENT_WALLPAPERS = [
  "/images/agent/wallpaper-sunset.jpg",
  "/images/agent/wallpaper-night.jpg",
  "/images/agent/wallpaper-rain.jpg",
  "/images/agent/wallpaper-sakura.jpg",
  "/images/agent/wallpaper-library.jpg",
] as const;

/** 页面加载随机壁纸，之后每 5 分钟顺序轮换（首屏固定壁纸，避免 SSR hydration 不一致） */
export function useWallpaperRotation(intervalMs = 300_000) {
  const [wallpaper, setWallpaper] = useState<(typeof AGENT_WALLPAPERS)[number]>(
    AGENT_WALLPAPERS[0],
  );

  useEffect(() => {
    setWallpaper(AGENT_WALLPAPERS[Math.floor(Math.random() * AGENT_WALLPAPERS.length)]);

    const timer = setInterval(() => {
      setWallpaper((prev) => {
        const index = AGENT_WALLPAPERS.indexOf(prev as (typeof AGENT_WALLPAPERS)[number]);
        const nextIndex = index >= 0 ? (index + 1) % AGENT_WALLPAPERS.length : 0;
        return AGENT_WALLPAPERS[nextIndex];
      });
    }, intervalMs);
    return () => clearInterval(timer);
  }, [intervalMs]);

  return wallpaper;
}
