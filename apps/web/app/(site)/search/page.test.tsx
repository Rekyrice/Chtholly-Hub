import { readFileSync } from "node:fs";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import SearchPage from "@/app/(site)/search/page";
import type { FeedItem } from "@/lib/types/post";

const serviceMocks = vi.hoisted(() => ({
  search: vi.fn(),
  tags: vi.fn(),
  feed: vi.fn(),
}));
const autocompleteProps = vi.hoisted(() => vi.fn());
const resultsProps = vi.hoisted(() => vi.fn());

vi.mock("@/lib/services/searchService", () => ({
  searchService: { search: serviceMocks.search },
}));

vi.mock("@/lib/services/tagService", () => ({
  tagService: { list: serviceMocks.tags },
}));

vi.mock("@/lib/services/postService", () => ({
  postService: { feed: serviceMocks.feed },
}));

vi.mock("@/components/site/SearchAutocompleteForm", () => ({
  default: (props: { initialQuery: string; tags: string[]; sort: string }) => {
    autocompleteProps(props);
    return <div data-testid="autocomplete">{props.initialQuery}</div>;
  },
}));

vi.mock("@/components/site/SearchResults", () => ({
  default: (props: { initial: { items: FeedItem[] } }) => {
    resultsProps(props);
    return (
      <div data-testid="search-results">
        {props.initial.items.map((item) => item.title).join(",")}
      </div>
    );
  },
}));

