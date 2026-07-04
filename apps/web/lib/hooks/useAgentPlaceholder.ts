"use client";

import { useEffect, useState } from "react";

export const AGENT_PLACEHOLDERS = [
  "和珂朵莉说点什么...",
  "今天想聊什么呢？",
  "有什么想问的吗...",
  "我在这里，随时可以听你说。",
  "想说什么就说什么吧。",
] as const;

/** 珂朵莉口吻 placeholder，每 10 秒轮换 */
export function useAgentPlaceholder(intervalMs = 10_000) {
  const [index, setIndex] = useState(0);

  useEffect(() => {
    const timer = setInterval(() => {
      setIndex((prev) => (prev + 1) % AGENT_PLACEHOLDERS.length);
    }, intervalMs);
    return () => clearInterval(timer);
  }, [intervalMs]);

  return AGENT_PLACEHOLDERS[index];
}
