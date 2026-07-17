import { readFileSync } from "node:fs";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import ChthollyRoom from "@/app/(site)/chtholly/page";

const serviceMocks = vi.hoisted(() => ({
  experienceTimeline: vi.fn(),
  feed: vi.fn(),
  overview: vi.fn(),
  signals: vi.fn(),
}));
const illustrationProps = vi.hoisted(() => vi.fn());
const topicWindowProps = vi.hoisted(() => vi.fn());

vi.mock("@/lib/services/agentService", () => ({
  agentService: { experienceTimeline: serviceMocks.experienceTimeline },
}));

vi.mock("@/lib/services/postService", () => ({
  postService: { feed: serviceMocks.feed },
}));

vi.mock("@/lib/services/topicService", () => ({
  topicService: { overview: serviceMocks.overview },
}));

vi.mock("@/lib/services/tagService", () => ({
  tagService: { list: serviceMocks.signals },
}));

vi.mock("@/components/site/ChthollyTopicWindow", () => ({
  default: (props: {
    initialOverview?: { state: string; items: Array<{ topicName: string }> };
  }) => {
    topicWindowProps(props);
    return (
      <div data-testid="topic-window" data-state={props.initialOverview?.state ?? "LEGACY"}>
        {props.initialOverview?.items[0]?.topicName}
        {props.initialOverview?.state === "FAILED" && "话题整理暂时没有完成"}
      </div>
    );
  },
}));

vi.mock("@/components/site/ChthollyIllustration", () => ({
  ChthollyIllustration: (props: { src?: string }) => {
    illustrationProps(props);
    return <div data-testid="chtholly-illustration" data-src={props.src} />;
  },
}));

vi.mock("@/components/agent/ChthollyInlineChat", () => ({
  default: () => <button type="button">和珂朵莉聊天</button>,
}));

