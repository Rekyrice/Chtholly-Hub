import { act, cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import HeroTypewriter from "@/components/site/HeroTypewriter";

describe("HeroTypewriter", () => {
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  it("starts the next background transition when deletion begins", async () => {
    vi.useFakeTimers();
    const onLineTransition = vi.fn();

    render(
      <HeroTypewriter
        quotes={["A", "B"]}
        onLineTransition={onLineTransition}
      />,
    );

    expect(onLineTransition).not.toHaveBeenCalled();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(80);
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3000);
    });

    expect(onLineTransition).toHaveBeenCalledTimes(1);
    expect(onLineTransition).toHaveBeenLastCalledWith(1, 2200);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(40);
    });

    expect(onLineTransition).toHaveBeenCalledTimes(1);
  });

  it("does not notify a line index when there are no quotes", () => {
    const onLineTransition = vi.fn();

    const { container } = render(
      <HeroTypewriter quotes={[]} onLineTransition={onLineTransition} />,
    );

    expect(container).toBeEmptyDOMElement();
    expect(onLineTransition).not.toHaveBeenCalled();
  });
});
