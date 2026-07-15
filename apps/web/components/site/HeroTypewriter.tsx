"use client";

import { useEffect } from "react";
import { useTypewriterSequence } from "@/lib/hooks/useTypewriterSequence";

type HeroTypewriterProps = {
  quotes: readonly string[];
  onLineTransition?: (index: number, durationMs: number) => void;
};

const MIN_BACKGROUND_TRANSITION_MS = 2200;

export default function HeroTypewriter({ quotes, onLineTransition }: HeroTypewriterProps) {
  const { text, index, isDeleting, typeMs, eraseMs } = useTypewriterSequence(quotes);

  useEffect(() => {
    if (!isDeleting || quotes.length < 2) return;

    const nextIndex = (index + 1) % quotes.length;
    const eraseDurationMs = (quotes[index]?.length ?? 0) * eraseMs;
    const typeDurationMs = (quotes[nextIndex]?.length ?? 0) * typeMs;
    onLineTransition?.(
      nextIndex,
      Math.max(MIN_BACKGROUND_TRANSITION_MS, eraseDurationMs + typeDurationMs),
    );
  }, [eraseMs, index, isDeleting, onLineTransition, quotes, typeMs]);

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
