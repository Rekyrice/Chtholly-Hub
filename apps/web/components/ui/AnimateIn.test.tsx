import { act, render, screen, waitFor } from "@testing-library/react";
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
