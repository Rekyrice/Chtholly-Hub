import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import UserCommentActivityList from "@/components/site/UserCommentActivityList";
import type { UserCommentActivityItem } from "@/lib/types/comment";

const items: UserCommentActivityItem[] = [
  {
    id: "comment-1",
    postId: "post-1",
    postSlug: "first story/序章",
    postTitle: "第一篇文章",
    parentId: null,
    content: "这里的留白很好。",
    createdAt: "2026-08-06T12:00:00Z",
  },
  {
    id: "comment-2",
    postId: "post-2",
    postSlug: "second-story",
    postTitle: "第二篇文章",
    parentId: "comment-0",
    content: "我也这样觉得。",
    createdAt: "2026-08-07T01:30:00+08:00",
  },
];

describe("UserCommentActivityList", () => {
  afterEach(cleanup);

  it("renders top-level comments, replies, safe post links, content, and machine-readable dates", () => {
    render(<UserCommentActivityList items={items} />);

    expect(screen.getByText("这里的留白很好。")).toBeVisible();
    expect(screen.getByText("我也这样觉得。")).toBeVisible();
    expect(screen.getByRole("link", { name: "评论了《第一篇文章》" })).toHaveAttribute(
      "href",
      "/post/first%20story%2F%E5%BA%8F%E7%AB%A0",
    );
    expect(screen.getByRole("link", { name: "回复了《第二篇文章》" })).toHaveAttribute(
      "href",
      "/post/second-story",
    );

    for (const time of screen.getAllByRole("time")) {
      expect(Number.isNaN(Date.parse(time.getAttribute("datetime") ?? ""))).toBe(false);
    }
  });

  it("renders a quiet empty state", () => {
    render(<UserCommentActivityList items={[]} />);

    expect(screen.getByText("还没有留下公开回应")).toBeVisible();
  });

  it("renders an invalid timestamp as undisclosed without a time element", () => {
    render(
      <UserCommentActivityList
        items={[{ ...items[0], id: "invalid-date", createdAt: "not-a-date" }]}
      />,
    );

    expect(screen.getByText("时间未公开")).toBeVisible();
    expect(screen.queryByRole("time")).not.toBeInTheDocument();
  });

  it("shows the initial error and retries it", async () => {
    const onRetry = vi.fn();
    const user = userEvent.setup();
    render(
      <UserCommentActivityList
        items={[]}
        error="回应暂时没有加载出来"
        onRetry={onRetry}
      />,
    );

    expect(screen.getByText("回应暂时没有加载出来")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "重新加载" }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it("keeps existing items visible when loading more fails and allows retry", async () => {
    const onRetry = vi.fn();
    const user = userEvent.setup();
    render(
      <UserCommentActivityList
        items={items}
        error="更多回应暂时没有加载出来"
        onRetry={onRetry}
      />,
    );

    expect(screen.getByText("这里的留白很好。")).toBeVisible();
    expect(screen.getByText("我也这样觉得。")).toBeVisible();
    expect(screen.getByText("更多回应暂时没有加载出来")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "重新加载" }));
    expect(onRetry).toHaveBeenCalledOnce();
  });
});
