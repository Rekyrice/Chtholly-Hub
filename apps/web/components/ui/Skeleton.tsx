import { cn } from "@/lib/utils";
import type { CSSProperties } from "react";

export type SkeletonProps = {
  width?: number | string;
  height?: number | string;
  borderRadius?: number | string;
  className?: string;
  pill?: boolean;
};

export function Skeleton({
  width,
  height,
  borderRadius,
  className,
  pill = false,
}: SkeletonProps) {
  const style: CSSProperties = {};

  if (width != null) style.width = typeof width === "number" ? `${width}px` : width;
  if (height != null) style.height = typeof height === "number" ? `${height}px` : height;
  if (borderRadius != null) {
    style.borderRadius =
      typeof borderRadius === "number" ? `${borderRadius}px` : borderRadius;
  }

  return (
    <div
      className={cn("skeleton", pill && "skeleton--pill", className)}
      style={style}
      aria-hidden="true"
    />
  );
}
