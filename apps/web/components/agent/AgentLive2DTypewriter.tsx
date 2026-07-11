"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { computeTypewriterTiming } from "@/lib/hooks/useTypewriterSequence";

type AgentLive2DTypewriterProps = {
  segments: string[];
  durationSec: number;
  sessionKey: number;
  /** 语音结束后为 true，触发擦除 */
  erasing: boolean;
  onFinished?: () => void;
};

/**
 * Live2D 点击台词：与首页 HeroTypewriter 相同逻辑，单行分段轮播。
 * 语音播放期间持续打字；语音结束后再擦除。
 */
export default function AgentLive2DTypewriter({
  sessionKey,
  ...props
}: AgentLive2DTypewriterProps) {
  return <AgentLive2DTypewriterSession key={sessionKey} {...props} />;
}

function AgentLive2DTypewriterSession({
  segments,
  durationSec,
  erasing,
  onFinished,
}: Omit<AgentLive2DTypewriterProps, "sessionKey">) {
  const { typeMs, eraseMs, pauseMs } = useMemo(
    () => computeTypewriterTiming(durationSec, segments),
    [durationSec, segments],
  );

  const [index, setIndex] = useState(0);
  const [text, setText] = useState("");
  const [isDeleting, setIsDeleting] = useState(false);
  const finishedRef = useRef(false);

  useEffect(() => {
    if (segments.length === 0) return;

    const full = segments[index] ?? "";
    let timeout: ReturnType<typeof setTimeout>;

    const notifyFinished = () => {
      if (finishedRef.current) return;
      finishedRef.current = true;
      onFinished?.();
    };

    if (erasing) {
      if (text === "") {
        notifyFinished();
        return;
      }
      timeout = setTimeout(() => {
        if (!isDeleting) setIsDeleting(true);
        setText(full.slice(0, Math.max(0, text.length - 1)));
      }, eraseMs);
      return () => clearTimeout(timeout);
    }

    if (!isDeleting && text === full) {
      const isLast = index >= segments.length - 1;
      if (isLast) return;
      timeout = setTimeout(() => setIsDeleting(true), pauseMs);
    } else {
      const nextLength = isDeleting ? text.length - 1 : text.length + 1;
      timeout = setTimeout(() => {
        setText(full.slice(0, nextLength));
        if (isDeleting && nextLength <= 0) {
          setIsDeleting(false);
          if (index < segments.length - 1) {
            setIndex((current) => current + 1);
          }
        }
      }, isDeleting ? eraseMs : typeMs);
    }

    return () => clearTimeout(timeout);
  }, [
    text,
    isDeleting,
    index,
    segments,
    erasing,
    typeMs,
    eraseMs,
    pauseMs,
    onFinished,
  ]);

  if (segments.length === 0) return null;

  return (
    <p
      className="agent-live2d-typewriter site-header-typewriter"
      aria-live="polite"
      data-testid="agent-live2d-typewriter"
    >
      <span className="agent-live2d-typewriter-text">{text}</span>
      <span className="site-header-cursor" aria-hidden="true">
        |
      </span>
    </p>
  );
}
