import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import RoutePageBackground from "@/components/site/RoutePageBackground";

describe("RoutePageBackground", () => {
  it("exposes the route visual contract as CSS custom properties", () => {
    const { container } = render(
      <RoutePageBackground
        background={{
          image: "/images/site/backgrounds/hub-content.webp",
          positionDesktop: "52% 40%",
          positionMobile: "56% 44%",
          overlayAlpha: 0.24,
          blurPx: 1.5,
          saturate: 0.93,
        }}
      />,
    );

    const background = screen.getByTestId("route-page-background");
    expect(background).toHaveAttribute("aria-hidden", "true");
    expect(background).toHaveClass("route-page-background");
    expect(background.style.getPropertyValue("--route-bg-image")).toBe(
      'url("/images/site/backgrounds/hub-content.webp")',
    );
    expect(background.style.getPropertyValue("--route-bg-position")).toBe("52% 40%");
    expect(background.style.getPropertyValue("--route-bg-position-mobile")).toBe("56% 44%");
    expect(background.style.getPropertyValue("--route-bg-overlay")).toBe("0.24");
    expect(background.style.getPropertyValue("--route-bg-blur")).toBe("1.5px");
    expect(background.style.getPropertyValue("--route-bg-saturate")).toBe("0.93");
    expect(container.querySelector(".route-page-background__image")).toBeInTheDocument();
    expect(container.querySelector(".route-page-background__overlay")).toBeInTheDocument();
  });
});
