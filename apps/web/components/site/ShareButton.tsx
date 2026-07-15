"use client";

import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { Check, Copy, Share2 } from "lucide-react";
import { cn } from "@/lib/utils";

type ShareButtonProps = {
  href?: string;
  title?: string;
  className?: string;
};

function subscribeLocation(onStoreChange: () => void) {
  window.addEventListener("popstate", onStoreChange);
  window.addEventListener("hashchange", onStoreChange);
  return () => {
    window.removeEventListener("popstate", onStoreChange);
    window.removeEventListener("hashchange", onStoreChange);
  };
}

function getLocationHref() {
  return window.location.href;
}

export default function ShareButton({ href, title, className }: ShareButtonProps) {
  const menuRef = useRef<HTMLDivElement | null>(null);
  const [open, setOpen] = useState(false);
  const [copied, setCopied] = useState(false);
  const locationHref = useSyncExternalStore(subscribeLocation, getLocationHref, () => "");
  const targetUrl = href
    ? locationHref
      ? new URL(href, locationHref).toString()
      : href
    : locationHref;

  const shareTitle = title ?? "Chtholly Hub";
  const twitterHref = `https://twitter.com/intent/tweet?url=${encodeURIComponent(targetUrl)}&text=${encodeURIComponent(shareTitle)}`;
  const weiboHref = `https://service.weibo.com/share/share.php?url=${encodeURIComponent(targetUrl)}&title=${encodeURIComponent(shareTitle)}`;

  useEffect(() => {
    if (!copied) return;
    const timer = window.setTimeout(() => setCopied(false), 2000);
    return () => window.clearTimeout(timer);
  }, [copied]);

  useEffect(() => {
    if (!open) return;

    const handlePointerDown = (event: PointerEvent) => {
      if (!menuRef.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setOpen(false);
      }
    };

    document.addEventListener("pointerdown", handlePointerDown);
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("pointerdown", handlePointerDown);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [open]);

  const handleClick = useCallback(async () => {
    const target = targetUrl || window.location.href;
    try {
      await navigator.clipboard.writeText(target);
      setCopied(true);
      setOpen(false);
    } catch {
      window.prompt("复制链接", target);
    }
  }, [targetUrl]);

  return (
    <div className="share-button-wrap" ref={menuRef}>
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
        className={cn("article-action-button share-button", copied && "share-button--copied", className)}
      >
        {copied ? <Check size={18} /> : <Share2 size={18} />}
        <span>{copied ? "已复制" : "分享"}</span>
      </button>

      {open && (
        <div className="share-menu" role="menu" aria-label="分享菜单">
          <button type="button" onClick={handleClick} role="menuitem" className="share-menu__item">
            <Copy size={16} />
            <span>复制链接</span>
          </button>
          <a href={twitterHref} target="_blank" rel="noreferrer" role="menuitem" className="share-menu__item">
            Twitter
          </a>
          <a href={weiboHref} target="_blank" rel="noreferrer" role="menuitem" className="share-menu__item">
            微博
          </a>
        </div>
      )}

      {copied && <div className="share-toast" role="status">链接已复制</div>}
    </div>
  );
}
