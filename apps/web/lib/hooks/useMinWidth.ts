"use client";

import { useEffect, useState } from "react";

/** 监听 min-width 媒体查询，用于桌面/移动布局分支 */
export function useMinWidth(minWidth: number) {
  const [matches, setMatches] = useState(false);

  useEffect(() => {
    const mq = window.matchMedia(`(min-width: ${minWidth}px)`);
    const sync = () => setMatches(mq.matches);
    sync();
    mq.addEventListener("change", sync);
    return () => mq.removeEventListener("change", sync);
  }, [minWidth]);

  return matches;
}
