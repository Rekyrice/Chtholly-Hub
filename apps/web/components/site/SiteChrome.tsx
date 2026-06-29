"use client";

import { usePathname } from "next/navigation";
import Footer from "@/components/site/Footer";
import Navbar from "@/components/site/Navbar";
import SiteHeader from "@/components/site/SiteHeader";
import AgentPageBackground from "@/components/agent/AgentPageBackground";
import FloatingAgent from "@/components/agent/FloatingAgent";
import { cn } from "@/lib/utils";

export default function SiteChrome({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const isAgentWorkspace = pathname.startsWith("/agent");

  return (
    <div className="min-h-screen flex flex-col">
      <Navbar />
      <div className="h-[52px]" />
      {!isAgentWorkspace && <SiteHeader />}
      <div className={cn("relative", isAgentWorkspace ? "h-[calc(100vh-52px)] min-h-0 overflow-hidden" : "flex-1")}>
        {isAgentWorkspace && <AgentPageBackground />}
        <main
          className={cn(
            "relative z-10",
            isAgentWorkspace
              ? "flex h-full min-h-0 flex-col overflow-hidden py-0 px-0"
              : "flex-1 py-8",
          )}
        >
          <div
            className={cn(
              isAgentWorkspace ? "flex h-full min-h-0 w-full flex-col" : "max-w-6xl mx-auto px-4",
            )}
          >
            {children}
          </div>
        </main>
        {!isAgentWorkspace && (
          <div className="relative z-10">
            <Footer />
          </div>
        )}
      </div>
      {!isAgentWorkspace && <FloatingAgent />}
    </div>
  );
}
