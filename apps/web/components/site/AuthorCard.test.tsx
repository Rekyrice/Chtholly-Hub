import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import AuthorCard from "@/components/site/AuthorCard";

vi.mock("@/components/site/FollowButton", () => ({ default: () => null }));
vi.mock("@/components/site/PostOwnerActions", () => ({ default: () => null }));

afterEach(cleanup);

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
    expect(screen.getByRole("link", { name: "Rekyrice" })).toHaveAttribute("href", "/user/rekyrice");
    expect(screen.getByRole("link", { name: "查看作者所有文章" })).toHaveAttribute(
      "href",
      "/user/rekyrice",
    );
  });

  it("does not invent a hub fallback when the canonical handle is unavailable", () => {
    render(<AuthorCard authorId="7" nickname="Temporary author" />);

    expect(screen.queryByRole("link", { name: "Temporary author" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "查看作者所有文章" })).not.toBeInTheDocument();
  });
});
