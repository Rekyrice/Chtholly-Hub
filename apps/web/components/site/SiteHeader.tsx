"use client";

import { useEffect, useState } from "react";
import HeroParticles from "@/components/site/HeroParticles";
import HeroTypewriter from "@/components/site/HeroTypewriter";
import { siteConfig } from "@/lib/site.config";

export default function SiteHeader() {
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

  return (
    <header className="site-header" data-testid="site-header">
      <div
        className="site-header-bg"
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
