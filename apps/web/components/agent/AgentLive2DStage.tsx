"use client";

import dynamic from "next/dynamic";
import { useCallback, useEffect, useRef, useState } from "react";
import AgentLive2DTypewriter from "@/components/agent/AgentLive2DTypewriter";
import { ChthollyIllustration, type IllustrationState } from "@/components/site/ChthollyIllustration";
import { useAgentChatContext } from "@/components/agent/AgentChatProvider";
import {
  CHTHOLLY_CHEEK_THINK,
  CHTHOLLY_EXPRESSION,
  CHTHOLLY_PARAM,
} from "@/lib/live2d/constants";
import { parseLiveStepEvent } from "@/lib/live2d/liveStepEvent";
import { formatTapLineJa, type ChthollyTapLine } from "@/lib/live2d/tapLines";
import { useMinWidth } from "@/lib/hooks/useMinWidth";
import type { Live2DHandle } from "@/lib/types/live2d";
import { cn } from "@/lib/utils";

const ChthollyLive2D = dynamic(() => import("@/components/agent/ChthollyLive2D"), {
  ssr: false,
  loading: () => (
    <div className="agent-live2d-loading" data-testid="chtholly-live2d-loading">
      加载珂朵莉…
    </div>
  ),
});

const IDLE_DELAY_MS = 5000;
const SPEAK_DEBOUNCE_MS = 300;
const RIPPLE_DURATION_MS = 800;
const ENTER_DURATION_MS = 600;

type TapLineSession = {
  key: number;
  segments: string[];
  durationSec: number;
  erasing: boolean;
};

