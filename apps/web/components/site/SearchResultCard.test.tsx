import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import SearchResultCard from "@/components/site/SearchResultCard";
import type { FeedItem } from "@/lib/types/post";

function post(overrides: Partial<FeedItem> = {}): FeedItem {
  return {
    id: "post-1",
    slug: "winter-notes",
    title: "冬日编辑札记",
    description: "记录冬日的编辑过程与札记。",
    coverImage: "/images/winter.jpg",
    tags: ["编辑", "冬日", "随笔", "第四标签"],
    authorNickname: "珂朵莉",
    publishTime: "2026-07-13T08:30:00Z",
    ...overrides,
  };
}

describe("SearchResultCard", () => {
  afterEach(cleanup);

  it("renders compact article metadata, a decorative cover and at most three tags", () => {
    const { container } = render(<SearchResultCard post={post()} query="冬日" />);

    expect(screen.getByRole("heading", { name: "冬日编辑札记" }).querySelector("a")).toHaveAttribute(
      "href",
      "/post/winter-notes",
    );
    expect(container.querySelector("img")).toHaveAttribute("alt", "");
    expect(container.querySelector("time")).toHaveAttribute(
      "dateTime",
      "2026-07-13T08:30:00Z",
    );
    expect(container.querySelector("time")).not.toBeEmptyDOMElement();
    expect(screen.getByText("珂朵莉")).toBeVisible();
    expect(container.querySelectorAll(".search-result-card__tag")).toHaveLength(3);
    expect(screen.queryByText("第四标签")).not.toBeInTheDocument();
  });

  it("highlights multiple query words with React mark nodes", () => {
    const { container } = render(
      <SearchResultCard post={post()} query="冬日 札记" />,
    );

    const marks = [...container.querySelectorAll("mark")].map((node) => node.textContent);
    expect(marks).toEqual(expect.arrayContaining(["冬日", "札记"]));
  });

  it("escapes hostile text and regex characters without creating injected DOM", () => {
    const title = '<img src=x onerror="alert(1)"> [札记]';
    const description = '<script>alert("xss")</script> 冬日';
    const { container } = render(
      <SearchResultCard
        post={post({ title, description, coverImage: undefined })}
        query="[札记] <script>"
      />,
    );

    expect(screen.getByText((content) => content.includes("<img src=x"))).toBeVisible();
    expect(screen.getByText((content) => content.includes("<script>"))).toBeVisible();
    expect(container.querySelector("script")).not.toBeInTheDocument();
    expect(container.querySelector('img[src="x"]')).not.toBeInTheDocument();
    expect(container.querySelectorAll("mark").length).toBeGreaterThan(0);
  });

  it("defends against empty runtime fields and uses the first tag as cover fallback", () => {
    const malformed = {
      id: "post-empty",
      slug: "",
      title: null,
      description: undefined,
      coverImage: "",
      tags: ["回退标签", "", null],
      authorNickname: null,
      publishTime: "not-a-date",
    } as unknown as FeedItem;

    const { container } = render(<SearchResultCard post={malformed} query="[" />);

    expect(screen.getByText("未命名文章")).toBeVisible();
    expect(screen.getByText("这篇文章暂时没有摘要。")).toBeVisible();
    expect(screen.getByText("匿名作者")).toBeVisible();
    expect(screen.getByText("回退标签", { selector: ".search-result-card__fallback" })).toBeVisible();
    expect(container.querySelector("time")).not.toBeInTheDocument();
    expect(container.querySelector("img")).not.toBeInTheDocument();
  });
});
