import { beforeEach, describe, expect, it, vi } from "vitest";
import { postService } from "@/lib/services/postService";
import { recommendationService } from "@/lib/services/recommendationService";

vi.mock("@/lib/services/postService", () => ({
  postService: {
    detailById: vi.fn(),
  },
}));

describe("recommendationService", () => {
  beforeEach(() => {
    vi.mocked(postService.detailById).mockReset();
  });

  it("preserves the publication time while hydrating a recommendation", async () => {
    const publishTime = "2026-07-18T08:30:00.000Z";
    vi.mocked(postService.detailById).mockResolvedValue({
      id: "post-1",
      slug: "editorial-notes",
      title: "编辑札记",
      description: "摘要",
      contentUrl: "/content/editorial-notes.md",
      images: [],
      tags: ["编辑精选"],
      authorNickname: "kzn",
      likeCount: 1,
      favoriteCount: 2,
      isTop: false,
      visible: "public",
      type: "article",
      publishTime,
    });

    const [hydrated] = await recommendationService.hydrateFeedItems([
      {
        postId: "1",
        title: "编辑札记",
        score: 0.9,
        reason: "与你最近阅读的主题相关",
      },
    ]);

    expect(hydrated.publishTime).toBe(publishTime);
  });

  it("passes a Snowflake post ID to the detail API without numeric coercion", async () => {
    const postId = "335475888558837761";
    vi.mocked(postService.detailById).mockResolvedValue({
      id: postId,
      slug: "city-restless-town",
      title: "关于《CITY》：京都动画怎样画一座停不下来的城",
      description: "摘要",
      contentUrl: "/content/city-restless-town.md",
      images: ["/uploads/city-restless-town-cover.webp"],
      tags: ["京都动画"],
      authorNickname: "kzn",
      likeCount: 1,
      favoriteCount: 2,
      isTop: false,
      visible: "public",
      type: "article",
    });

    const [hydrated] = await recommendationService.hydrateFeedItems([
      {
        postId,
        title: "关于《CITY》：京都动画怎样画一座停不下来的城",
        score: 0.9,
        reason: "兴趣标签匹配 + 内容相似",
      },
    ]);

    expect(postService.detailById).toHaveBeenCalledWith(postId);
    expect(hydrated).toMatchObject({
      id: postId,
      coverImage: "/uploads/city-restless-town-cover.webp",
    });
  });
});
