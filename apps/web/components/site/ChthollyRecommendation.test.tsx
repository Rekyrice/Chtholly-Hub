import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import ChthollyRecommendation from "@/components/site/ChthollyRecommendation";
import type { FeedItem } from "@/lib/types/post";

const description =
  "手语、视线和手机文字轮流接过一句话，《指尖相触，恋恋不舍》不急着替人物解释，而是把交流本身留给观众。长长的沉默、反复确认的眼神和没有说出口的回答，都应该完整留在文章摘要里。";
const title = "雪说话时，见面会先看她的眼睛：一段关于手语、视线与耐心倾听的漫长故事";

const post: FeedItem = {
  id: "recommendation-1",
  slug: "sign-of-affection",
  title,
  description,
  tags: ["指尖相触恋恋不舍"],
  authorNickname: "kzn",
  publishTime: "2026-07-18T08:30:00.000Z",
};

const secondPost: FeedItem = {
  ...post,
  id: "recommendation-2",
  slug: "second-recommendation",
  title: "第二条推荐",
};

function mockReducedMotion(matches: boolean) {
  vi.mocked(window.matchMedia).mockReturnValue({
    matches,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  } as unknown as MediaQueryList);
}

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

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  it("shows the article summary without presenting it as Chtholly's words", () => {
    render(<ChthollyRecommendation posts={[post]} />);

    expect(screen.getByText(description)).toBeInTheDocument();
    expect(screen.queryByText(/珂朵莉说/)).not.toBeInTheDocument();
  });

  it("keeps long titles and summaries available in the slide content", () => {
    render(<ChthollyRecommendation posts={[post]} />);

    expect(screen.getByText(title, { selector: "h3" })).toBeInTheDocument();
    expect(
      screen.getByText(description, { selector: ".hub-recommendation__quote" }),
    ).toBeInTheDocument();
  });

  it("renders a dedicated track for every editorial field even without a summary", () => {
    const { container } = render(
      <ChthollyRecommendation posts={[{ ...post, description: "" }]} />,
    );
    const content = container.querySelector(".hub-recommendation__content");

    expect(
      content?.querySelector(".hub-recommendation__reason"),
    ).toBeInTheDocument();
    expect(content?.querySelector(".hub-recommendation__meta")).toBeInTheDocument();
    expect(content?.querySelector(".hub-recommendation__title")).toBeInTheDocument();
    expect(content?.querySelector(".hub-recommendation__quote")).toBeEmptyDOMElement();
    expect(content?.querySelector(".hub-recommendation__date")).toBeInTheDocument();
  });

  it("renders the publication date with time semantics", () => {
    render(<ChthollyRecommendation posts={[post]} />);

    expect(screen.getByText("2026年07月18日", { selector: "time" })).toHaveAttribute(
      "dateTime",
      post.publishTime,
    );
  });

  it("keeps every carousel control in a row outside the slide link", () => {
    const { container } = render(<ChthollyRecommendation posts={[post]} />);

    const slide = container.querySelector(".hub-recommendation__slide");
    const controlRow = container.querySelector(".hub-recommendation__control-row");

    expect(controlRow).toBeInTheDocument();
    expect(slide).not.toContainElement(controlRow as HTMLElement);
    expect(controlRow).toContainElement(
      screen.getByRole("button", { name: "上一条推荐" }),
    );
    expect(controlRow).toHaveTextContent("1/1");
    expect(controlRow).toContainElement(
      screen.getByRole("button", { name: "下一条推荐" }),
    );
    expect(controlRow?.querySelector(".hub-recommendation__dots")).toBeInTheDocument();
  });

  it("keeps the reason track populated when a post has no personalized reason", () => {
    render(<ChthollyRecommendation posts={[post]} />);

    expect(
      screen.getByText("热门推荐", { selector: ".hub-recommendation__reason" }),
    ).toBeVisible();
  });

  it("pauses autoplay on hover and resumes after the pointer leaves", () => {
    vi.useFakeTimers();
    mockReducedMotion(false);
    render(<ChthollyRecommendation posts={[post, secondPost]} />);
    const region = screen.getByRole("region", { name: "热门推荐" });

    fireEvent.mouseEnter(region);
    act(() => vi.advanceTimersByTime(5000));
    expect(screen.getByText(title, { selector: "h3" })).toBeInTheDocument();

    fireEvent.mouseLeave(region);
    act(() => vi.advanceTimersByTime(5000));
    expect(screen.getByText(secondPost.title, { selector: "h3" })).toBeInTheDocument();
  });

  it("pauses autoplay while focus is within the recommendation and resumes outside it", () => {
    vi.useFakeTimers();
    mockReducedMotion(false);
    render(<ChthollyRecommendation posts={[post, secondPost]} />);
    const slide = screen.getByRole("link");
    const nextButton = screen.getByRole("button", { name: "下一条推荐" });

    fireEvent.focus(slide);
    act(() => vi.advanceTimersByTime(5000));
    expect(screen.getByText(title, { selector: "h3" })).toBeInTheDocument();

    fireEvent.blur(slide, { relatedTarget: nextButton });
    fireEvent.focus(nextButton);
    act(() => vi.advanceTimersByTime(5000));
    expect(screen.getByText(title, { selector: "h3" })).toBeInTheDocument();

    fireEvent.blur(nextButton, { relatedTarget: document.body });
    act(() => vi.advanceTimersByTime(5000));
    expect(screen.getByText(secondPost.title, { selector: "h3" })).toBeInTheDocument();
  });

  it("does not autoplay when reduced motion is requested", () => {
    vi.useFakeTimers();
    mockReducedMotion(true);
    render(<ChthollyRecommendation posts={[post, secondPost]} />);

    act(() => vi.advanceTimersByTime(10000));
    expect(screen.getByText(title, { selector: "h3" })).toBeInTheDocument();
  });
});
