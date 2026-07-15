"use client";

import Image from "next/image";
import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import { ChevronLeft, ChevronRight, Flame, Sparkles } from "lucide-react";
import type { RecommendedFeedItem } from "@/lib/services/recommendationService";
import type { FeedItem } from "@/lib/types/post";
import { cn } from "@/lib/utils";

type ChthollyRecommendationProps = {
  posts: Array<FeedItem | RecommendedFeedItem>;
  /** true 时标题为「为你推荐」，并优先展示 reason */
  personalized?: boolean;
  /** 推荐与 fallback 皆空时的兴趣引导空状态 */
  emptyInterest?: boolean;
};

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

  useEffect(() => {
    if (slides.length <= 1) return;
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    const timer = window.setInterval(() => {
      setIndex((current) => (current + 1) % slides.length);
    }, 5000);
    return () => window.clearInterval(timer);
  }, [slides.length]);

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
    <section className="hub-recommendation" aria-label={headingTitle}>
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
              sizes="(max-width: 1024px) 100vw, 820px"
            />
          ) : (
            <span>{active.tags[0] ?? "Chtholly Hub"}</span>
          )}
        </div>
        <div className="hub-recommendation__content">
          {reason ? (
            <span className="hub-recommendation__reason">{reason}</span>
          ) : null}
          <p className="hub-recommendation__meta">
            {active.authorNickname || "仓库居民"}
            {active.tags[0] ? ` · ${active.tags[0]}` : ""}
          </p>
          <h3>{active.title}</h3>
          {active.description ? (
            <p className="hub-recommendation__quote">{active.description}</p>
          ) : null}
        </div>
      </Link>

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

      <div className="hub-recommendation__dots" aria-hidden="true">
        {slides.map((post, dotIndex) => (
          <span
            key={post.id}
            className={cn(dotIndex === index && "hub-recommendation__dot--active")}
          />
        ))}
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
