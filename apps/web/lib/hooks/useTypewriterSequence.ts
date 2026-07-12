import { useEffect, useState } from "react";

export type TypewriterTiming = {
  typeMs: number;
  eraseMs: number;
  pauseMs: number;
};

const DEFAULT_TIMING: TypewriterTiming = {
  typeMs: 80,
  eraseMs: 40,
  pauseMs: 3000,
};

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value));
}

/** 按语音时长与分段数估算打字/停留/擦除速度 */
export function computeTypewriterTiming(durationSec: number, segments: string[]): TypewriterTiming {
  const totalMs = Math.max(durationSec * 1000 - 320, 1400);
  const segCount = Math.max(segments.length, 1);
  const totalChars = segments.reduce((n, s) => n + s.length, 0) || 1;

  const typeBudget = totalMs * 0.46;
  const pauseBudget = totalMs * 0.34;
  const eraseBudget = totalMs * 0.2;

  return {
    typeMs: clamp(Math.round(typeBudget / totalChars), 36, 92),
    eraseMs: clamp(Math.round(eraseBudget / totalChars), 22, 56),
    pauseMs: clamp(Math.round(pauseBudget / segCount), 320, 1400),
  };
}

/** 首页 Hero 打字机：循环播放 quotes */
export function useTypewriterSequence(
  lines: readonly string[],
  timing: TypewriterTiming = DEFAULT_TIMING,
) {
  const { typeMs, eraseMs, pauseMs } = timing;
  const [index, setIndex] = useState(0);
  const [text, setText] = useState("");
  const [isDeleting, setIsDeleting] = useState(false);

  useEffect(() => {
    if (lines.length === 0) return;

    const full = lines[index] ?? "";
    let timeout: ReturnType<typeof setTimeout>;

    if (!isDeleting && text === full) {
      timeout = setTimeout(() => setIsDeleting(true), pauseMs);
    } else {
      const nextLength = isDeleting ? text.length - 1 : text.length + 1;
      timeout = setTimeout(() => {
        setText(full.slice(0, nextLength));
        if (isDeleting && nextLength <= 0) {
          setIsDeleting(false);
          setIndex((current) => (current + 1) % lines.length);
        }
      }, isDeleting ? eraseMs : typeMs);
    }

    return () => clearTimeout(timeout);
  }, [text, isDeleting, index, lines, typeMs, eraseMs, pauseMs]);

  return { text, index };
}
