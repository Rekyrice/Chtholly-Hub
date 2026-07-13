import { beforeEach, describe, expect, it, vi } from "vitest";
import { apiFetch } from "@/lib/services/apiClient";
import { topicService } from "@/lib/services/topicService";

vi.mock("@/lib/services/apiClient", () => ({
  apiFetch: vi.fn(),
}));

describe("topicService", () => {
  beforeEach(() => {
    vi.mocked(apiFetch).mockReset();
  });

  it("loads public topic clusters", async () => {
    vi.mocked(apiFetch).mockResolvedValue([
      {
        topicName: "冬季追番",
        summary: "本周主题",
        size: 8,
        keyEntities: ["动画"],
        clusteredAt: "2026-07-13T00:00:00Z",
      },
    ]);

    await expect(topicService.list()).resolves.toEqual([
      expect.objectContaining({ topicName: "冬季追番", size: 8 }),
    ]);
    expect(apiFetch).toHaveBeenCalledWith("/api/v1/topics");
  });

  it("encodes topic names when loading posts", async () => {
    vi.mocked(apiFetch).mockResolvedValue([]);

    await topicService.posts("角色 关系/观察");

    expect(apiFetch).toHaveBeenCalledWith(
      "/api/v1/topics/%E8%A7%92%E8%89%B2%20%E5%85%B3%E7%B3%BB%2F%E8%A7%82%E5%AF%9F/posts",
    );
  });
});
