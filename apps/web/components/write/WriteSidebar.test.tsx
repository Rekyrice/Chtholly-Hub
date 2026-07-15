import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import WriteSidebar from "@/components/write/WriteSidebar";

describe("WriteSidebar", () => {
  it("summarizes readiness, save state, and writing statistics", () => {
    render(
      <WriteSidebar
        title="一篇新文章"
        tags={["动画", "随笔"]}
        description="文章摘要"
        markdown={"第一段内容。\n\n第二段内容。"}
        saveStatus="unsaved"
      />,
    );

    expect(screen.getByRole("complementary", { name: "写作辅助" })).toBeInTheDocument();
    expect(screen.getByText("发布检查")).toBeInTheDocument();
    expect(screen.getByText("草稿状态：有未保存的更改")).toBeInTheDocument();
    expect(screen.getByText("2 段")).toBeInTheDocument();
    expect(screen.getByText("Markdown 快捷写法")).toBeInTheDocument();
    expect(screen.getAllByTestId("write-check-ready")).toHaveLength(4);
  });

  it("keeps incomplete fields visibly pending", () => {
    render(
      <WriteSidebar title="" tags={[]} description="" markdown="" saveStatus="saved" />,
    );

    expect(screen.getAllByTestId("write-check-pending")).toHaveLength(4);
    expect(screen.getByText("草稿状态：已保存")).toBeInTheDocument();
    expect(screen.getByText("0 字")).toBeInTheDocument();
  });
});
