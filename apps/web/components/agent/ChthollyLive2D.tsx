"use client";

import {
  forwardRef,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
} from "react";
import {
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
import type { Live2DHandle } from "@/lib/types/live2d";

type Live2DModelInstance = import("pixi-live2d-display/cubism2").Live2DModel;

type MotionDef = {
  file: string;
  sound?: string;
};

type ChthollyLive2DProps = {
  className?: string;
  /** 页面布局预设，控制人物占展示区比例 */
  layoutPreset?: Live2DLayoutPreset;
};

const MOUTH_PARAM = "PARAM_MOUTH_OPEN_Y";

function playSound(relativePath: string, volume = 0.6) {
  const audio = new Audio(`${CHTHOLLY_MODEL_BASE}${relativePath}`);
  audio.volume = volume;
  void audio.play().catch(() => {
    // 浏览器自动播放策略可能拦截
  });
}

const ChthollyLive2D = forwardRef<Live2DHandle, ChthollyLive2DProps>(function ChthollyLive2D(
  { className, layoutPreset = "agent" },
  ref,
) {
  const containerRef = useRef<HTMLDivElement>(null);
  const modelRef = useRef<Live2DModelInstance | null>(null);
  const speakingRef = useRef(false);
  const mouthTickRef = useRef(0);
  const [status, setStatus] = useState<"loading" | "ready" | "error">("loading");

  useImperativeHandle(ref, () => ({
    setExpression(name: string) {
      const model = modelRef.current;
      if (!model) return;
      void model.expression(name);
    },
    startMotion(group: string, index?: number) {
      const model = modelRef.current;
      if (!model) return;
      void startMotionWithSound(model, group, index);
    },
    setSpeaking(speaking: boolean) {
      speakingRef.current = speaking;
      if (!speaking) {
        setMouthOpen(modelRef.current, 0);
      }
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

        // pixi-live2d-display 依赖静态 Ticker 引用；Pixi 7 无 window.PIXI
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
        mount.appendChild(canvasEl);

        // Pixi 7 事件系统会调用 isInteractive()，与 pixi-live2d-display（Pixi 6）不兼容
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
        // 每帧 _render 会尝试绑定 Pixi 6 interaction 插件，Pixi 7 无 manager.on
        model.registerInteraction = () => {};
        detachFromPixiEvents(model as unknown as PixiEventDetachTarget);
        layoutModel(model, mount);

        app.stage.addChild(model as unknown as import("pixi.js").DisplayObject);

        tickerHook = () => {
          model.update(app!.ticker.deltaMS);
          if (speakingRef.current) {
            mouthTickRef.current += 0.22;
            const v = (Math.sin(mouthTickRef.current) + 1) * 0.22 + 0.08;
            setMouthOpen(model, v);
          }
        };
        app.ticker.add(tickerHook);

        pointerCleanup = bindCanvasPointer(canvasEl, app, model);
        await model.motion("idle");

        resizeObserver = new ResizeObserver(() => {
          const w = mount.clientWidth || 320;
          const h = mount.clientHeight || 480;
          app?.renderer.resize(w, h);
          layoutModel(model, mount);
        });
        resizeObserver.observe(mount);

        setStatus("ready");
      } catch (err) {
        console.error("[ChthollyLive2D] 初始化失败", err);
        if (!disposed) setStatus("error");
      }
    }

    void init();

    return () => {
      disposed = true;
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
  }, [layoutPreset]);

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
  const core = model?.internalModel?.coreModel as
    | { setParamFloat?: (id: string, value: number, weight?: number) => void }
    | undefined;
  if (!core?.setParamFloat) return;
  try {
    core.setParamFloat(MOUTH_PARAM, value, 1);
  } catch {
    // 部分模型参数名可能不同，忽略
  }
}

async function startMotionWithSound(
  model: Live2DModelInstance,
  group: string,
  index?: number,
) {
  const settings = model.internalModel?.settings as
    | { motions?: Record<string, MotionDef[]> }
    | undefined;
  const motions = settings?.motions?.[group] ?? [];
  const pick =
    index !== undefined && index >= 0 && index < motions.length
      ? index
      : Math.floor(Math.random() * motions.length);

  const started = await model.motion(group, pick >= 0 ? pick : undefined);
  if (!started) return;

  const sound = motions[pick]?.sound;
  if (sound) {
    playSound(sound, 0.6);
  }
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

function bindCanvasPointer(
  canvas: HTMLCanvasElement,
  app: import("pixi.js").Application,
  model: Live2DModelInstance,
) {
  canvas.style.cursor = "pointer";

  const onPointerDown = (e: PointerEvent) => {
    const rect = canvas.getBoundingClientRect();
    const x = ((e.clientX - rect.left) / rect.width) * app.renderer.width;
    const y = ((e.clientY - rect.top) / rect.height) * app.renderer.height;

    model.tap(x, y);
    const hits = model.hitTest(x, y);
    if (hits.length > 0) {
      void startMotionWithSound(model, "tap");
    }
  };

  canvas.addEventListener("pointerdown", onPointerDown);
  return () => canvas.removeEventListener("pointerdown", onPointerDown);
}
