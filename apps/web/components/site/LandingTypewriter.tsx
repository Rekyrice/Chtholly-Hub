"use client";

import { useEffect, useState } from "react";
import { cn } from "@/lib/utils";

type LandingLine = {
  text: string;
};

const LINES: readonly LandingLine[] = [
  {
    text: "場所がどことか関係ない。私は、君と一緒にいたいだけなんだから。",
  },
  {
    text: "ただいま、帰りました……やっと言えた……",
  },
  {
    text: "世界一、幸せな女の子だ。",
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
    </div>
  );
}
