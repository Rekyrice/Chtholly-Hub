import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import ArticleReadingSidebar from "@/components/site/ArticleReadingSidebar";

vi.mock("@/components/site/ChthollyIllustration", () => ({
  ChthollyIllustration: () => <div data-testid="chtholly-reading-companion" />,
}));

afterEach(cleanup);

describe("ArticleReadingSidebar", () => {
  it("renders the table of contents and practical reading links", () => {
    render(
      <ArticleReadingSidebar
        headings={[
          { level: 2, text: "开始", id: "开始", sourceLine: 1 },
          { level: 3, text: "细节", id: "细节", sourceLine: 2 },
        ]}
        readingMinutes={6}
        authorId="user-1"
        authorHandle="rekyrice"
        authorNickname="Rekyrice"
        tags={["动画", "随笔"]}
        askHref="/agent?context=post"
        readingComment="慢慢看。"
        readingState="calm"
        timeOfDay="night"
      />,
    );

    expect(screen.getByRole("complementary", { name: "文章阅读辅助" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "开始" })).toHaveAttribute("href", "#开始");
    expect(screen.getByRole("link", { name: "细节" })).toHaveAttribute("href", "#细节");
    expect(screen.getByText("约 6 分钟阅读")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Rekyrice" })).toHaveAttribute("href", "/user/rekyrice");
    expect(screen.getByRole("link", { name: "动画" })).toHaveAttribute("href", "/tag/%E5%8A%A8%E7%94%BB");
    expect(screen.getByRole("link", { name: "问珂朵莉" })).toHaveAttribute("href", "/agent?context=post");
    expect(screen.getByTestId("chtholly-reading-companion")).toBeInTheDocument();
  });

  it("renders an unlinked author label when no canonical handle is available", () => {
    render(
      <ArticleReadingSidebar
        headings={[]}
        readingMinutes={1}
        authorId="user-1"
        authorNickname="Temporary author"
        tags={[]}
        askHref="/agent"
        readingComment="test"
        readingState="calm"
        timeOfDay="day"
        compact
      />,
    );

    expect(screen.getByText("Temporary author")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Temporary author" })).not.toBeInTheDocument();
  });
});
