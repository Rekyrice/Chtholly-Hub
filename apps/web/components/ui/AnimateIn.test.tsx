import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { act, render, screen, waitFor } from "@testing-library/react";
import { Profiler } from "react";
import { hydrateRoot } from "react-dom/client";
import { renderToString } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AnimateIn } from "@/components/ui/AnimateIn";

describe("AnimateIn", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("keeps content visible when IntersectionObserver is unavailable", () => {
    vi.stubGlobal("IntersectionObserver", undefined);

    render(<AnimateIn>渐进增强内容</AnimateIn>);

    const content = screen.getByText("渐进增强内容");
    expect(content).toBeInTheDocument();
    expect(content).toHaveClass("animate-in");
    expect(content).not.toHaveClass("animate-in--ready");
  });

  it("hydrates without a state-driven visible-to-hidden render", async () => {
    const observe = vi.fn();

    class IntersectionObserverMock {
      observe = observe;
      unobserve = vi.fn();
      disconnect = vi.fn();
    }

    vi.stubGlobal("IntersectionObserver", IntersectionObserverMock);

    let hydrationCommitCount = 0;
    function HydrationTree() {
      return (
        <Profiler
          id="animate-in-hydration"
          onRender={() => {
            hydrationCommitCount += 1;
          }}
        >
          <AnimateIn>水合内容</AnimateIn>
        </Profiler>
      );
    }

    const container = document.createElement("div");
    container.innerHTML = renderToString(<HydrationTree />);
    document.body.appendChild(container);

    const content = container.firstElementChild;
    const serverClassName = content?.className;

    const root = hydrateRoot(container, <HydrationTree />);
    await act(async () => {});
    await waitFor(() => expect(content).toHaveClass("animate-in--ready"));
    const hydratedClassName = content?.className;

    act(() => root.unmount());
    container.remove();

    expect(hydrationCommitCount).toBe(1);
    expect(serverClassName).toBe("animate-in");
    expect(observe).toHaveBeenCalledWith(content);
    expect(hydratedClassName).toContain("animate-in--ready");
  });

  it("keeps ready but non-visible content visible when reduced motion is requested", () => {
    const responsiveCss = readFileSync(
      resolve(process.cwd(), "app/styles/responsive.css"),
      "utf8",
    );
    const reducedMotionCss = responsiveCss.slice(
      responsiveCss.indexOf("@media (prefers-reduced-motion: reduce)"),
    );
    const reducedMotionAnimateRule = reducedMotionCss.match(
      /\.animate-in--ready:not\(\.animate-in--visible\),\s*\.animate-in\s*\{([^}]*)\}/,
    );

    expect(reducedMotionAnimateRule).not.toBeNull();
    expect(reducedMotionAnimateRule?.[1]).toMatch(/opacity:\s*1/);
    expect(reducedMotionAnimateRule?.[1]).toMatch(/transform:\s*none/);
    expect(reducedMotionAnimateRule?.[1]).toMatch(/transition:\s*none/);
  });

  it("enables animation only after observing and reveals intersecting content", async () => {
    let observerCallback: IntersectionObserverCallback | undefined;
    const observe = vi.fn();

    class IntersectionObserverMock {
      readonly root = null;
      readonly rootMargin = "0px";
      readonly thresholds = [0.1];

      constructor(callback: IntersectionObserverCallback) {
        observerCallback = callback;
      }

      observe = observe;
      unobserve = vi.fn();
      disconnect = vi.fn();
      takeRecords = vi.fn(() => []);
    }

    vi.stubGlobal("IntersectionObserver", IntersectionObserverMock);

    render(<AnimateIn>可观察内容</AnimateIn>);

    const content = screen.getByText("可观察内容");
    await waitFor(() => expect(observe).toHaveBeenCalledWith(content));
    await waitFor(() => expect(content).toHaveClass("animate-in--ready"));
    expect(content).not.toHaveClass("animate-in--visible");

    act(() => {
      observerCallback?.(
        [{ isIntersecting: true } as IntersectionObserverEntry],
        {} as IntersectionObserver,
      );
    });

    expect(content).toHaveClass("animate-in--visible");
  });
});
