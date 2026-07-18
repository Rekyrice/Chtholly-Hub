import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import ChthollyExperienceTimeline from "@/components/site/ChthollyExperienceTimeline";
import type { AgentExperienceTimeline } from "@/lib/types/agent";

describe("ChthollyExperienceTimeline", () => {
  afterEach(cleanup);

  it("features the highest scored recent experience and uses recency as the tie-break", () => {
    const timeline: AgentExperienceTimeline = {
      recent: [
        {
          text: "较早的同分记录",
          valueScore: 0.8,
          importance: 8,
          createdAt: "2026-07-12T20:00:00Z",
          source: "post",
        },
        {
          text: "今夜最值得记住",
          valueScore: 0.8,
          importance: 8,
          createdAt: "2026-07-13T20:00:00Z",
          source: "post",
        },
        {
          text: "普通记录",
          valueScore: 0.9,
          importance: 4,
          createdAt: "2026-07-13T21:00:00Z",
          source: "post",
        },
      ],
      weeklySummaries: [],
      archived: [],
    };

    const { container } = render(<ChthollyExperienceTimeline timeline={timeline} />);
    const featured = container.querySelector(".room-experience-featured");

    expect(featured).not.toBeNull();
    expect(within(featured as HTMLElement).getByText("今夜最值得记住")).toBeInTheDocument();
    expect(screen.queryByText(/重要度\s*8/)).not.toBeInTheDocument();
  });

  it("keeps the community quiet marker as a light status instead of a featured memory", () => {
    render(
      <ChthollyExperienceTimeline
        timeline={{
          recent: [
            {
              text: "不应作为经历卡出现",
              valueScore: 0,
              importance: 0,
              createdAt: "bad-time",
              source: "community-quiet",
            },
          ],
          weeklySummaries: [],
          archived: [],
        }}
      />,
    );

    expect(screen.getByText("今晚没有特别需要记下的事。")).toBeInTheDocument();
    expect(screen.getByText("今晚社区很安静")).toBeInTheDocument();
    expect(screen.queryByText("不应作为经历卡出现")).not.toBeInTheDocument();
  });

  it("shows an honest note when the entire timeline is empty", () => {
    render(
      <ChthollyExperienceTimeline
        timeline={{ recent: [], weeklySummaries: [], archived: [] }}
      />,
    );

    expect(screen.getByText("今晚没有特别需要记下的事。")).toBeInTheDocument();
    expect(screen.getByText("零散记忆")).toBeInTheDocument();
  });

  it("deduplicates scattered memories and folds entries beyond three into details", () => {
    const timeline: AgentExperienceTimeline = {
      recent: [
        {
          text: "今夜手记正文",
          valueScore: 1,
          importance: 10,
          createdAt: "2026-07-13T23:00:00Z",
          source: "post",
        },
        {
          text: "重复记忆",
          valueScore: 0.5,
          importance: 5,
          createdAt: "2026-07-10T12:00:00Z",
          source: "post",
        },
      ],
      weeklySummaries: [],
      archived: [
        {
          id: 0,
          text: "今夜手记正文",
          importance: 10,
          source: "post",
          createdAt: "2026-07-13T23:00:00Z",
          archivedAt: "2026-07-14T00:00:00Z",
        },
        {
          id: 1,
          text: "重复记忆",
          importance: 5,
          source: "post",
          createdAt: "2026-07-10T12:00:00Z",
          archivedAt: "2026-07-13T00:00:00Z",
        },
        {
          id: 2,
          text: "归档二",
          importance: 4,
          source: "agent",
          createdAt: "not-a-date",
          archivedAt: "2026-07-13T00:00:00Z",
        },
        {
          id: 3,
          text: "归档三",
          importance: 3,
          source: "agent",
          createdAt: "2026-06-01T00:00:00Z",
          archivedAt: "2026-07-13T00:00:00Z",
        },
        {
          id: 4,
          text: "归档四",
          importance: 2,
          source: "agent",
          createdAt: "2026-05-01T00:00:00Z",
          archivedAt: "2026-07-13T00:00:00Z",
        },
      ],
    };

    const { container } = render(<ChthollyExperienceTimeline timeline={timeline} />);

    expect(screen.getAllByText("重复记忆")).toHaveLength(1);
    expect(screen.getAllByText("今夜手记正文")).toHaveLength(1);
    expect(container.querySelectorAll(".room-memory-timeline--primary > li")).toHaveLength(3);
    expect(screen.getByText("还有 1 条记忆").closest("details")).not.toBeNull();
    expect(screen.getByText("归档二")).toBeInTheDocument();
  });

  it("renders multiple weekly summaries as compact letters", () => {
    render(
      <ChthollyExperienceTimeline
        timeline={{
          recent: [],
          weeklySummaries: [
            { weekKey: "2026-W27", summary: "第一封来信" },
            { weekKey: "2026-W28", summary: "第二封来信" },
          ],
          archived: [],
        }}
      />,
    );

    expect(screen.getByText("本周来信")).toBeInTheDocument();
    expect(screen.getByText("第一封来信")).toBeInTheDocument();
    expect(screen.getByText("第二封来信")).toBeInTheDocument();
  });
});
