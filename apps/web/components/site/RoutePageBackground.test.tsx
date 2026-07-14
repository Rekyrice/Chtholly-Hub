import { readFileSync } from "node:fs";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import RoutePageBackground from "@/components/site/RoutePageBackground";
import type { PageVisualBackground } from "@/lib/route-visuals";

const motion = vi.hoisted(() => ({ reduced: false }));
const frames = vi.hoisted(() => [] as FrameRequestCallback[]);
const preloadedImages = vi.hoisted(() => [] as Array<{ src: string }>);
const imageDecodes = vi.hoisted(() => ({
  deferred: false,
  pending: [] as Array<{ src: string; resolve: () => void }>,
}));

const background: PageVisualBackground = {
  images: ["/one.webp", "/two.webp", "/three.webp"],
  positionDesktop: "52% 40%",
  positionMobile: "56% 44%",
  overlayAlpha: 0.24,
  blurPx: 1.5,
  saturate: 0.93,
};

describe("RoutePageBackground", () => {
  afterEach(cleanup);

  beforeEach(() => {
    motion.reduced = false;
    frames.length = 0;
    window.requestAnimationFrame = vi.fn((callback: FrameRequestCallback) => {
      frames.push(callback);
      return frames.length;
    });
    window.cancelAnimationFrame = vi.fn();
    window.matchMedia = vi.fn().mockImplementation(() => ({
      matches: motion.reduced,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }));
    preloadedImages.length = 0;
    imageDecodes.deferred = false;
    imageDecodes.pending.length = 0;
    vi.stubGlobal("Image", class {
      private imageSrc = "";

      set src(value: string) {
        this.imageSrc = value;
        preloadedImages.push({ src: value });
      }

      decode() {
        if (!imageDecodes.deferred) return Promise.resolve();
        return new Promise<void>((resolve) => {
          imageDecodes.pending.push({ src: this.imageSrc, resolve });
        });
      }
    });
  });

  it("waits for the requested image to decode before starting the crossfade", async () => {
    const { container, rerender } = render(
      <RoutePageBackground background={background} activeIndex={0} />,
    );
    imageDecodes.deferred = true;

    rerender(<RoutePageBackground background={background} activeIndex={1} />);

    expect(container.querySelectorAll("[data-image-index]")).toHaveLength(1);
    await waitFor(() => expect(imageDecodes.pending).toHaveLength(1));
    expect(imageDecodes.pending[0]?.src).toBe("/two.webp");

    await act(async () => imageDecodes.pending[0]?.resolve());
    expect(container.querySelectorAll("[data-image-index]")).toHaveLength(2);
  });

  it("preloads only the next Hub image without adding another DOM layer", async () => {
    const { container, rerender } = render(
      <RoutePageBackground background={background} activeIndex={0} />,
    );

    await waitFor(() => expect(preloadedImages).toEqual([{ src: "/two.webp" }]));
    expect(container.querySelectorAll("[data-image-index]")).toHaveLength(1);

    rerender(<RoutePageBackground background={background} activeIndex={1} />);
    await waitFor(() => expect(preloadedImages.at(-1)).toEqual({ src: "/three.webp" }));
    expect(container.querySelectorAll("[data-image-index]")).toHaveLength(2);
  });

  it("mounts one image for a static route and keeps one shared overlay", () => {
    render(<RoutePageBackground background={{ ...background, images: ["/one.webp"] }} />);

    const wrapper = screen.getByTestId("route-page-background");
    expect(wrapper.style.getPropertyValue("--route-bg-position")).toBe("52% 40%");
    expect(wrapper.style.getPropertyValue("--route-bg-position-mobile")).toBe("56% 44%");
    expect(wrapper.style.getPropertyValue("--route-bg-overlay")).toBe("0.24");
    expect(wrapper.style.getPropertyValue("--route-bg-blur")).toBe("1.5px");
    expect(wrapper.style.getPropertyValue("--route-bg-saturate")).toBe("0.93");
    expect(wrapper.querySelectorAll("[data-image-index]")).toHaveLength(1);
    expect(screen.getAllByTestId("route-page-background-overlay")).toHaveLength(1);
  });

  it("keeps only the current and previous Hub images until the transition ends", async () => {
    const { container, rerender } = render(
      <RoutePageBackground background={background} activeIndex={0} />,
    );
    expect(container.querySelector('[data-image-index="0"]')).toHaveClass(
      "route-page-background__image--active",
    );

    rerender(<RoutePageBackground background={background} activeIndex={1} />);
    await waitFor(() => expect(container.querySelectorAll("[data-image-index]")).toHaveLength(2));
    const previous = container.querySelector('[data-image-index="0"]')!;
    expect(previous).toHaveClass("route-page-background__image--previous-visible");
    const active = container.querySelector('[data-image-index="1"]')!;
    expect(active).not.toHaveClass("route-page-background__image--active");

    act(() => frames.shift()?.(0));
    expect(active).toHaveClass("route-page-background__image--active");
    expect(previous).not.toHaveClass("route-page-background__image--previous-visible");

    fireEvent.transitionEnd(previous, { propertyName: "opacity" });
    expect(container.querySelectorAll("[data-image-index]")).toHaveLength(1);

    rerender(<RoutePageBackground background={background} activeIndex={2} />);
    await waitFor(() => expect(container.querySelectorAll("[data-image-index]")).toHaveLength(2));
    expect(container.querySelector('[data-image-index="0"]')).not.toBeInTheDocument();
    act(() => frames.shift()?.(0));
    const latest = container.querySelector('[data-image-index="2"]')!;
    const fading = container.querySelector('[data-image-index="1"]')!;
    fireEvent.transitionEnd(fading, { propertyName: "opacity" });
    expect(container.querySelectorAll("[data-image-index]")).toHaveLength(1);
    expect(container.querySelector('[data-image-index="1"]')).not.toBeInTheDocument();
    expect(latest).toHaveClass("route-page-background__image--active");
  });

  it("never reads an undefined image when a multi-image background becomes static", async () => {
    const { container, rerender } = render(
      <RoutePageBackground background={background} activeIndex={2} />,
    );

    rerender(
      <RoutePageBackground
        background={{ ...background, images: ["/search.webp"] }}
        activeIndex={0}
      />,
    );

    act(() => frames.shift()?.(0));
    expect(container.innerHTML).not.toContain("undefined");
    expect(container.querySelector('[style*="search.webp"]')).toBeInTheDocument();
  });

  it("ignores a previous layer transition event that arrives before the latest image enters", async () => {
    const { container, rerender } = render(
      <RoutePageBackground background={background} activeIndex={0} />,
    );

    rerender(<RoutePageBackground background={background} activeIndex={1} />);
    await waitFor(() => expect(container.querySelectorAll("[data-image-index]")).toHaveLength(2));
    act(() => frames.shift()?.(0));
    const lateLayer = container.querySelector('[data-image-index="1"]')!;

    rerender(<RoutePageBackground background={background} activeIndex={2} />);
    await waitFor(() => expect(container.querySelectorAll("[data-image-index]")).toHaveLength(2));
    expect(lateLayer).toBe(container.querySelector('[data-image-index="1"]'));

    fireEvent.transitionEnd(lateLayer, { propertyName: "opacity" });
    expect(container.querySelectorAll("[data-image-index]")).toHaveLength(2);
    expect(container.querySelector('[data-image-index="1"]')).toBeInTheDocument();

    act(() => frames.shift()?.(0));
    fireEvent.transitionEnd(lateLayer, { propertyName: "opacity" });
    expect(container.querySelectorAll("[data-image-index]")).toHaveLength(1);
    expect(container.querySelector('[data-image-index="2"]')).toBeInTheDocument();
  });

  it("switches without a crossfade when reduced motion is preferred", () => {
    motion.reduced = true;
    const { container, rerender } = render(
      <RoutePageBackground background={background} activeIndex={0} />,
    );

    rerender(<RoutePageBackground background={background} activeIndex={2} />);

    act(() => frames.shift()?.(0));
    expect(container.querySelectorAll("[data-image-index]")).toHaveLength(1);
    expect(container.querySelector('[data-image-index="2"]')).toBeInTheDocument();
  });

  it("uses a slow ease-out crossfade instead of an abrupt image swap", () => {
    const css = readFileSync("app/styles/route-visuals.css", "utf8");

    expect(css).toMatch(
      /\.route-page-background__image\s*\{[\s\S]*?transition:\s*opacity 1\.1s cubic-bezier\(0\.22, 1, 0\.36, 1\)/,
    );
  });
});
