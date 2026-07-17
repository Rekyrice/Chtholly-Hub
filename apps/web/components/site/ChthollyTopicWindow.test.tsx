import type { ComponentType } from "react";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import ChthollyTopicWindow from "@/components/site/ChthollyTopicWindow";
import { topicService } from "@/lib/services/topicService";
import type { TopicOverview } from "@/lib/types/topic";

vi.mock("@/lib/services/topicService", () => ({
  topicService: { overview: vi.fn() },
}));

const readyOverview: TopicOverview = {
  items: [
    {
      topicName: "冬季追番",
      summary: "大家最近在整理追番计划。",
      size: 8,
      keyEntities: ["动画", "清单", "冬天", "不应出现"],
      clusteredAt: "2026-07-13T00:00:00Z",
    },
  ],
  state: "READY",
  lastAttemptAt: "2026-07-13T00:01:00Z",
  lastSuccessAt: "2026-07-13T00:01:00Z",
  windowDays: 7,
  reason: null,
};

function renderWindow(initialOverview: TopicOverview) {
  const Window = ChthollyTopicWindow as ComponentType<Record<string, unknown>>;
  return render(
    <Window
      topics={[]}
      initialOverview={initialOverview}
      signals={[
        { id: "1", name: "动画", slug: "anime", usageCount: 12 },
        { id: "2", name: "随笔", slug: "essay", usageCount: 9 },
      ]}
    />,
  );
}

describe("ChthollyTopicWindow", () => {
  afterEach(cleanup);

  beforeEach(() => {
    vi.mocked(topicService.overview).mockReset();
  });

  it("renders at most three ready notes with semantic time and three entities", () => {
    const { container } = renderWindow(readyOverview);

    expect(screen.getByText("冬季追番")).toBeInTheDocument();
    expect(screen.getByText("大家最近在整理追番计划。")).toBeInTheDocument();
    expect(screen.getByText("8 篇相关内容")).toBeInTheDocument();
    expect(screen.getByText("动画")).toBeInTheDocument();
    expect(screen.getByText("清单")).toBeInTheDocument();
    expect(screen.getByText("冬天")).toBeInTheDocument();
    expect(screen.queryByText("不应出现")).not.toBeInTheDocument();
    expect(container.querySelector("time")).toHaveAttribute(
      "datetime",
      "2026-07-13T00:00:00Z",
    );
    expect(screen.queryByRole("heading", { name: "窗边便笺" })).not.toBeInTheDocument();
  });

  it("shows sparse copy and labels tags only as recent signals", () => {
    renderWindow({ items: [], state: "SPARSE", windowDays: 7 });

    expect(screen.getByText("近几天还没有形成稳定的话题")).toBeInTheDocument();
    expect(screen.getByText("近期标签")).toBeInTheDocument();
    expect(screen.getByText("动画")).toBeInTheDocument();
    expect(screen.queryByText("她注意到的主题")).not.toBeInTheDocument();
    expect(screen.queryByText(/篇相关内容/)).not.toBeInTheDocument();
  });

  it("keeps pending distinct from an empty topic result", () => {
    renderWindow({ items: [], state: "PENDING", windowDays: 7 });

    expect(screen.getByText("正在整理近期内容")).toBeInTheDocument();
    expect(screen.queryByText("近几天还没有形成稳定的话题")).not.toBeInTheDocument();
  });

  it("defensively explains a ready response with no items", () => {
    renderWindow({ items: [], state: "READY", windowDays: 7 });

    expect(screen.getByText("这次整理没有留下可以展示的话题。稍后再来看看吧。")).toBeInTheDocument();
  });

  it("retries only the overview request and renders the returned state", async () => {
    const user = userEvent.setup();
    vi.mocked(topicService.overview).mockResolvedValue(readyOverview);
    renderWindow({
      items: [],
      state: "FAILED",
      windowDays: 7,
      reason: "REQUEST_FAILED",
    });

    expect(screen.getByText("话题整理暂时没有完成")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "重新查看" }));

    expect(await screen.findByText("冬季追番")).toBeInTheDocument();
    expect(topicService.overview).toHaveBeenCalledTimes(1);
  });

  it("disables the retry button while the overview request is pending", async () => {
    const user = userEvent.setup();
    let resolveOverview!: (overview: TopicOverview) => void;
    vi.mocked(topicService.overview).mockImplementation(
      () => new Promise((resolve) => {
        resolveOverview = resolve;
      }),
    );
    renderWindow({ items: [], state: "FAILED", windowDays: 7 });

    await user.click(screen.getByRole("button", { name: "重新查看" }));

    expect(screen.getByRole("button", { name: "重新查看中…" })).toBeDisabled();
    resolveOverview({ items: [], state: "PENDING", windowDays: 7 });
    expect(await screen.findByText("正在整理近期内容")).toBeInTheDocument();
  });

  it("stays in the failed state when retrying the overview rejects", async () => {
    const user = userEvent.setup();
    vi.mocked(topicService.overview).mockRejectedValue(new Error("offline"));
    renderWindow({ items: [], state: "FAILED", windowDays: 30 });

    await user.click(screen.getByRole("button", { name: "重新查看" }));

    await waitFor(() => {
      expect(screen.getByText("话题整理暂时没有完成")).toBeInTheDocument();
    });
    expect(screen.getByRole("button", { name: "重新查看" })).toBeEnabled();
    expect(topicService.overview).toHaveBeenCalledTimes(1);
  });
});