/** Live2D 展示区：监听 liveSteps / 流式状态驱动珂朵莉表情与动作 */
export default function AgentLive2DStage() {
  const { liveSteps, streaming, lastError, busy } = useAgentChatContext();
  const isDesktop = useMinWidth(768);
  const live2dRef = useRef<Live2DHandle>(null);
  const stageInnerRef = useRef<HTMLDivElement>(null);
  const idleTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const speakTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const enterTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const prevStreamingRef = useRef(false);
  const prevLiveStepsLenRef = useRef(0);
  const busyRef = useRef(busy);
  const streamingRef = useRef(streaming);
  const [tapLineSession, setTapLineSession] = useState<TapLineSession | null>(null);
  const [ripples, setRipples] = useState<Array<{ id: number; x: number; y: number }>>([]);
  const [phase, setPhase] = useState<"loading" | "entering" | "breathing">("loading");

  useEffect(() => {
    busyRef.current = busy;
    streamingRef.current = streaming;
  }, [busy, streaming]);

  const onTapLineStart = useCallback((line: ChthollyTapLine) => {
    setTapLineSession({
      key: Date.now(),
      segments: formatTapLineJa(line.textJa),
      durationSec: line.durationSec,
      erasing: false,
    });
  }, []);

  const onTapLineEnd = useCallback(() => {
    setTapLineSession((prev) => (prev ? { ...prev, erasing: true } : null));
  }, []);

  const onTapLineTypewriterFinished = useCallback(() => {
    setTapLineSession(null);
  }, []);

  const handleCanvasPointerDown = useCallback((detail: { clientX: number; clientY: number }) => {
    const inner = stageInnerRef.current;
    if (!inner) return;

    const rect = inner.getBoundingClientRect();
    const x = detail.clientX - rect.left;
    const y = detail.clientY - rect.top;
    const id = Date.now() + Math.random();

    setRipples((prev) => [...prev, { id, x, y }]);
    window.setTimeout(() => {
      setRipples((prev) => prev.filter((ripple) => ripple.id !== id));
    }, RIPPLE_DURATION_MS);
  }, []);

  const handleLoad = useCallback(() => {
    if (enterTimerRef.current) {
      clearTimeout(enterTimerRef.current);
      enterTimerRef.current = null;
    }

    setPhase("entering");
    enterTimerRef.current = setTimeout(() => {
      setPhase("breathing");
      enterTimerRef.current = null;
    }, ENTER_DURATION_MS);
  }, []);

  const clearCheek = useCallback(() => {
    live2dRef.current?.setParam(CHTHOLLY_PARAM.cheek, 0);
  }, []);

  const resetIdleTimer = useCallback(() => {
    if (idleTimerRef.current) {
      clearTimeout(idleTimerRef.current);
      idleTimerRef.current = null;
    }
    idleTimerRef.current = setTimeout(() => {
      if (!busyRef.current && !streamingRef.current) {
        live2dRef.current?.startMotion("idle");
      }
    }, IDLE_DELAY_MS);
  }, []);

  // liveSteps 变化：think / act 驱动表情与动作（observe 仅重置空闲计时）
  useEffect(() => {
    if (liveSteps.length === 0) {
      prevLiveStepsLenRef.current = 0;
      resetIdleTimer();
      return;
    }

    if (liveSteps.length === prevLiveStepsLenRef.current) {
      resetIdleTimer();
      return;
    }
    prevLiveStepsLenRef.current = liveSteps.length;

    const last = liveSteps[liveSteps.length - 1];
    const kind = parseLiveStepEvent(last);
    const handle = live2dRef.current;

    if (handle) {
      if (kind === "think") {
        handle.setExpression(CHTHOLLY_EXPRESSION.neutral);
        handle.setParam(CHTHOLLY_PARAM.cheek, CHTHOLLY_CHEEK_THINK);
      } else if (kind === "act") {
        clearCheek();
        handle.playTapLine();
      } else if (kind === "observe") {
        clearCheek();
      }
    }

    resetIdleTimer();
  }, [liveSteps, resetIdleTimer, clearCheek]);

  // 流式输出：防抖后开启嘴型；结束时微笑并闭嘴
  useEffect(() => {
    resetIdleTimer();

    if (speakTimerRef.current) {
      clearTimeout(speakTimerRef.current);
      speakTimerRef.current = null;
    }

    if (streaming) {
      clearCheek();
      speakTimerRef.current = setTimeout(() => {
        live2dRef.current?.setSpeaking(true);
      }, SPEAK_DEBOUNCE_MS);
    } else {
      live2dRef.current?.setSpeaking(false);
      if (prevStreamingRef.current && !lastError) {
        live2dRef.current?.setExpression(CHTHOLLY_EXPRESSION.smile);
        clearCheek();
      }
    }

    prevStreamingRef.current = streaming;

    return () => {
      if (speakTimerRef.current) {
        clearTimeout(speakTimerRef.current);
        speakTimerRef.current = null;
      }
    };
  }, [streaming, lastError, resetIdleTimer, clearCheek]);

  // 错误：难过表情
  useEffect(() => {
    if (!lastError) return;
    live2dRef.current?.setExpression(CHTHOLLY_EXPRESSION.sad);
    live2dRef.current?.setSpeaking(false);
    clearCheek();
    resetIdleTimer();
  }, [lastError, resetIdleTimer, clearCheek]);

  // busy 结束且无流式时重新计时 idle
  useEffect(() => {
    resetIdleTimer();
  }, [busy, resetIdleTimer]);

  useEffect(() => {
    return () => {
      if (idleTimerRef.current) clearTimeout(idleTimerRef.current);
      if (speakTimerRef.current) clearTimeout(speakTimerRef.current);
      if (enterTimerRef.current) clearTimeout(enterTimerRef.current);
    };
  }, []);

  if (!isDesktop) {
    const mobileState: IllustrationState = lastError
      ? "serious"
      : streaming
        ? "speaking"
        : busy
          ? "thinking"
          : "calm";

    return (
      <div
        className="agent-live2d-stage agent-live2d-stage--mobile"
        data-testid="agent-live2d-stage"
      >
        <ChthollyIllustration
          size="sm"
          state={mobileState}
          className="agent-live2d-mobile-illustration"
        />
      </div>
    );
  }

  return (
    <div className="agent-live2d-stage" data-testid="agent-live2d-stage">
      <div
        ref={stageInnerRef}
        className={cn(
          "agent-live2d-stage-inner",
          phase === "entering" && "agent-live2d-stage-inner--entering",
          phase === "breathing" && "agent-live2d-stage-inner--breathing",
        )}
      >
        {ripples.map((ripple) => (
          <div
            key={ripple.id}
            className="agent-ripple"
            style={{ left: ripple.x - 30, top: ripple.y - 30 }}
            aria-hidden="true"
          />
        ))}
        <ChthollyLive2D
          ref={live2dRef}
          className="agent-live2d-canvas-wrap"
          layoutPreset="agent"
          onLoad={handleLoad}
          onTapLineStart={onTapLineStart}
          onTapLineEnd={onTapLineEnd}
          onCanvasPointerDown={handleCanvasPointerDown}
        />
        {tapLineSession && (
          <div className="agent-live2d-caption-layer" aria-hidden={false}>
            <AgentLive2DTypewriter
              segments={tapLineSession.segments}
              durationSec={tapLineSession.durationSec}
              sessionKey={tapLineSession.key}
              erasing={tapLineSession.erasing}
              onFinished={onTapLineTypewriterFinished}
            />
          </div>
        )}
      </div>
    </div>
  );
}
