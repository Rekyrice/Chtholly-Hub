import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import MarkdownContent from "@/components/site/MarkdownContent";

afterEach(cleanup);

describe("MarkdownContent image captions", () => {
  it("marks a pure emphasis paragraph immediately following a pure image paragraph", () => {
    render(<MarkdownContent content={"![天空](/sky.webp)\n\n*风从浮岛之间穿过。*"} />);

    expect(screen.getByText("风从浮岛之间穿过。").closest("p"))
      .toHaveClass("article-image-caption");
    expect(screen.getByText("风从浮岛之间穿过。").closest("p"))
      .toHaveAttribute("data-article-caption", "true");
  });

  it("does not mark a caption when the image paragraph also contains text", () => {
    render(<MarkdownContent content={"![天空](/sky.webp) 图片后的正文\n\n*普通斜体。*"} />);

    expect(screen.getByText("普通斜体。").closest("p"))
      .not.toHaveClass("article-image-caption");
  });

  it("does not mark a caption when the emphasis paragraph has trailing text", () => {
    render(<MarkdownContent content={"![天空](/sky.webp)\n\n*像说明的文字* 仍有正文"} />);

    expect(screen.getByText(/像说明的文字/).closest("p"))
      .not.toHaveClass("article-image-caption");
  });

  it("does not mark an ordinary italic paragraph without a preceding image", () => {
    render(<MarkdownContent content={"前一段正文。\n\n*普通斜体。*"} />);

    expect(screen.getByText("普通斜体。").closest("p"))
      .not.toHaveClass("article-image-caption");
  });
});
