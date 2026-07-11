"use client";

import { useEffect, useRef, useState } from "react";

export function useInView(options?: IntersectionObserverInit) {
  const ref = useRef<HTMLElement>(null);
  const [hasIntersected, setHasIntersected] = useState(false);
  const [canAnimate, setCanAnimate] = useState(false);

  useEffect(() => {
    const element = ref.current;
    if (!element || typeof IntersectionObserver === "undefined") return;

    let active = true;
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          setHasIntersected(true);
          observer.unobserve(element);
        }
      },
      { threshold: 0.1, ...options },
    );
    observer.observe(element);

    // 先完成观察器注册再隐藏元素，避免 JS/IO 不可用时内容永久不可见。
    queueMicrotask(() => {
      if (active) setCanAnimate(true);
    });

    return () => {
      active = false;
      observer.disconnect();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 页面进入动画只在挂载时观察一次。
  }, []);

  return {
    ref,
    canAnimate,
    isInView: !canAnimate || hasIntersected,
  };
}
