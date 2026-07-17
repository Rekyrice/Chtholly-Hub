import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import ArticleReadingNavigator from "@/components/site/ArticleReadingNavigator";
import MarkdownContent from "@/components/site/MarkdownContent";

type FrameQueue = Map<number, FrameRequestCallback>;

let frameQueue: FrameQueue;
let frameId: number;

beforeEach(() => {
  frameQueue = new Map();
  frameId = 0;
  vi.spyOn(window, "requestAnimationFrame").mockImplementation((callback) => {
    frameId += 1;
    frameQueue.set(frameId, callback);
    return frameId;
  });
  vi.spyOn(window, "cancelAnimationFrame").mockImplementation((id) => {
    frameQueue.delete(id);
  });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  document.querySelectorAll("[data-article-body], #start, #details").forEach((node) => node.remove());
});

function flushLatestFrame() {
  const latest = [...frameQueue.entries()].at(-1);
  if (!latest) return;
  frameQueue.delete(latest[0]);
  act(() => latest[1](performance.now()));
}

describe("ArticleReadingNavigator", () => {
  it("shows concrete reading clues instead of an empty table of contents", () => {
    render(
      <ArticleReadingNavigator
        headings={[]}
        clues={["先认识浮空岛的来历。", "再看两个人如何重逢。"]}
      />,
    );

    expect(screen.queryByRole("navigation", { name: "本文目录" })).not.toBeInTheDocument();
    expect(screen.getByText("阅读线索")).toBeInTheDocument();
    expect(screen.getByText("先认识浮空岛的来历。")).toBeInTheDocument();
    expect(screen.getByText("再看两个人如何重逢。")).toBeInTheDocument();
  });

  it("updates reading progress and the active heading in one animation frame", () => {
    const body = document.createElement("div");
    body.dataset.articleBody = "";
    let bodyTop = -400;
    Object.defineProperty(body, "scrollHeight", { value: 1600 });
    body.getBoundingClientRect = () => ({
      top: bodyTop,
      bottom: bodyTop + 1600,
      height: 1600,
      left: 0,
      right: 760,
      width: 760,
      x: 0,
      y: bodyTop,
      toJSON: () => ({}),
    });
    document.body.append(body);

    const start = document.createElement("h2");
    start.id = "start";
    start.getBoundingClientRect = () => ({
      top: -160,
      bottom: -120,
      height: 40,
      left: 0,
      right: 760,
      width: 760,
      x: 0,
      y: -160,
      toJSON: () => ({}),
    });
    const details = document.createElement("h2");
    details.id = "details";
    let detailsTop = 260;
    details.getBoundingClientRect = () => ({
      top: detailsTop,
      bottom: detailsTop + 40,
      height: 40,
      left: 0,
      right: 760,
      width: 760,
      x: 0,
      y: detailsTop,
      toJSON: () => ({}),
    });
    document.body.append(start, details);
    Object.defineProperty(window, "innerHeight", { configurable: true, value: 800 });

    render(
      <ArticleReadingNavigator
        headings={[
          { level: 2, id: "start", text: "开始", sourceLine: 1 },
          { level: 2, id: "details", text: "细节", sourceLine: 8 },
        ]}
        clues={[]}
      />,
    );
    flushLatestFrame();

    expect(screen.getByRole("progressbar", { name: "正文阅读进度" })).toHaveAttribute(
      "aria-valuenow",
      "50",
    );
    expect(screen.getByRole("link", { name: "开始" })).toHaveAttribute(
      "aria-current",
      "location",
    );

    bodyTop = -640;
    detailsTop = 72;
    act(() => window.dispatchEvent(new Event("scroll")));
    expect(window.requestAnimationFrame).toHaveBeenCalledTimes(2);
    flushLatestFrame();

    expect(screen.getByRole("progressbar", { name: "正文阅读进度" })).toHaveAttribute(
      "aria-valuenow",
      "80",
    );
    expect(screen.getByRole("link", { name: "细节" })).toHaveAttribute(
      "aria-current",
      "location",
    );
  });

  it("tracks a short article continuously until it is fully visible and passed", () => {
    const body = document.createElement("div");
    body.dataset.articleBody = "";
    let bodyTop = 600;
    Object.defineProperty(body, "scrollHeight", { value: 400 });
    body.getBoundingClientRect = () => ({
      top: bodyTop,
      bottom: bodyTop + 400,
      height: 400,
      left: 0,
      right: 760,
      width: 760,
      x: 0,
      y: bodyTop,
      toJSON: () => ({}),
    });
    document.body.append(body);
    Object.defineProperty(window, "innerHeight", { configurable: true, value: 800 });

    render(<ArticleReadingNavigator headings={[]} clues={[]} />);
    flushLatestFrame();
    expect(screen.getByRole("progressbar", { name: "正文阅读进度" })).toHaveAttribute(
      "aria-valuenow",
      "50",
    );

    bodyTop = 200;
    act(() => window.dispatchEvent(new Event("scroll")));
    flushLatestFrame();
    expect(screen.getByRole("progressbar", { name: "正文阅读进度" })).toHaveAttribute(
      "aria-valuenow",
      "100",
    );

    bodyTop = -500;
    act(() => window.dispatchEvent(new Event("scroll")));
    flushLatestFrame();
    expect(screen.getByRole("progressbar", { name: "正文阅读进度" })).toHaveAttribute(
      "aria-valuenow",
      "100",
    );
  });

  it("keeps zero-height and missing article bodies at zero progress", () => {
    const body = document.createElement("div");
    body.dataset.articleBody = "";
    Object.defineProperty(body, "scrollHeight", { value: 0 });
    body.getBoundingClientRect = () => ({
      top: 0,
      bottom: 0,
      height: 0,
      left: 0,
      right: 0,
      width: 0,
      x: 0,
      y: 0,
      toJSON: () => ({}),
    });
    document.body.append(body);

    render(<ArticleReadingNavigator headings={[]} clues={[]} />);
    flushLatestFrame();
    expect(screen.getByRole("progressbar", { name: "正文阅读进度" })).toHaveAttribute(
      "aria-valuenow",
      "0",
    );

    body.remove();
    act(() => window.dispatchEvent(new Event("scroll")));
    flushLatestFrame();
    expect(screen.getByRole("progressbar", { name: "正文阅读进度" })).toHaveAttribute(
      "aria-valuenow",
      "0",
    );
  });

  it("cancels pending work and removes listeners on unmount", () => {
    const removeListener = vi.spyOn(window, "removeEventListener");
    const { unmount } = render(
      <ArticleReadingNavigator
        headings={[{ level: 2, id: "start", text: "开始", sourceLine: 1 }]}
        clues={[]}
      />,
    );

    unmount();

    expect(window.cancelAnimationFrame).toHaveBeenCalledTimes(1);
    expect(removeListener).toHaveBeenCalledWith("scroll", expect.any(Function));
    expect(removeListener).toHaveBeenCalledWith("resize", expect.any(Function));
  });
});

describe("MarkdownContent", () => {
  it("marks the stable article body used by the reading navigator", () => {
    const { container } = render(<MarkdownContent content="第一段正文。" />);

    expect(container.querySelector("[data-article-body]")).toHaveTextContent("第一段正文。");
  });
});
