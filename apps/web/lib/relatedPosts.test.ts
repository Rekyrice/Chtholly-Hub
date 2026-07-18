import { createElement, type ComponentType } from "react";
import { readFileSync } from "node:fs";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import RelatedPosts from "@/components/site/RelatedPosts";
import { postService } from "@/lib/services/postService";
import {
  loadRelatedPostCards,
  type RelatedPostCardModel,
} from "@/lib/relatedPosts";

vi.mock("@/lib/services/postService", () => ({
  postService: {
    related: vi.fn(),
    detailById: vi.fn(),
  },
}));

const mockedPostService = vi.mocked(postService);

afterEach(cleanup);
beforeEach(() => {
  vi.clearAllMocks();
});

describe("loadRelatedPostCards", () => {
  it("returns an empty list when the related request fails", async () => {
    mockedPostService.related.mockRejectedValue(new Error("related unavailable"));

    await expect(loadRelatedPostCards("post-1")).resolves.toEqual([]);
    expect(mockedPostService.detailById).not.toHaveBeenCalled();
  });

  it("keeps summary-only cards when one detail request fails", async () => {
    mockedPostService.related.mockResolvedValue([
      {
        id: "related-1",
        slug: "summary-slug",
        title: "摘要文章",
        summary: "仍然可读的摘要",
        sharedEntities: ["蓝天"],
      },
      {
        id: "related-2",
        title: "没有链接的摘要",
      },
    ]);
    mockedPostService.detailById.mockRejectedValue(new Error("detail unavailable"));

    await expect(loadRelatedPostCards("post-1")).resolves.toEqual([
      expect.objectContaining({
        id: "related-1",
        title: "摘要文章",
        href: "/post/summary-slug",
      }),
      expect.objectContaining({
        id: "related-2",
        title: "没有链接的摘要",
        href: undefined,
      }),
    ]);
  });

  it("enriches cards from details and respects the requested limit", async () => {
    mockedPostService.related.mockResolvedValue([
      { id: "related-1", title: "服务端推荐标题", summary: "推荐摘要" },
      { id: "related-2", title: "不会被加载" },
    ]);
    mockedPostService.detailById.mockResolvedValue({
      id: "related-1",
      slug: "complete-post",
      title: "详情标题",
      description: "详情描述",
      contentUrl: "/content.md",
      images: ["/cover.webp"],
      tags: [],
      authorNickname: "珂朵莉",
      likeCount: 0,
      favoriteCount: 0,
      isTop: false,
      visible: "public",
      type: "article",
    });

    await expect(loadRelatedPostCards("post-1", 1)).resolves.toEqual([
      {
        id: "related-1",
        slug: "complete-post",
        title: "服务端推荐标题",
        summary: "推荐摘要",
        description: "详情描述",
        coverImage: "/cover.webp",
        authorNickname: "珂朵莉",
        href: "/post/complete-post",
      },
    ]);
    expect(mockedPostService.detailById).toHaveBeenCalledTimes(1);
    expect(mockedPostService.detailById).toHaveBeenCalledWith("related-1");
  });
});

describe("RelatedPosts", () => {
  it("renders preloaded linked and summary-only cards without requesting data", () => {
    const cards: RelatedPostCardModel[] = [
      {
        id: "linked",
        title: "可以继续阅读",
        summary: "有详情链接",
        href: "/post/linked",
      },
      {
        id: "summary-only",
        title: "只有推荐摘要",
        summary: "补全失败时仍然展示",
      },
    ];
    const DisplayOnlyRelatedPosts = RelatedPosts as unknown as ComponentType<{
      cards: RelatedPostCardModel[];
    }>;

    render(createElement(DisplayOnlyRelatedPosts, { cards }));

    expect(screen.getByRole("link", { name: /可以继续阅读/ })).toHaveAttribute(
      "href",
      "/post/linked",
    );
    expect(screen.getByText("只有推荐摘要").closest("article")).toHaveClass(
      "related-post-card--static",
    );
    expect(mockedPostService.related).not.toHaveBeenCalled();
    expect(mockedPostService.detailById).not.toHaveBeenCalled();
  });

  it("keeps page loading concurrent and shares the same cards across both placements", () => {
    const pageSource = readFileSync("app/(site)/post/[slug]/page.tsx", "utf8");

    expect(pageSource).toContain("const [markdown, relatedPosts] = await Promise.all([");
    expect(pageSource).toContain("loadRelatedPostCards(post.id)");
    expect(pageSource).toContain("<RelatedPosts cards={relatedPosts} />");
    expect(pageSource.match(/relatedPosts={relatedPosts}/g)).toHaveLength(2);
  });
});
