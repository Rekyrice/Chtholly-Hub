import { act, cleanup, fireEvent, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import AgentPageBackground from "@/components/agent/AgentPageBackground";
import { AGENT_WALLPAPERS } from "@/lib/hooks/useWallpaperRotation";

class ControlledImage {
  static instances: ControlledImage[] = [];

  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;
  src = "";

  constructor() {
    ControlledImage.instances.push(this);
  }

  load() {
    this.onload?.();
  }

  fail() {
    this.onerror?.();
  }
}

function getLayers(container: HTMLElement) {
  return Array.from(
    container.querySelectorAll<HTMLElement>(".agent-page-background__layer"),
  );
}

function expectWallpaper(layer: HTMLElement, wallpaper: string) {
  expect(layer.style.backgroundImage).toContain(wallpaper);
}

describe("AgentPageBackground", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.stubGlobal("Image", ControlledImage);
    vi.spyOn(Math, "random").mockReturnValue(0.3);
    ControlledImage.instances = [];
  });

  afterEach(() => {
    cleanup();
    vi.clearAllTimers();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("keeps the fixed sunset wallpaper after zero-delay timers run", () => {
    const { container } = render(<AgentPageBackground />);

    act(() => {
      vi.advanceTimersByTime(0);
    });

    const layers = getLayers(container);
    expect(layers).toHaveLength(1);
    expectWallpaper(layers[0], AGENT_WALLPAPERS[0]);
    expect(ControlledImage.instances).toHaveLength(0);
  });

  it("keeps the current wallpaper visible while the next wallpaper is loading", () => {
    const { container } = render(<AgentPageBackground />);

    act(() => {
      vi.advanceTimersByTime(300_000);
    });

    expect(ControlledImage.instances).toHaveLength(1);
    expect(ControlledImage.instances[0].src).toBe(AGENT_WALLPAPERS[1]);
    const layers = getLayers(container);
    expect(layers).toHaveLength(1);
    expectWallpaper(layers[0], AGENT_WALLPAPERS[0]);
    expect(layers[0]).toHaveClass("agent-page-background__layer--current");
  });

  it("crossfades the preloaded wallpaper and promotes it after animation end", () => {
    const { container } = render(<AgentPageBackground />);

    act(() => {
      vi.advanceTimersByTime(300_000);
    });
    act(() => {
      ControlledImage.instances[0].load();
    });

    let layers = getLayers(container);
    expect(layers).toHaveLength(2);
    expectWallpaper(layers[0], AGENT_WALLPAPERS[0]);
    expectWallpaper(layers[1], AGENT_WALLPAPERS[1]);
    expect(layers[0]).toHaveClass("agent-page-background__layer--current");
    expect(layers[1]).toHaveClass("agent-page-background__layer--incoming");

    fireEvent(layers[1], new window.Event("webkitAnimationEnd", { bubbles: true }));

    layers = getLayers(container);
    expect(layers).toHaveLength(1);
    expectWallpaper(layers[0], AGENT_WALLPAPERS[1]);
    expect(layers[0]).toHaveClass("agent-page-background__layer--current");
  });

  it("keeps the current wallpaper when preloading fails", () => {
    const { container } = render(<AgentPageBackground />);

    act(() => {
      vi.advanceTimersByTime(300_000);
    });
    act(() => {
      ControlledImage.instances[0].fail();
    });

    const layers = getLayers(container);
    expect(layers).toHaveLength(1);
    expectWallpaper(layers[0], AGENT_WALLPAPERS[0]);
  });

  it("ignores a stale preload when the rotation target changes again", () => {
    const { container } = render(<AgentPageBackground />);

    act(() => {
      vi.advanceTimersByTime(300_000);
    });
    const staleLoad = ControlledImage.instances[0].onload;

    act(() => {
      vi.advanceTimersByTime(300_000);
    });

    expect(ControlledImage.instances).toHaveLength(2);
    expect(ControlledImage.instances[1].src).toBe(AGENT_WALLPAPERS[2]);

    act(() => {
      staleLoad?.();
    });

    let layers = getLayers(container);
    expect(layers).toHaveLength(1);
    expectWallpaper(layers[0], AGENT_WALLPAPERS[0]);

    act(() => {
      ControlledImage.instances[1].load();
    });

    layers = getLayers(container);
    expect(layers).toHaveLength(2);
    expectWallpaper(layers[1], AGENT_WALLPAPERS[2]);
  });

  it("detaches preload callbacks so a late load cannot update after unmount", () => {
    const view = render(<AgentPageBackground />);

    act(() => {
      vi.advanceTimersByTime(300_000);
    });
    const image = ControlledImage.instances[0];
    const lateLoad = image.onload;

    view.unmount();

    expect(image.onload).toBeNull();
    expect(image.onerror).toBeNull();
    expect(vi.getTimerCount()).toBe(0);

    act(() => {
      lateLoad?.();
    });

    expect(view.container).toBeEmptyDOMElement();
    expect(vi.getTimerCount()).toBe(0);
  });

  it("promotes a loaded wallpaper with a fallback when animation end is absent", () => {
    const { container } = render(<AgentPageBackground />);

    act(() => {
      vi.advanceTimersByTime(300_000);
    });
    act(() => {
      ControlledImage.instances[0].load();
    });

    expect(getLayers(container)).toHaveLength(2);

    act(() => {
      vi.advanceTimersByTime(2_000);
    });

    const layers = getLayers(container);
    expect(layers).toHaveLength(1);
    expectWallpaper(layers[0], AGENT_WALLPAPERS[1]);
  });
});
