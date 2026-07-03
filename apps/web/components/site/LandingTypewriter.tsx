"use client";

import { useEffect, useState } from "react";
import { cn } from "@/lib/utils";

type LandingLine = {
  text: string;
  translation?: string;
};

const LINES: readonly LandingLine[] = [
  {
    text: "私は世界で一番幸せな女の子",
    translation: "我是世界上最幸福的女孩",
  },
  {
    text: "每一个故事都值得被记住",
  },
  {
    text: "今天仓库里也很安静呢",
  },
] as const;

const TYPE_DELAY_MS = 72;
const HOLD_DELAY_MS = 4000;
const FADE_DELAY_MS = 420;

export default function LandingTypewriter() {
  const [lineIndex, setLineIndex] = useState(0);
  const [charCount, setCharCount] = useState(0);
  const [isVisible, setIsVisible] = useState(true);

  const current = LINES[lineIndex];
  const visibleText = current.text.slice(0, charCount);

  useEffect(() => {
    let timeout: ReturnType<typeof setTimeout>;

    if (charCount < current.text.length) {
      timeout = setTimeout(() => setCharCount((count) => count + 1), TYPE_DELAY_MS);
      return () => clearTimeout(timeout);
    }

    timeout = setTimeout(() => setIsVisible(false), HOLD_DELAY_MS);
    return () => clearTimeout(timeout);
  }, [charCount, current.text]);

  useEffect(() => {
    if (isVisible) return;

    const timeout = setTimeout(() => {
      setLineIndex((index) => (index + 1) % LINES.length);
      setCharCount(0);
      setIsVisible(true);
    }, FADE_DELAY_MS);

    return () => clearTimeout(timeout);
  }, [isVisible]);

  return (
    <div className={cn("landing-typewriter", !isVisible && "landing-typewriter--hidden")} aria-live="polite">
      <p className="landing-typewriter__text">
        <span>{visibleText}</span>
        <span className="landing-typewriter__cursor" aria-hidden="true">
          |
        </span>
      </p>
      {current.translation && (
        <p className="landing-typewriter__translation">{current.translation}</p>
      )}
    </div>
  );
}
