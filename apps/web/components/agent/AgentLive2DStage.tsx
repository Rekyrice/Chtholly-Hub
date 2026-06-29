"use client";

import dynamic from "next/dynamic";
import { useEffect, useRef } from "react";
import { useAgentChatContext } from "@/components/agent/AgentChatProvider";
import { CHTHOLLY_EXPRESSION, CHTHOLLY_TEXTURE_FALLBACK } from "@/lib/live2d/constants";
import { useMinWidth } from "@/lib/hooks/useMinWidth";
import type { Live2DHandle } from "@/lib/types/live2d";

const ChthollyLive2D = dynamic(() => import("@/components/agent/ChthollyLive2D"), {
  ssr: false,
  loading: () => (
    <div className="agent-live2d-loading" data-testid="chtholly-live2d-loading">
      加载珂朵莉…
    </div>
  ),
});

/** Live2D 展示区：桌面端渲染模型，移动端静态图 */
export default function AgentLive2DStage() {
  const { livePhase, busy } = useAgentChatContext();
  const isDesktop = useMinWidth(992);
  const live2dRef = useRef<Live2DHandle>(null);
  const prevPhaseRef = useRef(livePhase);
  const idleTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const speakTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    if (prevPhaseRef.current === livePhase) return;
    prevPhaseRef.current = livePhase;

    const handle = live2dRef.current;
    if (!handle) return;

    switch (livePhase) {
      case "think":
        handle.setExpression(CHTHOLLY_EXPRESSION.neutral);
        break;
      case "act":
        handle.startMotion("tap");
        break;
      case "done":
        handle.setExpression(CHTHOLLY_EXPRESSION.smile);
        handle.setSpeaking(false);
        break;
      case "error":
        handle.setExpression(CHTHOLLY_EXPRESSION.sad);
        handle.setSpeaking(false);
        break;
      case "idle":
        handle.setSpeaking(false);
        break;
      default:
        break;
    }
  }, [livePhase]);

  useEffect(() => {
    if (speakTimerRef.current) {
      clearTimeout(speakTimerRef.current);
      speakTimerRef.current = null;
    }

    if (livePhase !== "speaking") {
      if (livePhase === "done" || livePhase === "error" || livePhase === "idle") {
        live2dRef.current?.setSpeaking(false);
      }
      return;
    }

    speakTimerRef.current = setTimeout(() => {
      live2dRef.current?.setSpeaking(true);
    }, 300);

    return () => {
      if (speakTimerRef.current) {
        clearTimeout(speakTimerRef.current);
        speakTimerRef.current = null;
      }
    };
  }, [livePhase]);

  useEffect(() => {
    if (idleTimerRef.current) {
      clearTimeout(idleTimerRef.current);
      idleTimerRef.current = null;
    }

    if (busy || livePhase !== "idle") return;

    idleTimerRef.current = setTimeout(() => {
      live2dRef.current?.startMotion("idle");
    }, 5000);

    return () => {
      if (idleTimerRef.current) {
        clearTimeout(idleTimerRef.current);
        idleTimerRef.current = null;
      }
    };
  }, [busy, livePhase]);

  if (!isDesktop) {
    return (
      <div className="agent-live2d-stage agent-live2d-stage--mobile" data-testid="agent-live2d-stage">
        <img
          src={CHTHOLLY_TEXTURE_FALLBACK}
          alt="珂朵莉"
          className="agent-live2d-mobile-fallback"
        />
      </div>
    );
  }

  return (
    <div className="agent-live2d-stage" data-testid="agent-live2d-stage">
      <ChthollyLive2D ref={live2dRef} className="agent-live2d-canvas-wrap" />
    </div>
  );
}
