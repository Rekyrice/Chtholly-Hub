import { describe, expect, it } from "vitest";
import { extractMarkdownHeadings } from "@/lib/markdownHeadings";

describe("extractMarkdownHeadings", () => {
  it("extracts H2 and H3 headings with stable unique ids", () => {
    expect(extractMarkdownHeadings("## 开始\n### 细节\n## 开始")).toEqual([
      { level: 2, text: "开始", id: "开始", sourceLine: 1 },
      { level: 3, text: "细节", id: "细节", sourceLine: 2 },
      { level: 2, text: "开始", id: "开始-2", sourceLine: 3 },
    ]);
  });

  it("ignores headings inside fenced code blocks and strips inline markup", () => {
    const markdown = [
      "```md",
      "## 不是目录",
      "```",
      "## **真正的** [标题](https://example.com) ###",
      "#### 不收录四级标题",
    ].join("\n");

    expect(extractMarkdownHeadings(markdown)).toEqual([
      { level: 2, text: "真正的 标题", id: "真正的-标题", sourceLine: 4 },
    ]);
  });
});
