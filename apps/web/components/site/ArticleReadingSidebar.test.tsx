import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import ArticleReadingSidebar from "@/components/site/ArticleReadingSidebar";
import { buildReadingClues } from "@/app/(site)/post/[slug]/page";

vi.mock("@/components/site/ChthollyIllustration", () => ({
  ChthollyIllustration: () => <div data-testid="chtholly-reading-companion" />,
}));

afterEach(cleanup);

const commonProps = {
  readingMinutes: 6,
  charCount: 12345,
  publishTime: "2026-07-13T08:30:00Z",
  clues: ["先看天空岛的来历。", "再看两个人的重逢。"],
  authorId: "user-1",
  authorHandle: "rekyrice",
  authorNickname: "Rekyrice",
  tags: ["动画", "随笔"],
  relatedPosts: [
    {
      id: "related-1",
      title: "有链接的相关文章",
      summary: "继续读下去",
      href: "/post/related-1",
    },
    {
      id: "related-2",
      title: "只有摘要的文章",
      summary: "补全失败仍然展示",
    },
    {
      id: "related-3",
      title: "第三篇不应出现",
      href: "/post/related-3",
    },
  ],
  askHref: "/agent?context=post",
  readingComment: "慢慢看。",
  readingState: "calm" as const,
  timeOfDay: "night" as const,
};

describe("ArticleReadingSidebar", () => {
  it("renders four distinct reading blocks with progress metadata and article clues", () => {
    render(
      <ArticleReadingSidebar
        {...commonProps}
        headings={[
          { level: 2, text: "开始", id: "开始", sourceLine: 1 },
          { level: 3, text: "细节", id: "细节", sourceLine: 2 },
        ]}
      />,
    );

    expect(screen.getByRole("complementary", { name: "文章阅读辅助" })).toBeInTheDocument();
    expect(screen.getByText("阅读轨迹")).toBeInTheDocument();
    expect(screen.getByRole("progressbar", { name: "正文阅读进度" })).toBeInTheDocument();
    expect(screen.getByText("约 6 分钟")).toBeInTheDocument();
    expect(screen.getByText("12,345 字")).toBeInTheDocument();
    expect(screen.getByText("2026年07月13日")).toBeInTheDocument();

    expect(screen.getByRole("navigation", { name: "本文目录" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "开始" })).toHaveAttribute("href", "#开始");
    expect(screen.getByRole("link", { name: "细节" })).toHaveAttribute("href", "#细节");

    expect(screen.getByText("文章线索")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Rekyrice" })).toHaveAttribute("href", "/user/rekyrice");
    expect(screen.getByRole("link", { name: "动画" })).toHaveAttribute("href", "/tag/%E5%8A%A8%E7%94%BB");
    expect(screen.getByRole("link", { name: /有链接的相关文章/ })).toHaveAttribute("href", "/post/related-1");
    expect(screen.getByText("只有摘要的文章")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /只有摘要的文章/ })).not.toBeInTheDocument();
    expect(screen.queryByText("第三篇不应出现")).not.toBeInTheDocument();

    expect(screen.getByText("珂朵莉陪读")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "问珂朵莉" })).toHaveAttribute("href", "/agent?context=post");
    expect(screen.getByTestId("chtholly-reading-companion")).toBeInTheDocument();
  });

  it("renders reading clues for a short article without headings", () => {
    render(<ArticleReadingSidebar {...commonProps} headings={[]} />);

    expect(screen.queryByRole("navigation", { name: "本文目录" })).not.toBeInTheDocument();
    expect(screen.getByText("阅读线索")).toBeInTheDocument();
    expect(screen.getByText("先看天空岛的来历。")).toBeInTheDocument();
    expect(screen.getByText("再看两个人的重逢。")).toBeInTheDocument();
  });

  it("keeps the compact sidebar to minutes, author, and tags without repeated blocks", () => {
    render(
      <ArticleReadingSidebar
        {...commonProps}
        headings={[{ level: 2, text: "开始", id: "开始", sourceLine: 1 }]}
        compact
      />,
    );

    expect(screen.getByText("约 6 分钟")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Rekyrice" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "动画" })).toBeInTheDocument();
    expect(screen.queryByText("阅读轨迹")).not.toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "本文目录" })).not.toBeInTheDocument();
    expect(screen.queryByText("阅读线索")).not.toBeInTheDocument();
    expect(screen.queryByText("有链接的相关文章")).not.toBeInTheDocument();
    expect(screen.queryByText("珂朵莉陪读")).not.toBeInTheDocument();
    expect(screen.queryByText("12,345 字")).not.toBeInTheDocument();
    expect(screen.queryByText("2026年07月13日")).not.toBeInTheDocument();
    expect(screen.queryByTestId("chtholly-reading-companion")).not.toBeInTheDocument();
  });

  it("renders an unlinked author label when no canonical handle is available", () => {
    render(
      <ArticleReadingSidebar
        {...commonProps}
        headings={[]}
        authorHandle={undefined}
        authorNickname="Temporary author"
        compact
      />,
    );

    expect(screen.getByText("Temporary author")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Temporary author" })).not.toBeInTheDocument();
  });
});

describe("buildReadingClues", () => {
  it("builds at most three short plain-text clues from description and markdown sentences", () => {
    const clues = buildReadingClues(
      "**群岛**上的相遇。",
      [
        "## 开始",
        "第一句写着 [重逢](/post/reunion)。第二句有 `约定`！",
        "![天空](/sky.webp)",
        "- 第三句也很重要。第四句不应进入侧栏。",
      ].join("\n"),
    );

    expect(clues).toEqual([
      "群岛上的相遇。",
      "第一句写着重逢。",
      "第二句有约定！",
    ]);
    expect(clues.every((clue) => clue.length <= 72)).toBe(true);
  });
});
