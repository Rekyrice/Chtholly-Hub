"use client";

import { useInView } from "@/lib/hooks/useInView";
import { cn } from "@/lib/utils";
import type { CSSProperties, ReactNode } from "react";

export type AnimateInProps = {
  children: ReactNode;
  delay?: number;
  className?: string;
};

export function AnimateIn({ children, delay = 0, className }: AnimateInProps) {
  const ref = useInView<HTMLDivElement>();

  return (
    <div
      ref={ref}
      className={cn("animate-in", className)}
      style={{ "--animate-delay": `${delay}ms` } as CSSProperties}
    >
      {children}
    </div>
  );
}
