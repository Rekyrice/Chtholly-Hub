"use client";

import { useEffect, useState } from "react";

export const AGENT_WALLPAPERS = [
  "/images/agent/wallpaper-sunset.jpg",
  "/images/agent/wallpaper-night.jpg",
  "/images/agent/wallpaper-rain.jpg",
  "/images/agent/wallpaper-sakura.jpg",
  "/images/agent/wallpaper-library.jpg",
] as const;

/** 首屏使用固定壁纸，之后每 5 分钟按列表顺序轮换目标。 */
export function useWallpaperRotation(intervalMs = 300_000) {
  const [wallpaperIndex, setWallpaperIndex] = useState(0);

  useEffect(() => {
    const timer = window.setInterval(() => {
      setWallpaperIndex((index) => (index + 1) % AGENT_WALLPAPERS.length);
    }, intervalMs);

    return () => {
      window.clearInterval(timer);
    };
  }, [intervalMs]);

  return AGENT_WALLPAPERS[wallpaperIndex];
}
