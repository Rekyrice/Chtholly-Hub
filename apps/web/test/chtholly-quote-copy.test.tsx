import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import LandingTypewriter from "@/components/site/LandingTypewriter";
import { siteConfig } from "@/lib/site.config";

const landingQuotes = [
  "場所がどことか関係ない。私は、君と一緒にいたいだけなんだから。",
  "ただいま、帰りました……やっと言えた……",
  "世界一、幸せな女の子だ。",
] as const;

const TYPE_DELAY_MS = 72;
const HOLD_DELAY_MS = 4000;
const FADE_DELAY_MS = 420;

describe("Chtholly quote copy", () => {
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  it("uses the approved Japanese quotes on the landing page", async () => {
    vi.useFakeTimers();
    render(<LandingTypewriter />);

    for (const [index, quote] of landingQuotes.entries()) {
      for (let characterIndex = 0; characterIndex < quote.length; characterIndex += 1) {
        await act(async () => {
          await vi.advanceTimersByTimeAsync(TYPE_DELAY_MS);
        });
      }

      expect(screen.getByText(quote)).toBeInTheDocument();
      expect(document.querySelector(".landing-typewriter__translation")).toBeNull();

      if (index < landingQuotes.length - 1) {
        await act(async () => {
          await vi.advanceTimersByTimeAsync(HOLD_DELAY_MS);
        });
        await act(async () => {
          await vi.advanceTimersByTimeAsync(FADE_DELAY_MS);
        });
      }
    }
  });

  it("uses three full Japanese quotes in the blog hero", () => {
    expect(siteConfig.heroQuotes).toEqual([
      "私のことは、忘れてくれると嬉しいかな。",
      "私……もう、とっくに幸せだったんだって。",
      "だから、きっと……今の私は……誰がなんと言おうと……世界一、幸せな女の子だ。",
    ]);
  });
});
