"use client";

import { useCallback, useEffect, useState } from "react";
import { Check, Share2 } from "lucide-react";
import { cn } from "@/lib/utils";

type ShareButtonProps = {
  href?: string;
  className?: string;
};

export default function ShareButton({ href, className }: ShareButtonProps) {
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!copied) return;
    const timer = window.setTimeout(() => setCopied(false), 1800);
    return () => window.clearTimeout(timer);
  }, [copied]);

  const handleClick = useCallback(async () => {
    const target = href ? new URL(href, window.location.origin).toString() : window.location.href;
    try {
      await navigator.clipboard.writeText(target);
      setCopied(true);
    } catch {
      window.prompt("复制链接", target);
    }
  }, [href]);

  return (
    <button
      type="button"
      onClick={handleClick}
      className={cn("article-action-button share-button", copied && "share-button--copied", className)}
    >
      {copied ? <Check size={18} /> : <Share2 size={18} />}
      <span>{copied ? "已复制" : "分享"}</span>
    </button>
  );
}
