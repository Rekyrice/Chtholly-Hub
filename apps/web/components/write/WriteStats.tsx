"use client";

import { useMemo } from "react";
import { countWritingStats } from "@/lib/utils/markdownInsert";

type WriteStatsProps = {
  markdown: string;
};

export default function WriteStats({ markdown }: WriteStatsProps) {
  const stats = useMemo(() => countWritingStats(markdown), [markdown]);

  return (
    <div className="write-stats" aria-live="polite">
      <span>{stats.charCount} 字</span>
      <span>·</span>
      <span>约 {stats.readingMinutes} 分钟阅读</span>
      <span>·</span>
      <span>{stats.paragraphs} 段</span>
    </div>
  );
}
