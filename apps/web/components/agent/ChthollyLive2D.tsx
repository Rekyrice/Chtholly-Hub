"use client";

import {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
} from "react";
import {
  CHTHOLLY_EXPRESSION,
  CHTHOLLY_MODEL_BASE,
  CHTHOLLY_MODEL_URL,
  LIVE2D_CORE_SCRIPT,
} from "@/lib/live2d/constants";
import { loadScript } from "@/lib/live2d/loadScript";
import {
  fitLive2DModel,
  LIVE2D_LAYOUT_PRESETS,
  type Live2DLayoutPreset,
} from "@/lib/live2d/layout";
import { CHTHOLLY_TAP_LINES, type ChthollyTapLine } from "@/lib/live2d/tapLines";
import type { Live2DHandle } from "@/lib/types/live2d";

type Live2DModelInstance = import("pixi-live2d-display/cubism2").Live2DModel;

type ChthollyLive2DProps = {
  className?: string;
  /** 页面布局预设，控制人物占展示区比例 */
  layoutPreset?: Live2DLayoutPreset;
  /** 模型加载完成 */
  onLoad?: () => void;
  /** 开始播放点击台词 */
  onTapLineStart?: (line: ChthollyTapLine) => void;
  /** 点击台词播放结束 */
  onTapLineEnd?: () => void;
};

const MOUTH_PARAM = "PARAM_MOUTH_OPEN_Y";

/** 鼠标跟随时的额外身体倾斜（叠加在 focus 之上；X 由 focus 驱动，这里只管 Y/Z） */
type BodyLean = { x: number; y: number; z: number };

const BODY_LEAN_IDLE: BodyLean = { x: 0, y: 0, z: 0 };

/** 对身体 Y/Z 做帧间平滑，避免 pointermove 跳变导致闪动 */
class BodyLeanSmoother {
  private target = { ...BODY_LEAN_IDLE };
  private current = { ...BODY_LEAN_IDLE };

  setTarget(y: number, z: number) {
    this.target.y = y;
    this.target.z = z;
  }

  resetTarget() {
    this.target = { ...BODY_LEAN_IDLE };
  }

  update(dt: number) {
    // 时间常数约 380ms，与 focusController 手感接近
    const t = 1 - Math.exp(-0.007 * Math.max(dt, 0));
    this.current.y += (this.target.y - this.current.y) * t;
    this.current.z += (this.target.z - this.current.z) * t;
  }

  read(): BodyLean {
    const { y, z } = this.current;
    if (Math.abs(y) < 0.02 && Math.abs(z) < 0.02) {
      return BODY_LEAN_IDLE;
    }
    return { x: 0, y, z };
  }
}

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value));
}

function playTapAudio(relativePath: string, volume = 0.65): HTMLAudioElement {
  const audio = new Audio(`${CHTHOLLY_MODEL_BASE}${relativePath}`);
  audio.volume = volume;
  return audio;
}

function pickRandomTapLine(): ChthollyTapLine {
  return CHTHOLLY_TAP_LINES[Math.floor(Math.random() * CHTHOLLY_TAP_LINES.length)]!;
}

