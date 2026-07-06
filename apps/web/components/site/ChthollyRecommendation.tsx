"use client";

import Image from "next/image";
import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import { ChevronLeft, ChevronRight, Flame } from "lucide-react";
import type { FeedItem } from "@/lib/types/post";
import { cn } from "@/lib/utils";

type ChthollyRecommendationProps = {
  posts: FeedItem[];
};

export default function ChthollyRecommendation({ posts }: ChthollyRecommendationProps) {
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

  if (slides.length === 0) {
    return (
      <section className="hub-recommendation hub-recommendation--empty">
        <div className="hub-section-heading">
          <p>Chtholly Picks</p>
          <h2>珂朵莉推荐</h2>
        </div>
        <p>推荐暂时还没准备好呢。等仓库里多一点新故事吧。</p>
      </section>
    );
  }

  const active = slides[index] ?? slides[0];

  const go = (direction: -1 | 1) => {
    setIndex((current) => (current + direction + slides.length) % slides.length);
  };

  return (
    <section className="hub-recommendation" aria-label="珂朵莉推荐">
      <div className="hub-section-heading">
        <p>Chtholly Picks</p>
        <h2>
          <Flame size={20} />
          珂朵莉推荐
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
          <p className="hub-recommendation__meta">
            {active.authorNickname || "仓库居民"}
            {active.tags[0] ? ` · ${active.tags[0]}` : ""}
          </p>
          <h3>{active.title}</h3>
          <p className="hub-recommendation__quote">
            珂朵莉说：「{buildChthollyComment(active)}」
          </p>
        </div>
      </Link>

      <div className="hub-recommendation__controls">
        <button type="button" onClick={() => go(-1)} aria-label="上一条推荐">
          <ChevronLeft size={18} />
        </button>
        <span>{index + 1}/{slides.length}</span>
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

function buildChthollyComment(post: FeedItem) {
  if (post.description) {
    return `${post.description.slice(0, 48)}${post.description.length > 48 ? "……" : ""}`;
  }
  if (post.tags.includes("技术")) return "这篇看起来很认真呢，适合慢慢读。";
  if (post.tags.some((tag) => ["番剧", "动画", "观后感"].includes(tag))) {
    return "像是在好好记住一个故事。这样的文章，我会多看一会儿。";
  }
  return "这篇写得很用心呢。路过的时候，可以停下来看看。";
}
