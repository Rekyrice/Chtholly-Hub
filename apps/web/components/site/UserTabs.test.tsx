import { Profiler, type ReactNode } from "react";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type {
  UserCommentActivityItem,
  UserCommentActivityPage,
} from "@/lib/types/comment";
import type { FeedItem } from "@/lib/types/post";

const mocks = vi.hoisted(() => ({
  getStoredAuth: vi.fn(),
  mine: vi.fn(),
  listByUser: vi.fn(),
}));

vi.mock("@/lib/auth/tokens", () => ({ getStoredAuth: mocks.getStoredAuth }));
vi.mock("@/lib/services/postService", () => ({
  postService: { mine: mocks.mine },
}));
vi.mock("@/lib/services/commentService", () => ({
  commentService: { listByUser: mocks.listByUser },
}));
vi.mock("@/components/site/PostCard", () => ({
  default: ({ post }: { post: FeedItem }) => <article>{post.title}</article>,
}));
vi.mock("@/components/site/PostOwnerActions", () => ({
  default: () => null,
}));
vi.mock("next/link", () => ({
  default: ({ children, href }: { children: ReactNode; href: string }) => (
    <a href={href}>{children}</a>
  ),
}));

import UserTabs from "@/components/site/UserTabs";

const activity = (
  id: string,
  content: string,
  overrides: Partial<UserCommentActivityItem> = {},
): UserCommentActivityItem => ({
  id,
  postId: `post-${id}`,
  postSlug: `story-${id}`,
  postTitle: `文章 ${id}`,
  parentId: null,
  content,
  createdAt: "2026-08-06T12:00:00Z",
  ...overrides,
});

const commentPage = (
  items: UserCommentActivityItem[],
  overrides: Partial<UserCommentActivityPage> = {},
): UserCommentActivityPage => ({
  items,
  total: items.length,
  page: 1,
  size: 20,
  hasMore: false,
  ...overrides,
});

const comments = [
  activity("1", "第一条回应"),
  activity("2", "第二条回应"),
  activity("3", "第三条回应"),
];

const defaultProps = {
  posts: [] as FeedItem[],
  displayName: "Alice",
  userId: "7",
  userHandle: "alice",
  initialComments: commentPage(comments),
  commentsInitialLoadFailed: false,
};

