import { readFileSync } from "node:fs";
import { cleanup, render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { RouteVisualConfig } from "@/lib/route-visuals";
import NotFound from "./not-found";

vi.mock("@/components/site/SiteChrome", () => ({
  default: ({
    children,
    visualOverride,
  }: {
    children: ReactNode;
    visualOverride?: RouteVisualConfig;
  }) => (
    <div
      data-testid="site-chrome"
      data-visual-id={visualOverride?.id}
      data-visual-images={visualOverride?.page.images.join(",")}
    >
      {children}
    </div>
  ),
}));

describe("root not found", () => {
  afterEach(() => cleanup());

  it("uses the dedicated full-page visual without a local background layer", () => {
    const { container } = render(<NotFound />);

    expect(screen.getByTestId("site-chrome")).toHaveAttribute(
      "data-visual-id",
      "not-found",
    );
    expect(screen.getByTestId("site-chrome")).toHaveAttribute(
      "data-visual-images",
      "/images/site/backgrounds/not-found.webp",
    );
    expect(container.querySelector(".not-found-background")).not.toBeInTheDocument();
    expect(container.querySelector(".not-found-background__image")).not.toBeInTheDocument();
    expect(container.querySelector(".not-found-background__scrim")).not.toBeInTheDocument();
  });

  it("keeps the illustration, copy, and recovery action", () => {
    render(<NotFound />);

    expect(screen.getByRole("img", { name: "珂朵莉迷路了" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: "404" })).toBeInTheDocument();
    expect(screen.getByText("这个页面迷失在妖精仓库了……")).toBeInTheDocument();
    expect(screen.getByText("珂朵莉找了好久也没找到呢")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "回到仓库" })).toHaveAttribute("href", "/hub");
  });

  it("does not define a second local image or scrim in the page stylesheet", () => {
    const css = readFileSync("app/styles/not-found.css", "utf8");

    expect(css).not.toContain(".not-found-background");
    expect(css).not.toContain("/images/landing/default.jpg");
    expect(css).not.toContain("background__scrim");
  });
});
