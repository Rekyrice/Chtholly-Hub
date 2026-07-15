import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import PostCard from "@/components/site/PostCard";
import type { FeedItem } from "@/lib/types/post";

describe("PostCard", () => {
  it("renders highlighted descriptions as text instead of HTML", () => {
    const description = "<img src=x onerror=alert(1)><em>命中</em>";
    const post: FeedItem = {
      id: "post-1",
      slug: "safe-search-snippet",
      title: "安全搜索摘要",
      description,
      tags: [],
      authorNickname: "测试作者",
    };

    const { container } = render(
      <PostCard post={post} highlightDescription />,
    );

    expect(screen.getByText(description)).toBeInTheDocument();
    expect(container.querySelector("img[src=x]")).not.toBeInTheDocument();
    expect(container.querySelector("em")).not.toBeInTheDocument();
  });

  it("shows the publication date in the article metadata", () => {
    const post: FeedItem = {
      id: "post-2",
      slug: "dated-post",
      title: "Dated post",
      description: "Summary",
      tags: [],
      authorNickname: "Author",
      publishTime: "2026-07-13T08:30:00Z",
    };

    const { container } = render(<PostCard post={post} />);

    expect(container.querySelector("time")).toHaveAttribute(
      "dateTime",
      post.publishTime,
    );
    expect(container.querySelector("time")).not.toBeEmptyDOMElement();
  });
});
