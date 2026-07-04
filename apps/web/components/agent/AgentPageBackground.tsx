"use client";

import { useWallpaperRotation } from "@/lib/hooks/useWallpaperRotation";

/** Agent 工作台全页背景：壁纸轮换 + 半透明蒙版 */
export default function AgentPageBackground() {
  const wallpaper = useWallpaperRotation();

  return (
    <div className="agent-page-background" aria-hidden="true">
      <div
        key={wallpaper}
        className="agent-page-background__layer"
        style={{ backgroundImage: `url(${wallpaper})` }}
      />
    </div>
  );
}
