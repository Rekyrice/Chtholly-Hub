"use client";

import { useCallback, useState } from "react";
import { usePathname } from "next/navigation";
import Footer from "@/components/site/Footer";
import MobileBottomNav from "@/components/site/MobileBottomNav";
import Navbar from "@/components/site/Navbar";
import RoutePageBackground from "@/components/site/RoutePageBackground";
import SiteHeader from "@/components/site/SiteHeader";
import AgentPageBackground from "@/components/agent/AgentPageBackground";
import { getAgentRuntimePolicy } from "@/components/agent/agentRuntimePolicy";
import { getRouteVisualConfig } from "@/lib/route-visuals";
import type { RouteVisualConfig } from "@/lib/route-visuals";
import { cn } from "@/lib/utils";

type SiteChromeProps = {
  children: React.ReactNode;
  visualOverride?: RouteVisualConfig;
};

type RouteVisualLayersProps = {
  routeVisual: RouteVisualConfig | null;
  showHeader: boolean;
};

function RouteVisualLayers({ routeVisual, showHeader }: RouteVisualLayersProps) {
  const [backgroundTransition, setBackgroundTransition] = useState({
    activeIndex: 0,
    durationMs: 2200,
  });
  const handleQuoteTransition = useCallback((index: number, durationMs: number) => {
    setBackgroundTransition((current) =>
      current.activeIndex === index && current.durationMs === durationMs
        ? current
        : { activeIndex: index, durationMs },
    );
  }, []);

  return (
    <>
      {routeVisual && (
        <RoutePageBackground
          background={routeVisual.page}
          activeIndex={backgroundTransition.activeIndex}
          transitionMs={backgroundTransition.durationMs}
        />
      )}
      {showHeader && (
        <SiteHeader
          onQuoteTransition={routeVisual?.id === "hub" ? handleQuoteTransition : undefined}
        />
      )}
    </>
  );
}

export default function SiteChrome({ children, visualOverride }: SiteChromeProps) {
  const pathname = usePathname();
  const policy = getAgentRuntimePolicy(pathname);
  const routeVisual = visualOverride ?? getRouteVisualConfig(pathname);
  const isChthollyRoom = pathname === "/chtholly";
  const isFocusedPage = policy.agentWorkspace || policy.writeWorkspace;
  const showHeader = !isFocusedPage && !isChthollyRoom;

  if (policy.landing) {
    return <>{children}</>;
  }

  return (
    <div
      className={cn(
        "site-shell min-h-screen flex flex-col",
        routeVisual && "site-shell--route-visual",
        showHeader && "site-shell--with-header",
      )}
      data-route-visual={routeVisual?.id}
    >
      <Navbar />
      <div className="h-[52px]" />
      <RouteVisualLayers
        key={`${pathname}:${routeVisual?.id ?? "none"}`}
        routeVisual={routeVisual}
        showHeader={showHeader}
      />
      <div className={cn("relative", policy.agentWorkspace ? "h-[calc(100vh-52px)] min-h-0 overflow-hidden" : "flex-1")}>
        {policy.agentWorkspace && <AgentPageBackground />}
        <main
          className={cn(
            "main-content relative z-10",
            policy.agentWorkspace
              ? "flex h-full min-h-0 flex-col overflow-hidden py-0 px-0"
              : policy.writeWorkspace
                ? "flex-1 py-0 px-0"
                : "flex-1 py-8",
          )}
        >
          <div
            className={cn(
              policy.agentWorkspace
                ? "flex h-full min-h-0 w-full flex-col"
                : policy.writeWorkspace
                  ? "w-full"
                  : "max-w-6xl mx-auto px-4",
            )}
          >
            {children}
          </div>
        </main>
        {!isFocusedPage && (
          <div className="relative z-10">
            <Footer />
          </div>
        )}
      </div>
      <MobileBottomNav />
    </div>
  );
}
