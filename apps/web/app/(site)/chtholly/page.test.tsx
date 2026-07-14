import { readFileSync } from "node:fs";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import ChthollyRoom from "@/app/(site)/chtholly/page";

const serviceMocks = vi.hoisted(() => ({
  experienceTimeline: vi.fn(),
  feed: vi.fn(),
  topics: vi.fn(),
}));

vi.mock("@/lib/services/agentService", () => ({
  agentService: { experienceTimeline: serviceMocks.experienceTimeline },
}));

vi.mock("@/lib/services/postService", () => ({
  postService: { feed: serviceMocks.feed },
}));

vi.mock("@/lib/services/topicService", () => ({
  topicService: { list: serviceMocks.topics },
}));

vi.mock("@/components/site/ChthollyIllustration", () => ({
  ChthollyIllustration: () => <div data-testid="chtholly-illustration" />,
}));

vi.mock("@/components/agent/ChthollyInlineChat", () => ({
  default: () => <button type="button">和珂朵莉聊天</button>,
}));

describe("ChthollyRoom", () => {
  afterEach(cleanup);

  beforeEach(() => {
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
    serviceMocks.topics.mockResolvedValue([
      {
        topicName: "冬季追番",
        summary: "大家最近在整理追番计划。",
        size: 8,
        keyEntities: [],
        clusteredAt: "2026-07-13T00:00:00Z",
      },
    ]);
  });

  it("shows experiences, topics and recommendations as separate content regions", async () => {
    render(await ChthollyRoom());

    expect(screen.getByText("她最近在想什么")).toBeInTheDocument();
    expect(screen.getByText("她注意到的主题")).toBeInTheDocument();
    expect(screen.getByText("冬季追番")).toBeInTheDocument();
    expect(screen.getByText("她留下的推荐")).toBeInTheDocument();
    expect(screen.queryByText("她看到的社区")).not.toBeInTheDocument();
  });

  it("keeps other regions when topics are unavailable", async () => {
    serviceMocks.topics.mockRejectedValue(new Error("topic extension disabled"));

    render(await ChthollyRoom());

    expect(screen.getByText("她最近在想什么")).toBeInTheDocument();
    expect(screen.getByText("她注意到的主题")).toBeInTheDocument();
    expect(screen.getByText("她留下的推荐")).toBeInTheDocument();
    expect(screen.getByText("窗边暂时没有新的话题。")).toBeInTheDocument();
  });

  it("uses one hero and a strict desktop content grid", async () => {
    const { container } = render(await ChthollyRoom());

    expect(container.querySelectorAll(".chtholly-room-hero")).toHaveLength(1);
    const grid = container.querySelector(".chtholly-room-content-grid");
    expect(grid).not.toBeNull();
    expect(grid?.querySelector(".chtholly-room-experience")).not.toBeNull();
    expect(grid?.querySelector(".chtholly-room-topic")).not.toBeNull();
    expect(grid?.querySelector(".chtholly-room-recommendation")).not.toBeNull();
  });

  it("keeps the experience panel aligned across both right-hand rows", () => {
    const css = readFileSync("app/styles/community.css", "utf8");

    expect(css).toContain("grid-template-rows: repeat(2, minmax(0, 1fr))");
    expect(css).toContain("grid-row: 1 / span 2");
  });
});
