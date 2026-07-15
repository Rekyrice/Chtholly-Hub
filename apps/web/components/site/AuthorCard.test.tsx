import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import AuthorCard from "@/components/site/AuthorCard";

vi.mock("@/components/site/FollowButton", () => ({ default: () => null }));
vi.mock("@/components/site/PostOwnerActions", () => ({ default: () => null }));

describe("AuthorCard", () => {
  it("shows the canonical biography and links to the handle profile", () => {
    render(
      <AuthorCard
        authorId="7"
        authorHandle="rekyrice"
        avatar="/avatar.webp"
        nickname="Rekyrice"
        bio="写点看完之后没有散掉的东西。"
      />,
    );

    expect(screen.getByText("写点看完之后没有散掉的东西。")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "查看作者所有文章" })).toHaveAttribute(
      "href",
      "/user/rekyrice",
    );
  });
});
