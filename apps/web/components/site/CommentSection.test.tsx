import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import CommentSection from "@/components/site/CommentSection";

const { list } = vi.hoisted(() => ({ list: vi.fn() }));

vi.mock("@/lib/services/commentService", () => ({
  commentService: {
    list,
    create: vi.fn(),
  },
}));
vi.mock("@/lib/auth/auth-store", () => ({ useStoredAuth: () => null }));
vi.mock("@/components/site/ChthollyIllustration", () => ({
  ChthollyIllustration: () => <div data-testid="chtholly" />,
}));

describe("CommentSection", () => {
  beforeEach(() => {
    list.mockReset();
  });

  it("links a normal commenter avatar and name to the public profile", async () => {
    list.mockResolvedValue({
      items: [
        {
          id: "1",
          postId: "42",
          parentId: null,
          userId: "7",
          authorHandle: "rekyrice",
          authorNickname: "Rekyrice",
          authorAvatar: "/avatar.webp",
          content: "这里的留白很好。",
          createdAt: "2026-07-16T00:00:00Z",
          chtholly: false,
          replies: [],
        },
      ],
      total: 1,
      page: 1,
      size: 20,
      hasMore: false,
    });

    render(<CommentSection postId="42" />);

    const profileLinks = await screen.findAllByRole("link", { name: "Rekyrice" });
    expect(profileLinks).not.toHaveLength(0);
    expect(profileLinks[0]).toHaveAttribute("href", "/user/rekyrice");
    expect(screen.getByRole("img", { name: "Rekyrice" }).getAttribute("src"))
      .toContain("%2Favatar.webp");
  });
});