const ChthollyLive2D = forwardRef<Live2DHandle, ChthollyLive2DProps>(function ChthollyLive2D(
  { className, layoutPreset = "agent", onLoad, onTapLineStart, onTapLineEnd },
  ref,
) {
  const containerRef = useRef<HTMLDivElement>(null);
  const modelRef = useRef<Live2DModelInstance | null>(null);
  const speakingRef = useRef(false);
  const mouthTickRef = useRef(0);
  const onLoadRef = useRef(onLoad);
  const onTapLineStartRef = useRef(onTapLineStart);
  const onTapLineEndRef = useRef(onTapLineEnd);
  const tapAudioRef = useRef<HTMLAudioElement | null>(null);
  const tapEndTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const playTapLineOnModelRef = useRef<
    (model: Live2DModelInstance, line: ChthollyTapLine) => Promise<void>
  >(() => Promise.resolve());
  const [status, setStatus] = useState<"loading" | "ready" | "error">("loading");

  onLoadRef.current = onLoad;
  onTapLineStartRef.current = onTapLineStart;
  onTapLineEndRef.current = onTapLineEnd;

  const clearTapPlayback = useCallback((model: Live2DModelInstance | null, notify = true) => {
    if (tapEndTimerRef.current) {
      clearTimeout(tapEndTimerRef.current);
      tapEndTimerRef.current = null;
    }
    if (tapAudioRef.current) {
      tapAudioRef.current.pause();
      tapAudioRef.current = null;
    }
    speakingRef.current = false;
    setMouthOpen(model, 0);
    if (notify) onTapLineEndRef.current?.();
  }, []);

  const playTapLineOnModel = useCallback(
    async (model: Live2DModelInstance, line: ChthollyTapLine) => {
      clearTapPlayback(model, false);

      onTapLineStartRef.current?.(line);
      speakingRef.current = true;

      void model.expression(CHTHOLLY_EXPRESSION[line.expression]);
      stopLive2DMotions(model);
      void model.motion("tap", line.motionIndex);

      const audio = playTapAudio(line.sound);
      tapAudioRef.current = audio;

      const finish = () => {
        if (tapAudioRef.current !== audio) return;
        clearTapPlayback(model, true);
      };

      audio.addEventListener("ended", finish, { once: true });
      audio.addEventListener("error", finish, { once: true });

      try {
        await audio.play();
      } catch {
        tapEndTimerRef.current = setTimeout(finish, line.durationSec * 1000);
      }
    },
    [clearTapPlayback],
  );

  playTapLineOnModelRef.current = playTapLineOnModel;

  useImperativeHandle(ref, () => ({
    setExpression(name: string) {
      const model = modelRef.current;
      if (!model) return;
      void model.expression(name);
    },
    startMotion(group: string, index?: number) {
      const model = modelRef.current;
      if (!model) return;
      if (group === "tap") {
        const line =
          index !== undefined && index >= 0 && index < CHTHOLLY_TAP_LINES.length
            ? CHTHOLLY_TAP_LINES[index]
            : pickRandomTapLine();
        void playTapLineOnModel(model, line);
        return;
      }
      void model.motion(group, index);
    },
    playTapLine(index?: number) {
      const model = modelRef.current;
      if (!model) return;
      const line =
        index !== undefined && index >= 0 && index < CHTHOLLY_TAP_LINES.length
          ? CHTHOLLY_TAP_LINES[index]
          : pickRandomTapLine();
      void playTapLineOnModel(model, line);
    },
    setSpeaking(speaking: boolean) {
      speakingRef.current = speaking;
      if (!speaking) {
        setMouthOpen(modelRef.current, 0);
      }
    },
    setParam(id: string, value: number) {
      setLive2DParam(modelRef.current, id, value);
    },
  }));

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    let disposed = false;
    let app: import("pixi.js").Application | null = null;
    let canvasEl: HTMLCanvasElement | null = null;
    let resizeObserver: ResizeObserver | null = null;
    let tickerHook: (() => void) | null = null;
    let pointerCleanup: (() => void) | null = null;
    let onBeforeModelUpdate: (() => void) | null = null;
    const bodyLeanSmoother = new BodyLeanSmoother();
    const bodyLeanRef: { current: BodyLean } = { current: BODY_LEAN_IDLE };

    const layoutConfig = LIVE2D_LAYOUT_PRESETS[layoutPreset];

    const layoutModel = (model: Live2DModelInstance, mountEl: HTMLDivElement) => {
      fitLive2DModel(model, mountEl, layoutConfig);
    };

    async function init() {
      const mount = containerRef.current;
      if (!mount) return;

      try {
        await loadScript(LIVE2D_CORE_SCRIPT);
        if (disposed) return;

        const [{ Application, Ticker }, { Live2DModel }] = await Promise.all([
          import("pixi.js"),
          import("pixi-live2d-display/cubism2"),
        ]);
        if (disposed) return;

        Live2DModel.registerTicker(Ticker as never);

        app = new Application({
          backgroundAlpha: 0,
          antialias: true,
          width: mount.clientWidth || 320,
          height: mount.clientHeight || 480,
          resolution: Math.min(window.devicePixelRatio || 1, 2),
          autoDensity: true,
        });

        canvasEl = app.view as HTMLCanvasElement;
        canvasEl.style.pointerEvents = "auto";
        canvasEl.style.touchAction = "manipulation";
        mount.appendChild(canvasEl);

        app.stage.eventMode = "none";
        app.stage.interactiveChildren = false;

        const model = await Live2DModel.from(CHTHOLLY_MODEL_URL, {
          autoUpdate: false,
          autoInteract: false,
        });
        if (disposed) {
          model.destroy();
          app.destroy(true, { children: true });
          return;
        }

        modelRef.current = model;
        model.registerInteraction = () => {};
        detachFromPixiEvents(model as unknown as PixiEventDetachTarget);
        layoutModel(model, mount);

        app.stage.addChild(model as unknown as import("pixi.js").DisplayObject);

        onBeforeModelUpdate = () => {
          applyBodyLean(model, bodyLeanRef.current);
        };
        model.internalModel.on("beforeModelUpdate", onBeforeModelUpdate);

        tickerHook = () => {
          const dt = app!.ticker.deltaMS;
          model.update(dt);
          bodyLeanSmoother.update(dt);
          bodyLeanRef.current = bodyLeanSmoother.read();
          if (speakingRef.current) {
            mouthTickRef.current += 0.22;
            const v = (Math.sin(mouthTickRef.current) + 1) * 0.22 + 0.08;
            setMouthOpen(model, v);
          }
        };
        app.ticker.add(tickerHook);

        pointerCleanup = bindCanvasPointer(mount, canvasEl, app, model, bodyLeanSmoother, (m) => {
          void playTapLineOnModelRef.current(m, pickRandomTapLine());
        });
        await model.motion("idle");

        resizeObserver = new ResizeObserver(() => {
          const w = mount.clientWidth || 320;
          const h = mount.clientHeight || 480;
          app?.renderer.resize(w, h);
          layoutModel(model, mount);
        });
        resizeObserver.observe(mount);

        setStatus("ready");
        onLoadRef.current?.();
      } catch (err) {
        console.error("[ChthollyLive2D] 初始化失败", err);
        if (!disposed) setStatus("error");
      }
    }

    void init();

    return () => {
      disposed = true;
      clearTapPlayback(modelRef.current, false);
      if (modelRef.current && onBeforeModelUpdate) {
        modelRef.current.internalModel.off("beforeModelUpdate", onBeforeModelUpdate);
      }
      pointerCleanup?.();
      pointerCleanup = null;
      resizeObserver?.disconnect();
      if (tickerHook && app) {
        app.ticker.remove(tickerHook);
      }
      modelRef.current?.destroy();
      modelRef.current = null;
      app?.destroy(true, { children: true });
      app = null;
      container.replaceChildren();
    };
  }, [layoutPreset, clearTapPlayback]);

  return (
    <div
      ref={containerRef}
      className={className}
      data-testid="chtholly-live2d"
      data-status={status}
      aria-label="珂朵莉 Live2D"
      aria-busy={status === "loading"}
    />
  );
});

