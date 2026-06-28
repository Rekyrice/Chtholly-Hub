import { cn } from "@/lib/utils";
import type { ReactNode } from "react";

export type EmptyStateProps = {
  illustration?: ReactNode;
  title: string;
  description?: string;
  action?: ReactNode;
  className?: string;
};

export function EmptyState({
  illustration,
  title,
  description,
  action,
  className,
}: EmptyStateProps) {
  return (
    <div
      className={cn(
        "flex flex-col items-center justify-center py-16 text-center",
        className,
      )}
    >
      {illustration && <div className="mb-4 text-text-secondary">{illustration}</div>}
      <h3 className="text-lg font-medium text-text">{title}</h3>
      {description && (
        <p className="mt-2 max-w-md text-sm text-text-secondary">{description}</p>
      )}
      {action && <div className="mt-6">{action}</div>}
    </div>
  );
}
