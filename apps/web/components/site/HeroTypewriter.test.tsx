import { act, cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import HeroTypewriter from "@/components/site/HeroTypewriter";

describe("HeroTypewriter", () => {
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  it("notifies the active line index from the first line through the next line", async () => {
    vi.useFakeTimers();
    const onLineChange = vi.fn();

    render(<HeroTypewriter quotes={["A", "B"]} onLineChange={onLineChange} />);

    expect(onLineChange).toHaveBeenLastCalledWith(0);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(80);
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3000);
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(40);
    });

    expect(onLineChange).toHaveBeenLastCalledWith(1);
  });

  it("does not notify a line index when there are no quotes", () => {
    const onLineChange = vi.fn();

    const { container } = render(
      <HeroTypewriter quotes={[]} onLineChange={onLineChange} />,
    );

    expect(container).toBeEmptyDOMElement();
    expect(onLineChange).not.toHaveBeenCalled();
  });
});
