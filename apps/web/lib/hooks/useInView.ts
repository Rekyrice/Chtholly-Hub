"use client";

import { useLayoutEffect, useRef } from "react";

export function useInView<T extends HTMLElement>() {
  const ref = useRef<T>(null);

  useLayoutEffect(() => {
    const element = ref.current;
    if (!element || typeof IntersectionObserver === "undefined") return;

    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          element.classList.add("animate-in--visible");
          observer.unobserve(element);
        }
      },
      { threshold: 0.1 },
    );

    element.classList.add("animate-in--ready");
    observer.observe(element);

    return () => {
      observer.disconnect();
      element.classList.remove("animate-in--ready", "animate-in--visible");
    };
  }, []);

  return ref;
}
