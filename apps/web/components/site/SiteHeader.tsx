"use client";

import { useEffect, useState } from "react";
import type { CSSProperties } from "react";
import HeroParticles from "@/components/site/HeroParticles";
import HeroTypewriter from "@/components/site/HeroTypewriter";
import type { VisualBackground } from "@/lib/route-visuals";
import { siteConfig } from "@/lib/site.config";

export interface SiteHeaderProps {
  background?: VisualBackground;
}

type HeaderStyle = CSSProperties & Record<`--site-header-${string}`, string>;

export default function SiteHeader({ background }: SiteHeaderProps) {
  const [parallaxY, setParallaxY] = useState(0);
  const [reduceMotion, setReduceMotion] = useState(false);

  useEffect(() => {
    const motionQuery = window.matchMedia("(prefers-reduced-motion: reduce)");
    const syncMotion = () => setReduceMotion(motionQuery.matches);
    syncMotion();
    motionQuery.addEventListener("change", syncMotion);

    const onScroll = () => {
      if (!motionQuery.matches) {
        setParallaxY(window.scrollY * 0.3);
      }
    };

    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });

    return () => {
      motionQuery.removeEventListener("change", syncMotion);
      window.removeEventListener("scroll", onScroll);
    };
  }, []);

  const headerStyle: HeaderStyle | undefined = background
    ? {
        "--site-header-image": `url("${background.image}")`,
        "--site-header-position": background.positionDesktop,
        "--site-header-position-mobile": background.positionMobile,
        "--site-header-overlay": String(background.overlayAlpha),
        "--site-header-blur": `${background.blurPx}px`,
        "--site-header-saturate": String(background.saturate),
      }
    : undefined;

  return (
    <header className="site-header" style={headerStyle} data-testid="site-header">
      <div
        className="site-header-bg"
        data-testid="site-header-background"
        style={
          reduceMotion
            ? undefined
            : { transform: `translate3d(0, ${parallaxY}px, 0)` }
        }
      />
      <HeroParticles />
      <div className="site-header-overlay" />
      <div className="site-header-content">
        <h1 className="site-header-title">{siteConfig.name}</h1>
        <HeroTypewriter quotes={siteConfig.heroQuotes} />
      </div>
    </header>
  );
}