function item(id: string, title = `文章 ${id}`): FeedItem {
  return {
    id,
    slug: `post-${id}`,
    title,
    description: `${title} 摘要`,
    tags: ["随笔"],
    authorNickname: "作者",
    publishTime: "2026-07-13T08:30:00Z",
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((promiseResolve) => {
    resolve = promiseResolve;
  });
  return { promise, resolve };
}

describe("SearchPage", () => {
  beforeEach(() => {
    Object.values(serviceMocks).forEach((mock) => mock.mockReset());
    autocompleteProps.mockClear();
    resultsProps.mockClear();
    serviceMocks.tags.mockResolvedValue([
      { id: "tag-1", name: "随笔", slug: "essay", usageCount: 12 },
      { id: "tag-2", name: "世界观", slug: "world", usageCount: 8 },
      { id: "tag-3", name: "动画", slug: "anime", usageCount: 6 },
    ]);
    serviceMocks.search.mockResolvedValue({
      items: [item("1", "冬日札记")],
      nextAfter: "cursor-1",
      hasMore: true,
      degraded: false,
    });
    serviceMocks.feed.mockResolvedValue({
      items: [item("1"), item("2"), item("3"), item("4")],
      page: 1,
      size: 4,
      hasMore: false,
    });
  });

  afterEach(cleanup);

  it("restores normalized URL state and provides removable, addable and sortable filters", async () => {
    render(
      await SearchPage({
        searchParams: Promise.resolve({
          q: "  冬日  ",
          tags: "随笔,, 世界观,随笔",
          sort: "newest",
        }),
      }),
    );

    expect(serviceMocks.search).toHaveBeenCalledWith({
      q: "冬日",
      size: 12,
      tags: ["随笔", "世界观"],
      sort: "newest",
    });
    expect(autocompleteProps).toHaveBeenCalledWith({
      initialQuery: "冬日",
      tags: ["随笔", "世界观"],
      sort: "newest",
    });
    expect(resultsProps).toHaveBeenCalledWith(
      expect.objectContaining({
        query: "冬日",
        tags: ["随笔", "世界观"],
        sort: "newest",
        initial: expect.objectContaining({ nextAfter: "cursor-1" }),
      }),
    );
    expect(screen.getByRole("link", { name: "最新" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    expect(screen.getByRole("link", { name: "综合" })).toHaveAttribute(
      "href",
      "/search?q=%E5%86%AC%E6%97%A5&tags=%E9%9A%8F%E7%AC%94%2C%E4%B8%96%E7%95%8C%E8%A7%82",
    );
    expect(screen.getByRole("link", { name: "移除标签 随笔" })).toHaveAttribute(
      "href",
      "/search?q=%E5%86%AC%E6%97%A5&tags=%E4%B8%96%E7%95%8C%E8%A7%82&sort=newest",
    );
    expect(screen.getByRole("link", { name: "添加标签 动画" })).toHaveAttribute(
      "href",
      "/search?q=%E5%86%AC%E6%97%A5&tags=%E9%9A%8F%E7%AC%94%2C%E4%B8%96%E7%95%8C%E8%A7%82%2C%E5%8A%A8%E7%94%BB&sort=newest",
    );
  });

  it("normalizes an invalid sort and never emits an empty tags parameter", async () => {
    render(
      await SearchPage({
        searchParams: Promise.resolve({ q: "winter", tags: ",,", sort: "unknown" }),
      }),
    );

    expect(serviceMocks.search).toHaveBeenCalledWith({
      q: "winter",
      size: 12,
      tags: [],
      sort: "relevance",
    });
    expect(screen.getByRole("link", { name: "综合" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    expect(screen.getByRole("link", { name: "综合" })).toHaveAttribute(
      "href",
      "/search?q=winter",
    );
  });

  it("starts keyword search and tag catalog requests in the same batch", async () => {
    const search = deferred<{
      items: FeedItem[];
      nextAfter: null;
      hasMore: false;
      degraded: false;
    }>();
    const tags = deferred<never[]>();
    serviceMocks.search.mockReturnValue(search.promise);
    serviceMocks.tags.mockReturnValue(tags.promise);

    const pagePromise = SearchPage({ searchParams: Promise.resolve({ q: "并发" }) });
    await Promise.resolve();

    expect(serviceMocks.search).toHaveBeenCalledTimes(1);
    expect(serviceMocks.tags).toHaveBeenCalledWith(12);
    expect(serviceMocks.feed).not.toHaveBeenCalled();
    search.resolve({ items: [], nextAfter: null, hasMore: false, degraded: false });
    tags.resolve([]);
    await pagePromise;
  });

  it("starts recent posts and tag catalog requests together without a keyword", async () => {
    const feed = deferred<{ items: FeedItem[] }>();
    const tags = deferred<never[]>();
    serviceMocks.feed.mockReturnValue(feed.promise);
    serviceMocks.tags.mockReturnValue(tags.promise);

    const pagePromise = SearchPage({ searchParams: Promise.resolve({}) });
    await Promise.resolve();

    expect(serviceMocks.tags).toHaveBeenCalledWith(12);
    expect(serviceMocks.feed).toHaveBeenCalledWith(1, 4);
    expect(serviceMocks.search).not.toHaveBeenCalled();
    feed.resolve({ items: [] });
    tags.resolve([]);
    await pagePromise;
  });

  it("shows a true empty result only when the search service is healthy", async () => {
    serviceMocks.search.mockResolvedValue({
      items: [],
      nextAfter: null,
      hasMore: false,
      degraded: false,
    });

    render(await SearchPage({ searchParams: Promise.resolve({ q: "没有命中" }) }));

    expect(screen.getByText("没有找到匹配的文章")).toBeVisible();
    expect(screen.getByText(/尝试缩短关键词/)).toBeVisible();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("shows a degraded warning without misreporting no results", async () => {
    serviceMocks.search.mockRejectedValue(new Error("offline"));
    serviceMocks.tags.mockRejectedValue(new Error("tags offline"));

    render(await SearchPage({ searchParams: Promise.resolve({ q: "服务失败" }) }));

    expect(screen.getByRole("alert")).toHaveTextContent("搜索服务暂时不可用");
    expect(screen.queryByText("没有找到匹配的文章")).not.toBeInTheDocument();
  });

  it("renders browsing entrances, hot tags and four recent compact links without a keyword", async () => {
    render(await SearchPage({ searchParams: Promise.resolve({}) }));

    expect(screen.getByRole("link", { name: "浏览归档" })).toHaveAttribute("href", "/archive");
    expect(screen.getByRole("link", { name: "前往 Hub" })).toHaveAttribute("href", "/hub");
    expect(screen.getByRole("link", { name: "动画6 篇" })).toHaveAttribute("href", "/tag/anime");
    expect(screen.getAllByTestId("search-recent-item")).toHaveLength(4);
  });

  it("reports the actual recent post count when fewer than four are available", async () => {
    serviceMocks.feed.mockResolvedValue({
      items: [item("1"), item("2")],
      page: 1,
      size: 4,
      hasMore: false,
    });

    render(await SearchPage({ searchParams: Promise.resolve({}) }));

    expect(screen.getByText("RECENT 02")).toBeVisible();
    expect(screen.getAllByTestId("search-recent-item")).toHaveLength(2);
  });

  it("keeps search CSS route-private with compact card geometry", () => {
    const css = readFileSync("app/styles/search.css", "utf8");
    const article = readFileSync("app/styles/article.css", "utf8");

    expect(article).not.toMatch(/^\s*\.search-/m);
    expect(css).toContain(".search-page-layout");
    expect(css).toContain("grid-template-columns: minmax(0, 1fr) 300px");
    expect(css).toContain("grid-template-columns: 240px minmax(0, 1fr)");
    expect(css).toContain("min-height: 204px");
    expect(css).toContain("border-radius: 14px");
    expect(css).toContain("padding: 24px 28px");
    expect(css).toContain("color-mix(in srgb, var(--color-sky) 24%, transparent)");
    expect(css).toContain("@media (max-width: 640px)");
    expect(css).toContain("overflow-wrap: anywhere");
    expect(css).toContain(":focus-visible");
  });
});
