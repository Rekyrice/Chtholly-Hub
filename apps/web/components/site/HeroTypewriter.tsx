"use client";

import { useTypewriterSequence } from "@/lib/hooks/useTypewriterSequence";

type HeroTypewriterProps = {
  quotes: readonly string[];
};

export default function HeroTypewriter({ quotes }: HeroTypewriterProps) {
  const { text } = useTypewriterSequence(quotes);

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
