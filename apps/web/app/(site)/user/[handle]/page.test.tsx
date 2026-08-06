import type { ReactNode } from "react";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { UserCommentActivityPage } from "@/lib/types/comment";
import type { FeedItem } from "@/lib/types/post";
import type { UserCounter } from "@/lib/types/relation";

const serviceMocks = vi.hoisted(() => ({
  getByHandle: vi.fn(),
  feed: vi.fn(),
  counter: vi.fn(),
  listByUser: vi.fn(),
  notFound: vi.fn(),
}));

const componentProps = vi.hoisted(() => ({
  userTabs: vi.fn(),
  relationPanel: vi.fn(),
}));

vi.mock("@/lib/services/userService", () => ({
  userService: { getByHandle: serviceMocks.getByHandle },
}));

vi.mock("@/lib/services/postService", () => ({
  postService: { feed: serviceMocks.feed },
}));

vi.mock("@/lib/services/relationService", () => ({
  relationService: { counter: serviceMocks.counter },
}));

vi.mock("@/lib/services/commentService", () => ({
  commentService: { listByUser: serviceMocks.listByUser },
}));

vi.mock("next/navigation", () => ({ notFound: serviceMocks.notFound }));
vi.mock("next/image", () => ({ default: () => null }));
vi.mock("next/link", () => ({
  default: ({ children, href }: { children: ReactNode; href: string }) => (
    <a href={href}>{children}</a>
  ),
}));

vi.mock("@/components/site/Sidebar", () => ({
  default: () => <aside />,
}));

vi.mock("@/components/site/UserRelationPanel", () => ({
  default: (props: { userId: string | number; initialCounter?: UserCounter }) => {
    componentProps.relationPanel(props);
    return <div />;
  },
}));

vi.mock("@/components/site/ChthollyImpression", () => ({
  default: () => <div />,
}));

type UserTabsProps = {
  posts: FeedItem[];
  displayName: string;
  userId: string | number;
  userHandle?: string | null;
  initialComments: UserCommentActivityPage;
  commentsInitialLoadFailed: boolean;
};

vi.mock("@/components/site/UserTabs", () => ({
  default: (props: UserTabsProps) => {
    componentProps.userTabs(props);
    return <div data-testid="user-tabs" />;
  },
}));

import UserPage from "@/app/(site)/user/[handle]/page";

const user = {
  id: "9007199254740993",
  handle: "alice",
  nickname: "Alice",
  avatar: null,
  bio: "写作者",
  tags: [],
  createdAt: "2026-08-01T00:00:00Z",
  publicPostCount: 1,
};

const post: FeedItem = {
  id: "post-1",
  slug: "first-post",
  title: "第一篇文章",
  description: "摘要",
  tags: [],
  authorNickname: "Alice",
};

const counter: UserCounter = {
  followings: 2,
  followers: 3,
  posts: 1,
  likedPosts: 4,
  favedPosts: 5,
};

const comments: UserCommentActivityPage = {
  items: [
    {
      id: "comment-1",
      postId: "post-1",
      postSlug: "first-post",
      postTitle: "第一篇文章",
      parentId: null,
      content: "很喜欢这篇。",
      createdAt: "2026-08-06T12:00:00Z",
    },
  ],
  total: 1,
  page: 1,
  size: 20,
  hasMore: false,
};

describe("UserPage", () => {
  beforeEach(() => {
    Object.values(serviceMocks).forEach((mock) => mock.mockReset());
    Object.values(componentProps).forEach((mock) => mock.mockClear());
    serviceMocks.notFound.mockImplementation(() => {
      throw new Error("not found");
    });
    serviceMocks.getByHandle.mockResolvedValue(user);
    serviceMocks.feed.mockResolvedValue({
      items: [post],
      page: 1,
      size: 50,
      hasMore: false,
    });
    serviceMocks.counter.mockResolvedValue(counter);
    serviceMocks.listByUser.mockResolvedValue(comments);
  });

  afterEach(cleanup);

  it("loads the first comment activity page after resolving the user", async () => {
    render(await UserPage({ params: Promise.resolve({ handle: "Alice" }) }));

    expect(serviceMocks.getByHandle).toHaveBeenCalledWith("alice");
    expect(serviceMocks.listByUser).toHaveBeenCalledWith(String(user.id), 1, 20);
    expect(componentProps.userTabs).toHaveBeenCalledWith(
      expect.objectContaining({
        initialComments: comments,
        commentsInitialLoadFailed: false,
      }),
    );
    expect(screen.getByRole("heading", { name: "Alice" })).toBeVisible();
  });

  it("keeps the page and existing fallbacks when comment activity fails", async () => {
    serviceMocks.listByUser.mockRejectedValue(new Error("comments offline"));
    serviceMocks.feed.mockRejectedValue(new Error("posts offline"));
    serviceMocks.counter.mockRejectedValue(new Error("counter offline"));

    render(await UserPage({ params: Promise.resolve({ handle: "alice" }) }));

    expect(screen.getByRole("heading", { name: "Alice" })).toBeVisible();
    expect(componentProps.userTabs).toHaveBeenCalledWith(
      expect.objectContaining({
        posts: [],
        initialComments: {
          items: [],
          total: 0,
          page: 1,
          size: 20,
          hasMore: false,
        },
        commentsInitialLoadFailed: true,
      }),
    );
    expect(componentProps.relationPanel).toHaveBeenCalledWith({
      userId: user.id,
      initialCounter: {
        followings: 0,
        followers: 0,
        posts: user.publicPostCount,
        likedPosts: 0,
        favedPosts: 0,
      },
    });
  });
});
