import { cn } from "@/lib/utils";
import type { HTMLAttributes, ReactNode } from "react";

export type BadgeProps = HTMLAttributes<HTMLSpanElement> & {
  children: ReactNode;
};

export function Badge({ className, children, ...props }: BadgeProps) {
  return (
    <span
      className={cn(
        "inline-flex items-center rounded-full px-3 py-0.5 text-xs",
        "bg-sky/10 text-sky transition-colors duration-150",
        className,
      )}
      {...props}
    >
      {children}
    </span>
  );
}
