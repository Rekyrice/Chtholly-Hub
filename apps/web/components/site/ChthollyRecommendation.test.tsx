import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import ChthollyRecommendation from "@/components/site/ChthollyRecommendation";
import type { FeedItem } from "@/lib/types/post";

const description =
  "手语、视线和手机文字轮流接过一句话，《指尖相触，恋恋不舍》不急着替人物解释，而是把交流本身留给观众。";

const post: FeedItem = {
  id: "recommendation-1",
  slug: "sign-of-affection",
  title: "雪说话时，见面会先看她的眼睛",
  description,
  tags: ["指尖相触恋恋不舍"],
  authorNickname: "kzn",
};

describe("ChthollyRecommendation", () => {
  beforeEach(() => {
    Object.defineProperty(window, "matchMedia", {
      configurable: true,
      value: vi.fn().mockReturnValue({
        matches: true,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      }),
    });
  });

  afterEach(cleanup);

  it("shows the article summary without presenting it as Chtholly's words", () => {
    render(<ChthollyRecommendation posts={[post]} />);

    expect(screen.getByText(description)).toBeInTheDocument();
    expect(screen.queryByText(/珂朵莉说/)).not.toBeInTheDocument();
  });
});