export default ChthollyLive2D;

function setMouthOpen(model: Live2DModelInstance | null, value: number) {
  setLive2DParam(model, MOUTH_PARAM, value);
}

function setLive2DParam(model: Live2DModelInstance | null, id: string, value: number) {
  const core = model?.internalModel?.coreModel as
    | { setParamFloat?: (id: string, value: number, weight?: number) => void }
    | undefined;
  if (!core?.setParamFloat) return;
  try {
    core.setParamFloat(id, value, 1);
  } catch {
    // 部分模型参数名可能不同，忽略
  }
}

function addLive2DParam(model: Live2DModelInstance | null, id: string, value: number) {
  const core = model?.internalModel?.coreModel as
    | { addToParamFloat?: (id: string, value: number, weight?: number) => void }
    | undefined;
  if (!core?.addToParamFloat) return;
  try {
    core.addToParamFloat(id, value, 1);
  } catch {
    // 忽略
  }
}

/** 在 focus 之后叠加身体 Y/Z 倾斜（X 已由 focusController 平滑驱动） */
function applyBodyLean(model: Live2DModelInstance, lean: BodyLean) {
  if (lean.y === 0 && lean.z === 0) return;
  addLive2DParam(model, "PARAM_BODY_ANGLE_Y", lean.y);
  addLive2DParam(model, "PARAM_BODY_ANGLE_Z", lean.z);
}