describe("ChthollyRoom", () => {
  afterEach(cleanup);

  beforeEach(() => {
    Object.values(serviceMocks).forEach((mock) => mock.mockReset());
    illustrationProps.mockClear();
    topicWindowProps.mockClear();
    serviceMocks.experienceTimeline.mockResolvedValue({
      recent: [
        {
          text: "刚刚读完一篇文章。",
          valueScore: 0.7,
          importance: 7,
          createdAt: "2026-07-13T00:00:00Z",
          source: "post",
        },
      ],
      weeklySummaries: [],
      archived: [],
    });
    serviceMocks.feed.mockResolvedValue({
      items: [
        {
          id: "post-1",
          slug: "winter-list",
          title: "冬季追番清单",
          description: "值得慢慢读",
        },
      ],
    });
    serviceMocks.overview.mockResolvedValue({
      items: [
        {
          topicName: "冬季追番",
          summary: "大家最近在整理追番计划。",
          size: 8,
          keyEntities: [],
          clusteredAt: "2026-07-13T00:00:00Z",
        },
      ],
      state: "READY",
      windowDays: 7,
    });
    serviceMocks.signals.mockResolvedValue([
      { id: "tag-1", name: "动画", slug: "anime", usageCount: 12 },
    ]);
  });

  it("shows the room content as three editorial sections in narrative order", async () => {
    render(await ChthollyRoom());

    const headings = screen.getAllByRole("heading", { level: 2 });
    expect(headings.map((heading) => heading.textContent)).toEqual([
      "今夜书桌",
      "窗边便笺",
      "她的书架",
    ]);
    expect(screen.getByText("冬季追番")).toBeInTheDocument();
    expect(screen.getByText("MEMORY")).toBeInTheDocument();
    expect(screen.getByText("WINDOW NOTES")).toBeInTheDocument();
    expect(screen.getByText("BOOKSHELF")).toBeInTheDocument();
  });

  it("passes a failed overview to the retry island without failing the room", async () => {
    serviceMocks.overview.mockRejectedValue(new Error("topic extension disabled"));

    render(await ChthollyRoom());

    expect(screen.getByText("今夜书桌")).toBeInTheDocument();
    expect(screen.getByText("窗边便笺")).toBeInTheDocument();
    expect(screen.getByText("她的书架")).toBeInTheDocument();
    expect(screen.getByText("话题整理暂时没有完成")).toBeInTheDocument();
    expect(screen.getByTestId("topic-window")).toHaveAttribute("data-state", "FAILED");
    expect(topicWindowProps).toHaveBeenCalledWith(
      expect.objectContaining({
        initialOverview: {
          items: [],
          state: "FAILED",
          windowDays: 7,
          reason: "REQUEST_FAILED",
        },
      }),
    );
  });

  it("uses one hero and replaces the equal-card grid with narrative sections", async () => {
    const { container } = render(await ChthollyRoom());

    expect(container.querySelectorAll(".chtholly-room-hero")).toHaveLength(1);
    const sections = container.querySelector(".chtholly-room-sections");
    expect(sections).not.toBeNull();
    expect(container.querySelector(".chtholly-room-content-grid")).toBeNull();
    expect(sections?.querySelector(".chtholly-room-experience")).not.toBeNull();
    expect(sections?.querySelector(".chtholly-room-topic")).not.toBeNull();
    expect(sections?.querySelector(".chtholly-room-recommendation")).not.toBeNull();
  });

  it("renders recommendations as semantic compact books with cover metadata and fallback", async () => {
    serviceMocks.feed.mockResolvedValue({
      items: [
        {
          id: "post-cover",
          slug: "covered-post",
          title: "有封面的文章",
          description: "两行以内的摘要",
          coverImage: "/images/covers/example.jpg",
          tags: ["阅读"],
          authorNickname: "风铃",
          publishTime: "2026-07-13T08:00:00Z",
        },
        {
          id: "post-fallback",
          slug: "fallback-post",
          title: "没有封面的文章",
          description: "",
          tags: ["随笔"],
          authorNickname: "珂朵莉",
        },
      ],
    });

    const { container } = render(await ChthollyRoom());

    expect(screen.getByRole("link", { name: /风铃.*有封面的文章/ })).toHaveAttribute(
      "href",
      "/post/covered-post",
    );
    expect(container.querySelector('.room-book__cover img[alt=""]')).toHaveAttribute(
      "src",
      expect.stringContaining("example.jpg"),
    );
    expect(screen.getByText("风铃")).toBeInTheDocument();
    expect(screen.getByText("随笔")).toBeInTheDocument();
    expect(screen.getByText("她把这篇轻轻放在了书架上。")).toBeInTheDocument();
  });

  it("starts all four room data requests before awaiting any one of them", async () => {
    const timeline = deferred<{
      recent: never[];
      weeklySummaries: never[];
      archived: never[];
    }>();
    const feed = deferred<{ items: never[] }>();
    const overview = deferred<{ items: never[]; state: "PENDING"; windowDays: number }>();
    const signals = deferred<never[]>();
    serviceMocks.experienceTimeline.mockReturnValue(timeline.promise);
    serviceMocks.feed.mockReturnValue(feed.promise);
    serviceMocks.overview.mockReturnValue(overview.promise);
    serviceMocks.signals.mockReturnValue(signals.promise);

    const roomPromise = ChthollyRoom();

    expect(serviceMocks.experienceTimeline).toHaveBeenCalledTimes(1);
    expect(serviceMocks.feed).toHaveBeenCalledTimes(1);
    expect(serviceMocks.overview).toHaveBeenCalledTimes(1);
    expect(serviceMocks.signals).toHaveBeenCalledWith(6);

    timeline.resolve({ recent: [], weeklySummaries: [], archived: [] });
    feed.resolve({ items: [] });
    overview.resolve({ items: [], state: "PENDING", windowDays: 7 });
    signals.resolve([]);
    await roomPromise;
  });

  it("fills the room hero with the approved Chtholly18 image", async () => {
    render(await ChthollyRoom());

    expect(screen.getByTestId("chtholly-illustration")).toHaveAttribute(
      "data-src",
      "/images/illustrations/chtholly18.png",
    );
    expect(illustrationProps).toHaveBeenCalledWith(
      expect.objectContaining({ size: "hero" }),
    );
  });

  it("uses a single narrative stack with an asymmetric desk and compact grids", () => {
    const css = readFileSync("app/styles/community.css", "utf8");

    expect(css).toContain(".chtholly-room-sections");
    expect(css).toContain("grid-template-columns: minmax(0, 1.15fr) minmax(0, 0.85fr)");
    expect(css).toContain("grid-template-columns: repeat(3, minmax(0, 1fr))");
    expect(css).toContain("grid-template-columns: repeat(2, minmax(0, 1fr))");
  });

  it("keeps the expanded room wide and the character vertically centered", () => {
    const css = readFileSync("app/styles/community.css", "utf8");

    expect(css).toContain("width: min(100%, 1360px)");
    expect(css).toContain("place-items: center");
    expect(css).toContain(".chtholly-room-hero__character .chtholly-illustration--hero");
    expect(css).toContain("width: min(100%, 560px)");
  });
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((promiseResolve) => {
    resolve = promiseResolve;
  });
  return { promise, resolve };
}
