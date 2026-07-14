import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import ChthollyAvatar from "@/components/site/ChthollyAvatar";

describe("ChthollyAvatar", () => {
  afterEach(cleanup);

  it.each(["sm", "md", "lg"] as const)(
    "uses the transparent Chtholly4 silhouette at %s size",
    (size) => {
      render(<ChthollyAvatar size={size} />);

      const avatar = screen.getByTestId("chtholly-avatar");
      expect(avatar).toHaveAttribute("data-size", size);
      expect(avatar).not.toHaveClass("rounded-full");
      expect(avatar.querySelector("img")).toHaveAttribute(
        "src",
        expect.stringContaining("chtholly4.png"),
      );
    },
  );
});
