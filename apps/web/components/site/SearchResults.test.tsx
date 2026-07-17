import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import SearchResults from "@/components/site/SearchResults";
import { searchService } from "@/lib/services/searchService";
import type { FeedItem } from "@/lib/types/post";
import type { SearchResponse } from "@/lib/types/search";

vi.mock("@/lib/services/searchService", () => ({
  searchService: {
    search: vi.fn(),
  },
}));

function item(id: string, title = `文章 ${id}`): FeedItem {
  return {
    id,
    slug: `post-${id}`,
    title,
    description: `${title} 的摘要`,
    tags: ["测试"],
    authorNickname: "作者",
  };
}

function response(overrides: Partial<SearchResponse> = {}): SearchResponse {
  return {
    items: [item("1")],
    nextAfter: "cursor-1",
    hasMore: true,
    degraded: false,
    ...overrides,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

describe("SearchResults", () => {
  beforeEach(() => {
    vi.mocked(searchService.search).mockReset();
  });

  afterEach(cleanup);

  it("appends a cursor page, deduplicates IDs and updates pagination state", async () => {
    vi.mocked(searchService.search).mockResolvedValue(
      response({
        items: [item("1", "重复文章"), item("2"), item("2", "同页重复")],
        hasMore: false,
        nextAfter: null,
      }),
    );
    render(
      <SearchResults
        query="冬日"
        tags={["随笔"]}
        sort="newest"
        initial={response()}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "加载更多" }));

    expect(await screen.findByText("文章 2")).toBeVisible();
    expect(screen.getAllByRole("article")).toHaveLength(2);
    expect(searchService.search).toHaveBeenCalledWith({
      q: "冬日",
      size: 12,
      tags: ["随笔"],
      sort: "newest",
      after: "cursor-1",
    });
    expect(screen.queryByRole("button", { name: "加载更多" })).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("已加载全部结果");
  });

  it("keeps existing items on failure and retries from the same cursor", async () => {
    vi.mocked(searchService.search)
      .mockRejectedValueOnce(new Error("offline"))
      .mockResolvedValueOnce(
        response({ items: [item("2")], hasMore: false, nextAfter: null }),
      );
    render(
      <SearchResults query="冬日" tags={[]} sort="relevance" initial={response()} />,
    );

    fireEvent.click(screen.getByRole("button", { name: "加载更多" }));
    expect(await screen.findByText("加载失败，请重试")).toBeVisible();
    expect(screen.getByText("文章 1")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "重试" }));

    expect(await screen.findByText("文章 2")).toBeVisible();
    expect(searchService.search).toHaveBeenCalledTimes(2);
    expect(vi.mocked(searchService.search).mock.calls[1][0].after).toBe("cursor-1");
  });

  it("locks duplicate load-more clicks while a request is pending", async () => {
    const pending = deferred<SearchResponse>();
    vi.mocked(searchService.search).mockReturnValue(pending.promise);
    render(
      <SearchResults query="冬日" tags={[]} sort="relevance" initial={response()} />,
    );
    const button = screen.getByRole("button", { name: "加载更多" });

    fireEvent.click(button);
    fireEvent.click(button);

    expect(searchService.search).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("region", { name: "搜索结果" })).toHaveAttribute(
      "aria-busy",
      "true",
    );
    await act(async () => {
      pending.resolve(response({ items: [], hasMore: false, nextAfter: null }));
    });
  });

  it("resets to changed props and ignores a previous query response", async () => {
    const oldRequest = deferred<SearchResponse>();
    vi.mocked(searchService.search).mockReturnValue(oldRequest.promise);
    const { rerender } = render(
      <SearchResults query="旧查询" tags={[]} sort="relevance" initial={response()} />,
    );
    fireEvent.click(screen.getByRole("button", { name: "加载更多" }));

    rerender(
      <SearchResults
        query="新查询"
        tags={["新标签"]}
        sort="newest"
        initial={response({
          items: [item("new", "新查询结果")],
          hasMore: false,
          nextAfter: null,
        })}
      />,
    );
    expect(screen.getByRole("heading", { name: "新查询结果" })).toBeVisible();
    expect(screen.queryByText("文章 1")).not.toBeInTheDocument();

    await act(async () => {
      oldRequest.resolve(
        response({ items: [item("old", "旧请求迟到")], hasMore: false, nextAfter: null }),
      );
    });
    expect(screen.getByRole("heading", { name: "新查询结果" })).toBeVisible();
    expect(screen.queryByText("旧请求迟到")).not.toBeInTheDocument();
  });
});
