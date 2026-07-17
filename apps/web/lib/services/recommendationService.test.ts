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
        postId: 1,
        title: "编辑札记",
        score: 0.9,
        reason: "与你最近阅读的主题相关",
      },
    ]);

    expect(hydrated.publishTime).toBe(publishTime);
  });
});
