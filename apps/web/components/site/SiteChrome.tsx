"use client";

import { usePathname } from "next/navigation";
import Footer from "@/components/site/Footer";
import MobileBottomNav from "@/components/site/MobileBottomNav";
import Navbar from "@/components/site/Navbar";
import RoutePageBackground from "@/components/site/RoutePageBackground";
import SiteHeader from "@/components/site/SiteHeader";
import AgentPageBackground from "@/components/agent/AgentPageBackground";
import { getAgentRuntimePolicy } from "@/components/agent/agentRuntimePolicy";
import { SITE_HEADER_BACKGROUND, getRouteVisualConfig } from "@/lib/route-visuals";
import { cn } from "@/lib/utils";

export default function SiteChrome({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const policy = getAgentRuntimePolicy(pathname);
  const routeVisual = getRouteVisualConfig(pathname);
  const isChthollyRoom = pathname === "/chtholly";
  const isFocusedPage = policy.agentWorkspace || policy.writeWorkspace;

  if (policy.landing) {
    return <>{children}</>;
  }

  return (
    <div
      className={cn("site-shell min-h-screen flex flex-col", routeVisual && "site-shell--route-visual")}
      data-route-visual={routeVisual?.id}
    >
      <Navbar />
      <div className="h-[52px]" />
      {!isFocusedPage && !isChthollyRoom && (
        <SiteHeader background={routeVisual ? SITE_HEADER_BACKGROUND : undefined} />
      )}
      <div className={cn("relative", policy.agentWorkspace ? "h-[calc(100vh-52px)] min-h-0 overflow-hidden" : "flex-1")}>
        {policy.agentWorkspace && <AgentPageBackground />}
        {routeVisual && <RoutePageBackground background={routeVisual.page} />}
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
