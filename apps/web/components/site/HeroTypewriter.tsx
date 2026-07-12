"use client";

import { useEffect } from "react";
import { useTypewriterSequence } from "@/lib/hooks/useTypewriterSequence";

type HeroTypewriterProps = {
  quotes: readonly string[];
  onLineChange?: (index: number) => void;
};

export default function HeroTypewriter({ quotes, onLineChange }: HeroTypewriterProps) {
  const { text, index } = useTypewriterSequence(quotes);

  useEffect(() => {
    if (quotes.length > 0) {
      onLineChange?.(index);
    }
  }, [index, onLineChange, quotes.length]);

  if (quotes.length === 0) return null;

  return (
    <p className="site-header-desc site-header-typewriter" aria-live="polite">
      <span>{text}</span>
      <span className="site-header-cursor" aria-hidden="true">
        |
      </span>
    </p>
  );
}
