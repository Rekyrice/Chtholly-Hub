"use client";

import Image from "next/image";
import Link from "next/link";
import { useEffect, useMemo, useState, useSyncExternalStore } from "react";
import { ChevronLeft, ChevronRight, Flame, Sparkles } from "lucide-react";
import type { RecommendedFeedItem } from "@/lib/services/recommendationService";
import type { FeedItem } from "@/lib/types/post";
import { cn, formatDate } from "@/lib/utils";

type ChthollyRecommendationProps = {
  posts: Array<FeedItem | RecommendedFeedItem>;
  /** true 时标题为「为你推荐」，并优先展示 reason */
  personalized?: boolean;
  /** 推荐与 fallback 皆空时的兴趣引导空状态 */
  emptyInterest?: boolean;
};

const REDUCED_MOTION_QUERY = "(prefers-reduced-motion: reduce)";

function subscribeToReducedMotion(onStoreChange: () => void) {
  const mediaQuery = window.matchMedia(REDUCED_MOTION_QUERY);
  mediaQuery.addEventListener("change", onStoreChange);
  return () => mediaQuery.removeEventListener("change", onStoreChange);
}

function getReducedMotionSnapshot() {
  return window.matchMedia(REDUCED_MOTION_QUERY).matches;
}

function getServerReducedMotionSnapshot() {
  return false;
}

function subscribeToPageVisibility(onStoreChange: () => void) {
  document.addEventListener("visibilitychange", onStoreChange);
  return () => document.removeEventListener("visibilitychange", onStoreChange);
}

function getPageHiddenSnapshot() {
  return document.visibilityState === "hidden";
}

function getServerPageHiddenSnapshot() {
  return false;
}

export default function ChthollyRecommendation({
  posts,
  ...props
}: ChthollyRecommendationProps) {
  const slideKey = posts.slice(0, 5).map((post) => post.id).join(":");
  return <ChthollyRecommendationSlides key={slideKey} posts={posts} {...props} />;
}

function ChthollyRecommendationSlides({
  posts,
  personalized = false,
  emptyInterest = false,
}: ChthollyRecommendationProps) {
  const slides = useMemo(() => posts.slice(0, 5), [posts]);
  const [index, setIndex] = useState(0);
  const [isPointerInside, setIsPointerInside] = useState(false);
  const [isFocusWithin, setIsFocusWithin] = useState(false);
  const prefersReducedMotion = useSyncExternalStore(
    subscribeToReducedMotion,
    getReducedMotionSnapshot,
    getServerReducedMotionSnapshot,
  );
  const isPageHidden = useSyncExternalStore(
    subscribeToPageVisibility,
    getPageHiddenSnapshot,
    getServerPageHiddenSnapshot,
  );

  useEffect(() => {
    if (slides.length <= 1) return;
    if (isPointerInside || isFocusWithin) return;
    if (prefersReducedMotion) return;
    if (isPageHidden) return;
    const timer = window.setInterval(() => {
      setIndex((current) => (current + 1) % slides.length);
    }, 5000);
    return () => window.clearInterval(timer);
  }, [isFocusWithin, isPageHidden, isPointerInside, prefersReducedMotion, slides.length]);

  const headingEyebrow = personalized ? "For You" : "Chtholly Picks";
  const headingTitle = personalized ? "为你推荐" : "热门推荐";
  const HeadingIcon = personalized ? Sparkles : Flame;

  if (emptyInterest || slides.length === 0) {
    return (
      <section className="hub-recommendation hub-recommendation--empty">
        <div className="hub-section-heading">
          <p>{headingEyebrow}</p>
          <h2>
            <HeadingIcon size={20} />
            {headingTitle}
          </h2>
        </div>
        <p>
          {emptyInterest
            ? "还没有发现你的兴趣呢，多逛逛告诉珂朵莉你喜欢什么吧~"
            : "推荐暂时还没准备好呢。等仓库里多一点新故事吧。"}
        </p>
      </section>
    );
  }

  const active = slides[index] ?? slides[0];
  const reason = getReason(active);

  const go = (direction: -1 | 1) => {
    setIndex((current) => (current + direction + slides.length) % slides.length);
  };

  return (
    <section
      className="hub-recommendation"
      aria-label={headingTitle}
      onMouseEnter={() => setIsPointerInside(true)}
      onMouseLeave={() => setIsPointerInside(false)}
      onFocus={() => setIsFocusWithin(true)}
      onBlur={(event) => {
        const nextFocus = event.relatedTarget;
        if (!(nextFocus instanceof Node) || !event.currentTarget.contains(nextFocus)) {
          setIsFocusWithin(false);
        }
      }}
    >
      <div className="hub-section-heading">
        <p>{headingEyebrow}</p>
        <h2>
          <HeadingIcon size={20} />
          {headingTitle}
        </h2>
      </div>

      <Link href={`/post/${active.slug}`} className="hub-recommendation__slide">
        <div className="hub-recommendation__image">
          {active.coverImage ? (
            <Image
              src={active.coverImage}
              alt=""
              fill
              priority
              sizes="(max-width: 767px) 100vw, (max-width: 1024px) 52vw, 58vw"
            />
          ) : (
            <span>{active.tags[0] ?? "Chtholly Hub"}</span>
          )}
        </div>
        <div className="hub-recommendation__content">
          <span className="hub-recommendation__reason">{reason ?? headingTitle}</span>
          <p className="hub-recommendation__meta">
            {active.authorNickname || "仓库居民"}
            {active.tags[0] ? ` · ${active.tags[0]}` : ""}
          </p>
          <h3 className="hub-recommendation__title">{active.title}</h3>
          <p className="hub-recommendation__quote">{active.description}</p>
          <div className="hub-recommendation__date">
            {active.publishTime ? (
              <time dateTime={active.publishTime}>{formatDate(active.publishTime)}</time>
            ) : null}
          </div>
        </div>
      </Link>

      <div
        className="hub-recommendation__control-row"
        role="group"
        aria-label="推荐轮播控制"
      >
        <div className="hub-recommendation__dots" aria-hidden="true">
          {slides.map((post, dotIndex) => (
            <span
              key={post.id}
              className={cn(dotIndex === index && "hub-recommendation__dot--active")}
            />
          ))}
        </div>
        <div className="hub-recommendation__controls">
          <button type="button" onClick={() => go(-1)} aria-label="上一条推荐">
            <ChevronLeft size={18} />
          </button>
          <span>
            {index + 1}/{slides.length}
          </span>
          <button type="button" onClick={() => go(1)} aria-label="下一条推荐">
            <ChevronRight size={18} />
          </button>
        </div>
      </div>
    </section>
  );
}

function getReason(post: FeedItem | RecommendedFeedItem) {
  if ("reason" in post && typeof post.reason === "string" && post.reason.trim()) {
    return post.reason.trim();
  }
  return null;
}
