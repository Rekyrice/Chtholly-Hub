"use client";

import { useEffect, useState } from "react";

const TYPE_MS = 80;
const PAUSE_MS = 3000;
const ERASE_MS = 40;

type HeroTypewriterProps = {
  quotes: readonly string[];
};

export default function HeroTypewriter({ quotes }: HeroTypewriterProps) {
  const [index, setIndex] = useState(0);
  const [text, setText] = useState("");
  const [isDeleting, setIsDeleting] = useState(false);

  useEffect(() => {
    if (quotes.length === 0) return;

    const full = quotes[index] ?? "";
    let timeout: ReturnType<typeof setTimeout>;

    if (!isDeleting && text === full) {
      timeout = setTimeout(() => setIsDeleting(true), PAUSE_MS);
    } else if (isDeleting && text === "") {
      setIsDeleting(false);
      setIndex((current) => (current + 1) % quotes.length);
    } else {
      const nextLength = isDeleting ? text.length - 1 : text.length + 1;
      timeout = setTimeout(() => {
        setText(full.slice(0, nextLength));
      }, isDeleting ? ERASE_MS : TYPE_MS);
    }

    return () => clearTimeout(timeout);
  }, [text, isDeleting, index, quotes]);

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