/** 将 Live2D 子树从 Pixi 7 命中检测中排除，避免 isInteractive 报错 */
type PixiEventDetachTarget = {
  eventMode?: string;
  interactiveChildren?: boolean;
  children?: PixiEventDetachTarget[];
};

function detachFromPixiEvents(displayObject: PixiEventDetachTarget) {
  displayObject.eventMode = "none";
  displayObject.interactiveChildren = false;
  for (const child of displayObject.children ?? []) {
    detachFromPixiEvents(child);
  }
}

/** 屏幕坐标 → 渲染器坐标，供 focus / tap 使用 */
function pointerToRenderer(
  e: PointerEvent,
  canvas: HTMLCanvasElement,
  app: import("pixi.js").Application,
) {
  const rect = canvas.getBoundingClientRect();
  return {
    x: ((e.clientX - rect.left) / rect.width) * app.renderer.width,
    y: ((e.clientY - rect.top) / rect.height) * app.renderer.height,
  };
}

/** 归一化指针方向：中心为 (0,0)，范围约 ±1 */
function pointerDirection(
  e: PointerEvent,
  canvas: HTMLCanvasElement,
): { dirX: number; dirY: number } {
  const rect = canvas.getBoundingClientRect();
  const dirX = ((rect.left + rect.width / 2 - e.clientX) / rect.width) * 2;
  const dirY = ((rect.top + rect.height / 2 - e.clientY) / rect.height) * 2;
  return { dirX, dirY };
}

function stopLive2DMotions(model: Live2DModelInstance) {
  const motionManager = model.internalModel?.motionManager as
    | { stopAllMotions?: () => void; stopAllMotion?: () => void }
    | undefined;
  motionManager?.stopAllMotions?.();
  motionManager?.stopAllMotion?.();
}

function bindCanvasPointer(
  hitTarget: HTMLElement,
  canvas: HTMLCanvasElement,
  app: import("pixi.js").Application,
  model: Live2DModelInstance,
  bodyLeanSmoother: BodyLeanSmoother,
  onTap: (model: Live2DModelInstance) => void,
) {
  hitTarget.style.cursor = "pointer";
  hitTarget.style.pointerEvents = "auto";

  const syncPointer = (e: PointerEvent) => {
    const { x, y } = pointerToRenderer(e, canvas, app);
    const { dirX, dirY } = pointerDirection(e, canvas);

    model.focus(x, y);
    // 仅设置目标值，由 ticker 逐帧平滑；Z 用较小系数减少中心附近符号翻转
    bodyLeanSmoother.setTarget(
      clamp(dirY * 5.5, -5.5, 5.5),
      clamp(dirX * dirY * -2, -2.5, 2.5),
    );
  };

  const resetPointer = () => {
    bodyLeanSmoother.resetTarget();
    model.focus(app.renderer.width / 2, app.renderer.height * 0.38);
  };

  const onPointerMove = (e: PointerEvent) => {
    syncPointer(e);
  };

  const onPointerDown = (e: PointerEvent) => {
    syncPointer(e);
    const { x, y } = pointerToRenderer(e, canvas, app);
    model.tap(x, y);
    onTap(model);
  };

  hitTarget.addEventListener("pointermove", onPointerMove);
  hitTarget.addEventListener("pointerdown", onPointerDown);
  hitTarget.addEventListener("pointerleave", resetPointer);

  resetPointer();

  return () => {
    hitTarget.removeEventListener("pointermove", onPointerMove);
    hitTarget.removeEventListener("pointerdown", onPointerDown);
    hitTarget.removeEventListener("pointerleave", resetPointer);
    bodyLeanSmoother.resetTarget();
  };
}
