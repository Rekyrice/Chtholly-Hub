"use client";

import { useInView } from "@/hooks/useInView";
import { cn } from "@/lib/utils";
import type { CSSProperties, ReactNode, RefObject } from "react";

export type AnimateInProps = {
  children: ReactNode;
  delay?: number;
  className?: string;
};

export function AnimateIn({ children, delay = 0, className }: AnimateInProps) {
  const { ref, isInView } = useInView();

  return (
    <div
      ref={ref as RefObject<HTMLDivElement>}
      className={cn("animate-in", isInView && "animate-in--visible", className)}
      style={{ "--animate-delay": `${delay}ms` } as CSSProperties}
    >
      {children}
    </div>
  );
}