describe("UserTabs comment activity", () => {
  beforeEach(() => {
    Object.values(mocks).forEach((mock) => mock.mockReset());
    mocks.getStoredAuth.mockReturnValue(null);
    mocks.mine.mockResolvedValue({ items: [], page: 1, size: 50, hasMore: false });
  });

  afterEach(cleanup);

  it("shows only the two most recent comments in overview and all loaded comments in the comments tab", async () => {
    const user = userEvent.setup();
    render(<UserTabs {...defaultProps} />);

    expect(screen.getByText("第一条回应")).toBeVisible();
    expect(screen.getByText("第二条回应")).toBeVisible();
    expect(screen.queryByText("第三条回应")).not.toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "评论" }));

    expect(screen.getByText("第一条回应")).toBeVisible();
    expect(screen.getByText("第二条回应")).toBeVisible();
    expect(screen.getByText("第三条回应")).toBeVisible();
    expect(screen.queryByText(/接口还没接上/)).not.toBeInTheDocument();
  });

  it("keeps only the first occurrence when an initial page contains duplicate IDs", () => {
    const first = activity("duplicate", "首条重复回应");
    render(
      <UserTabs
        {...defaultProps}
        initialComments={commentPage([
          first,
          { ...first, content: "不应出现的重复回应" },
        ])}
      />,
    );

    expect(screen.getByText("首条重复回应")).toBeVisible();
    expect(screen.queryByText("不应出现的重复回应")).not.toBeInTheDocument();
  });

  it("shows an initial-load retry in overview and comments, then replaces state after a successful retry", async () => {
    const user = userEvent.setup();
    const recovered = activity("recovered", "恢复后的回应");
    mocks.listByUser.mockResolvedValue(
      commentPage([recovered, { ...recovered, content: "不应保留的重试重复项" }]),
    );
    render(
      <UserTabs
        {...defaultProps}
        initialComments={commentPage([])}
        commentsInitialLoadFailed
      />,
    );

    expect(screen.getByText("回应暂时没有加载出来")).toBeVisible();
    expect(screen.getByRole("button", { name: "重新加载" })).toBeEnabled();

    await user.click(screen.getByRole("tab", { name: "评论" }));
    expect(screen.getByText("回应暂时没有加载出来")).toBeVisible();

    await user.click(screen.getByRole("button", { name: "重新加载" }));

    await waitFor(() => {
      expect(mocks.listByUser).toHaveBeenCalledWith("7", 1, 20);
      expect(screen.queryByText("回应暂时没有加载出来")).not.toBeInTheDocument();
    });
    expect(screen.getByText("恢复后的回应")).toBeVisible();
    expect(screen.queryByText("不应保留的重试重复项")).not.toBeInTheDocument();
  });

  it("deduplicates a loaded page, advances its page, and blocks duplicate loading requests", async () => {
    const user = userEvent.setup();
    const secondPage = deferred<UserCommentActivityPage>();
    mocks.listByUser
      .mockImplementationOnce(() => secondPage.promise)
      .mockResolvedValueOnce(commentPage([], { page: 3, hasMore: false }));
    render(
      <UserTabs
        {...defaultProps}
        initialComments={commentPage([comments[0]], { page: 1, hasMore: true, total: 3 })}
      />,
    );
    await user.click(screen.getByRole("tab", { name: "评论" }));

    const loadMore = screen.getByRole("button", { name: "加载更多" });
    await user.click(loadMore);
    expect(loadMore).toBeDisabled();
    await user.click(loadMore);
    expect(mocks.listByUser).toHaveBeenCalledTimes(1);
    expect(mocks.listByUser).toHaveBeenCalledWith("7", 2, 20);

    await act(async () => {
      const added = activity("2", "新追加的回应");
      secondPage.resolve(
        commentPage(
          [comments[0], added, { ...added, content: "不应追加的页内重复项" }],
          { page: 2, hasMore: true, total: 3 },
        ),
      );
      await secondPage.promise;
    });

    expect(screen.getAllByText("第一条回应")).toHaveLength(1);
    expect(screen.getByText("新追加的回应")).toBeVisible();
    expect(screen.queryByText("不应追加的页内重复项")).not.toBeInTheDocument();
    await user.click(await screen.findByRole("button", { name: "加载更多" }));
    expect(mocks.listByUser).toHaveBeenNthCalledWith(2, "7", 3, 20);
  });

  it("keeps existing comments after a load-more failure and retries the same page", async () => {
    const user = userEvent.setup();
    mocks.listByUser
      .mockRejectedValueOnce(new Error("offline"))
      .mockResolvedValueOnce(
        commentPage([activity("2", "恢复后追加的回应")], { page: 2, hasMore: false, total: 2 }),
      );
    render(
      <UserTabs
        {...defaultProps}
        initialComments={commentPage([comments[0]], { page: 1, hasMore: true, total: 2 })}
      />,
    );
    await user.click(screen.getByRole("tab", { name: "评论" }));
    await user.click(screen.getByRole("button", { name: "加载更多" }));

    expect(await screen.findByText("更多回应暂时没有加载出来")).toBeVisible();
    expect(screen.getByText("第一条回应")).toBeVisible();
    expect(mocks.listByUser).toHaveBeenNthCalledWith(1, "7", 2, 20);

    await user.click(screen.getByRole("button", { name: "重新加载" }));

    expect(await screen.findByText("恢复后追加的回应")).toBeVisible();
    expect(mocks.listByUser).toHaveBeenNthCalledWith(2, "7", 2, 20);
    expect(screen.queryByText("更多回应暂时没有加载出来")).not.toBeInTheDocument();
  });

  it("resets comments for a new user and ignores a stale response from the previous user", async () => {
    const user = userEvent.setup();
    const stalePage = deferred<UserCommentActivityPage>();
    const commitSnapshots: string[] = [];
    let profileContainer: HTMLElement | null = null;
    mocks.listByUser.mockImplementationOnce(() => stalePage.promise);
    const { container, rerender } = render(
      <Profiler
        id="profile-comments"
        onRender={() => commitSnapshots.push(profileContainer?.textContent ?? "")}
      >
        <UserTabs
          {...defaultProps}
          initialComments={commentPage([activity("old", "旧用户回应")], { hasMore: true })}
        />
      </Profiler>,
    );
    profileContainer = container;
    await user.click(screen.getByRole("tab", { name: "评论" }));
    await user.click(screen.getByRole("button", { name: "加载更多" }));
    expect(mocks.listByUser).toHaveBeenCalledWith("7", 2, 20);

    const nextComment = activity("new", "新用户回应");
    commitSnapshots.length = 0;
    rerender(
      <Profiler
        id="profile-comments"
        onRender={() => commitSnapshots.push(profileContainer?.textContent ?? "")}
      >
        <UserTabs
          {...defaultProps}
          userId="8"
          userHandle="bob"
          displayName="Bob"
          initialComments={commentPage([
            nextComment,
            { ...nextComment, content: "不应出现的新用户重复项" },
          ])}
        />
      </Profiler>,
    );
    expect(commitSnapshots[0]).toContain("新用户回应");
    expect(commitSnapshots[0]).not.toContain("旧用户回应");
    expect(screen.getByText("新用户回应")).toBeVisible();
    expect(screen.queryByText("旧用户回应")).not.toBeInTheDocument();
    expect(screen.queryByText("不应出现的新用户重复项")).not.toBeInTheDocument();

    await act(async () => {
      stalePage.resolve(
        commentPage([activity("stale", "迟到的旧请求")], { page: 2, hasMore: false }),
      );
      await stalePage.promise;
    });

    expect(screen.getByText("新用户回应")).toBeVisible();
    expect(screen.queryByText("迟到的旧请求")).not.toBeInTheDocument();
  });
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}
