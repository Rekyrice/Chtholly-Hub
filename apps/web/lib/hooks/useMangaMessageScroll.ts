"use client";

import { useEffect, useLayoutEffect, useRef, type RefObject } from "react";

const MANGA_FOCUS_RATIO = 0.34;
const STREAM_SCROLL_DEBOUNCE_MS = 180;

function scrollToFocusBubble(
  bubbleEl: HTMLElement,
  container: HTMLElement,
  smooth: boolean,
) {
  const targetTop = bubbleEl.offsetTop - container.clientHeight * MANGA_FOCUS_RATIO;
  container.scrollTo({
    top: Math.max(0, targetTop),
    behavior: smooth ? "smooth" : "auto",
  });
}

type UseMangaMessageScrollOptions = {
  enabled: boolean;
  scrollContainerRef: RefObject<HTMLElement | null>;
  speakingBubbleRef: RefObject<HTMLElement | null>;
  /** 当前正在说的助手消息 id，变化时平滑滚到焦点区 */
  speakingMessageId: string | null;
  /** 流式输出中，高度变化时节流跟随 */
  streaming: boolean;
};

/** 漫画分格式滚动：最新助手台词保持在视窗焦点带，流式时节流跟随 */
export function useMangaMessageScroll({
  enabled,
  scrollContainerRef,
  speakingBubbleRef,
  speakingMessageId,
  streaming,
}: UseMangaMessageScrollOptions) {
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const prevSpeakingIdRef = useRef<string | null>(null);

  useLayoutEffect(() => {
    if (!enabled || !speakingMessageId) return;

    const container = scrollContainerRef.current;
    if (!container) return;

    const isNewSpeech = speakingMessageId !== prevSpeakingIdRef.current;
    prevSpeakingIdRef.current = speakingMessageId;

    const runScroll = () => {
      const bubble = speakingBubbleRef.current;
      if (!bubble) return;
      scrollToFocusBubble(bubble, container, isNewSpeech);
    };

    runScroll();
    requestAnimationFrame(runScroll);
  }, [enabled, speakingMessageId, scrollContainerRef, speakingBubbleRef]);

  useEffect(() => {
    if (!enabled || !streaming) return;

    const container = scrollContainerRef.current;
    const bubble = speakingBubbleRef.current;
    if (!container || !bubble) return;

    const observer = new ResizeObserver(() => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
      debounceRef.current = setTimeout(() => {
        scrollToFocusBubble(bubble, container, false);
      }, STREAM_SCROLL_DEBOUNCE_MS);
    });

    observer.observe(bubble);
    return () => {
      observer.disconnect();
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, [enabled, streaming, speakingMessageId, scrollContainerRef, speakingBubbleRef]);
}
