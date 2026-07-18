import { beforeEach, describe, expect, it, vi } from "vitest";
import { apiFetch } from "@/lib/services/apiClient";
import { searchService } from "@/lib/services/searchService";

vi.mock("@/lib/services/apiClient", () => ({
  apiFetch: vi.fn(),
}));

describe("searchService", () => {
  beforeEach(() => {
    vi.mocked(apiFetch).mockReset();
    vi.mocked(apiFetch).mockResolvedValue({
      items: [],
      nextAfter: null,
      hasMore: false,
      degraded: false,
    });
  });

  it("serializes search options in a stable order", async () => {
    await searchService.search({
      q: "珂朵莉 notes",
      size: 12,
      tags: ["随笔", "世界观"],
      sort: "newest",
      after: "12:post/7",
    });

    expect(apiFetch).toHaveBeenCalledWith(
      "/api/v1/search?q=%E7%8F%82%E6%9C%B5%E8%8E%89+notes&size=12&tags=%E9%9A%8F%E7%AC%94%2C%E4%B8%96%E7%95%8C%E8%A7%82&sort=newest&after=12%3Apost%2F7",
    );
  });

  it("sends the relevance sort contract when sort is omitted", async () => {
    await searchService.search({ q: "winter" });

    expect(apiFetch).toHaveBeenCalledWith(
      "/api/v1/search?q=winter&size=20&sort=relevance",
    );
  });

  it("keeps suggest and hub feed contracts available", async () => {
    await searchService.suggest("a/b", 8);
    await searchService.hubFeed(["世界观"], 2, 6);

    expect(apiFetch).toHaveBeenNthCalledWith(
      1,
      "/api/v1/search/suggest?prefix=a%2Fb&size=8",
    );
    expect(apiFetch).toHaveBeenNthCalledWith(
      2,
      "/api/v1/search/hub-feed?interestTags=%E4%B8%96%E7%95%8C%E8%A7%82&page=2&size=6",
    );
  });
});
